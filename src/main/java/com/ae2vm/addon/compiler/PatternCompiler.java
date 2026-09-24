package com.ae2vm.addon.compiler;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import java.math.BigInteger;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.Opcode;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

public class PatternCompiler {
   public interface DetachedPattern extends IPatternDetails { }
   /** Values observed at the live API boundary; no worker-thread world query is permitted. */
   public interface DetachedInput extends IInput {
      AEKey craftedKey();
      boolean emittable();
      /** Observe only candidates encountered by this VM request, in a server-owned batch. */
      default void prepareCandidates(java.util.Collection<AEKey> keys) { }
   }
   public interface Scope extends AutoCloseable { @Override void close(); }
   private static final class State {
      final Map<IPatternDetails, CraftingBytecode> compiled = new ConcurrentHashMap<>();
      final Map<AEKey, java.util.Set<AEKey>> fuzzy = new ConcurrentHashMap<>();
      final java.util.Set<AEKey> processing = ConcurrentHashMap.newKeySet();
      final java.util.Set<AEKey> exact = ConcurrentHashMap.newKeySet();
   }
   private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
   public static Scope openScope() {
      State previous = STATE.get();
      STATE.set(new State());
      return () -> STATE.set(previous);
   }
   private static final Map<IPatternDetails, CraftingBytecode> COMPILED_PATTERNS = new ConcurrentHashMap<>();

   /**
    * (v1.11.x PATTERN-REFRESH) Monotonic version of the network's pattern set. Bumped
    * whenever a pattern provider's {@code updatePatterns} runs (a player ADDS / REMOVES /
    * MODIFIES a pattern in a provider). CraftingVM's JIT {@code bundleCache} persists
    * across requests on a reused VM; a bundle captured while an intermediate key had NO
    * pattern records that key as a capture-time {@code missing} leaf. If the player then
    * adds that pattern, the stale bundle would keep reporting the intermediate as missing
    * until a restart (the 1.20.1 "新样板作为中间产物识别不到" report). Each VM checks this
    * version at {@code execute()} and drops its JIT memo when it changed, so the next
    * request re-captures the affected chains and recognises the new pattern.
    */
   private static volatile long patternVersion = 0;

   /** Current pattern-set version (monotonic). */
   public static long patternVersion() {
      return patternVersion;
   }

   /** Mark the network pattern set as changed (call from pattern-update entry points). */
   public static void bumpPatternVersion() {
      patternVersion++;
      // (v1.11.x DIAG) Log when pattern version bumps — if this never fires after a
      // pattern is added, the mixin is not being applied or the target method is wrong.
      // LOG disabled (v1.8.20/GTL): keep only total calc time.
      // AE2VMAddon.LOGGER.info("[AE2-VM] bumpPatternVersion: {} -> {}", patternVersion - 1, patternVersion);
   }

   /**
    * Fuzzy / fluid-substitution groups (v1.9.13): pattern inputs whose
    * {@code getPossibleInputs()} returns MORE than one variant — i.e. the encoded
    * pattern has item-replacement (物品替换) or fluid-replacement (流体替换) enabled.
    * Each group maps every variant key to the full set of acceptable variants, so the
    * VM's missing-check can see that e.g. gray wool can be satisfied by white wool
    * stock. Groups are registered from {@link #registerFuzzyGroups(IPatternDetails)},
    * which {@link #compilePattern(IPatternDetails)} calls for every compiled pattern,
    * and are consumed by {@code CraftingVM}'s aggregation (fuzzy-group stock) and the
    * no-sub-pattern leaf check.
    */
   private static final Map<AEKey, java.util.Set<AEKey>> FUZZY_GROUPS = new ConcurrentHashMap<>();

   /**
    * Processing-recipe input keys (v1.10.x). Processing recipes (处理配方) default to
    * FUZZY matching: a stored variant of the input item (same item, any NBT/damage —
    * {@code FuzzyMode.IGNORE_ALL}) satisfies the slot, even though AE2's
    * {@code AEProcessingPattern} encodes each input as a single exact variant with no
    * substitution flag. This mirrors AE2 native's inventory-level lookup
    * ({@code CraftingCpuHelper.getValidItemTemplates} → {@code findFuzzyTemplates}),
    * which the VM was missing — it treated processing inputs as exact and reported
    * false "missing" (GTL greenhouse fake-craft / Mystical Agriculture essence:
    * "材料缺失但不知道哪里缺失", "有方块却报缺失"). Keys are collected from every
    * processing pattern at compile time and consumed by {@code CraftingVM}'s
    * missing-check / stock-aware aggregation / extraction.
    */
   private static final java.util.Set<AEKey> PROCESSING_INPUT_KEYS = ConcurrentHashMap.newKeySet();

   /**
    * (v1.10.x PRODUCTIVE_BEES) EXACT processing-recipe input keys. AE2's native
    * {@code AEProcessingPattern} encodes each input as a single EXACT variant and its
    * {@code IInput.isValid} is {@code input.matches(template[0])} = exact {@code equals}
    * (see CraftingCpuHelper.getValidItemTemplates, which filters every
    * {@code findFuzzyTemplates} NBT variant through {@code isValid} — only the encoded
    * exact variant passes). So for AE2-native processing patterns a different-NBT variant
    * of the same item (e.g. Productive Bees honeycombs with different {@code bee_type}
    * components) must NOT satisfy the slot — using the wrong honeycomb variant would
    * consume the wrong raw material. Only third-party patterns whose {@code isValid}
    * genuinely accepts variants (GTL greenhouse, MA essence, UselessMod omniversal) keep
    * the v1.10.x default-fuzzy behaviour via {@link #PROCESSING_INPUT_KEYS}.
    * <p>Keys are moved here (from the default-fuzzy set) by
    * {@link #registerFuzzyGroups(IPatternDetails)} for AE2-native patterns, or registered
    * directly by callers via {@link #registerExactProcessingInput(AEKey)}.
    */
   private static final java.util.Set<AEKey> EXACT_PROCESSING_KEYS = ConcurrentHashMap.newKeySet();

   /** True for patterns that are NOT molecular-assembler crafting patterns. */
   private static boolean isProcessingPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return false;
      }
      if (pattern instanceof DetachedPattern) return false;
      // Crafting patterns (AECraftingPattern) run in the ME molecular assembler and
      // implement IMolecularAssemblerSupportedPattern; everything else (AE processing
      // patterns, custom machine patterns like GTL's fake-craft) is a processing recipe.
      return !(pattern instanceof appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern);
   }

   /** True if {@code key} is an input of a processing recipe with default fuzzy matching.
    *  AE2-native exact processing inputs (e.g. bee_type honeycombs) return false. */
   public static boolean isProcessingInput(AEKey key) {
      return key != null && STATE.get().processing.contains(key)
            && !STATE.get().exact.contains(key);
   }

   /** Mark {@code key} as an EXACT processing input (no NBT-variant substitution). */
   public static void registerExactProcessingInput(AEKey key) {
      if (key == null) {
         return;
      }
      STATE.get().processing.remove(key);
      STATE.get().exact.add(key);
   }

   public static void clearProcessingInputKeys() {
      STATE.get().processing.clear();
      STATE.get().exact.clear();
   }

   /** Register every input variant group of {@code pattern} (call once per pattern at encode time). */
   public static void registerFuzzyGroups(IPatternDetails pattern) {
      if (pattern == null) {
         return;
      }
      boolean processing = isProcessingPattern(pattern);
      // (v1.10.x PRODUCTIVE_BEES) AE2's native processing pattern encodes each input as an
      // EXACT variant (its IInput.isValid is exact equals — see class doc on
      // EXACT_PROCESSING_KEYS). A different-NBT variant (e.g. a honeycomb with another
      // bee_type component) must NOT satisfy such a slot — otherwise the VM consumes the
      // WRONG honeycomb as raw material. Only third-party processing patterns (whose
      // isValid genuinely accepts variants) keep the default-fuzzy PROCESSING_INPUT set.
      boolean nativeAE2Processing = pattern instanceof appeng.crafting.pattern.AEProcessingPattern;
      IPatternDetails.IInput[] patternInputs = pattern.getInputs();
      if (patternInputs == null) {
         return; // (v1.12.x GTL DEFENSIVE) exotic pattern without an input list
      }
      for (IInput inputEntry : patternInputs) {
         GenericStack[] possibleInputs = inputEntry.getPossibleInputs();
         if (possibleInputs == null) {
            continue;
         }
         if (processing) {
            // Processing recipes default to fuzzy matching: remember the input's primary
            // key so the VM matches it against the item's full fuzzy family at runtime.
            if (possibleInputs != null && possibleInputs.length > 0
                  && possibleInputs[0] != null && possibleInputs[0].what() != null) {
               if (nativeAE2Processing) {
                  // Exact: move to the EXACT set so isProcessingInput() returns false.
                  STATE.get().exact.add(possibleInputs[0].what());
               } else {
                  // Third-party default-fuzzy: remember the primary key so the VM matches
                  // it against the item's full fuzzy family at runtime.
                  STATE.get().processing.add(possibleInputs[0].what());
               }
            }
         }
         if (possibleInputs == null || possibleInputs.length <= 1) {
            continue; // exact input (replacement not encoded) — no fuzzy group
         }
         java.util.Set<AEKey> group = new java.util.HashSet<>();
         for (GenericStack gs : possibleInputs) {
            if (gs != null && gs.what() != null) {
               group.add(gs.what());
            }
         }
         if (group.size() > 1) {
            for (AEKey k : group) {
               STATE.get().fuzzy.merge(k, group, (a, b) -> {
                  a.addAll(b);
                  return a;
               });
            }
         }
      }
   }

   /** The full set of acceptable variants for {@code key} (always contains {@code key} itself). */
   public static java.util.Set<AEKey> getFuzzyGroup(AEKey key) {
      java.util.Set<AEKey> group = STATE.get().fuzzy.get(key);
      return group != null ? group : java.util.Set.of(key);
   }

   public static void clearFuzzyGroups() {
      STATE.get().fuzzy.clear();
      STATE.get().processing.clear();
      STATE.get().exact.clear();
   }

   /**
    * True for UselessMod's virtual smart-doubling wrapper {@code ScaledProcessingPattern}
    * (and the benchmark stand-in {@code ScaledBenchPatternDetails}): a runtime wrapper
    * whose class name carries {@code Scaled…Pattern}. Such wrappers are NOT part of the
    * pattern-provider lists the {@code updatePatterns} pass compiles, so every compiler
    * entry point unwraps them to the ORIGINAL pattern before compiling (v1.10.8).
    */
   private static boolean isScaledPattern(IPatternDetails pattern) {
      if (pattern == null) {
         return false;
      }
      String cn = pattern.getClass().getName();
      return cn.contains("Scaled") && cn.contains("Pattern");
   }

   /**
    * Recursively unwraps a virtual smart-doubling wrapper down to its ORIGINAL pattern via
    * its {@code getOriginal()} accessor (reflective — the wrapper is an optional third-party
    * class). Returns {@code pattern} unchanged when it is not a scaled wrapper or
    * unwrapping fails. Compiling the ORIGINAL (never the virtual wrapper) keeps
    * {@code outputPerCraft} at the real per-craft amount and makes every plan's
    * {@code patternTimes} key a real AE2 pattern that the Crafting CPU / {@code getProviders}
    * / furnace {@code pushPattern} recognise — UselessMod re-applies smart-doubling at
    * submit time because the key is NOT a {@code ScaledProcessingPattern}.
    */
   private static IPatternDetails unwrapScaled(IPatternDetails pattern) {
      if (pattern == null) {
         return null;
      }
      IPatternDetails current = pattern;
      while (isScaledPattern(current)) {
         try {
            var method = current.getClass().getMethod("getOriginal");
            Object original = method.invoke(current);
            if (!(original instanceof IPatternDetails) || original == null) {
               break;
            }
            current = (IPatternDetails) original;
         } catch (Exception e) {
            break; // not a wrapper we can unwrap — keep current
         }
      }
      return current;
   }

   public static void compileIfAbsent(IPatternDetails pattern) {
      IPatternDetails effective = unwrapScaled(pattern);
      // (v1.12.x GTL DEFENSIVE) Patterns with NO usable output (empty getOutputs() or
      // null primary output — possible with buggy/partial recipes in modpacks) cannot be
      // crafted: skip them instead of letting compilePattern NPE and dragging the whole
      // request into a native fallback (stall).
      if (effective != null && hasUsableOutput(effective) && effective.getInputs() != null) {
         STATE.get().compiled.computeIfAbsent(effective, PatternCompiler::compileOrderedPattern);
      }
   }

   /** True if the pattern exposes at least one output with a non-null key. */
   private static boolean hasUsableOutput(IPatternDetails pattern) {
      try {
         GenericStack[] outputs = pattern.getOutputs();
         if (outputs == null || outputs.length == 0) {
            return false;
         }
         GenericStack primary = pattern.getPrimaryOutput();
         return primary != null && primary.what() != null;
      } catch (RuntimeException e) {
         return false; // defensive: an exotic pattern that throws on inspection is unusable
      }
   }

   public static CraftingBytecode getCompiled(IPatternDetails pattern) {
      return STATE.get().compiled.get(unwrapScaled(pattern));
   }

   public static CraftingBytecode compileRequest(IPatternDetails pattern, long requestedAmount) {
      return compileRequest(pattern, BigInteger.valueOf(requestedAmount));
   }

   public static CraftingBytecode compileRequest(AEKey output, BigInteger requestedAmount) {
      if (requestedAmount.signum() <= 0) throw new IllegalArgumentException("positive crafting request required");
      var builder = new CraftingBytecode.Builder();
      int key = builder.addConstant(output);
      builder.setOutput(key, requestedAmount);
      builder.emitPushAmount(requestedAmount);
      builder.emitCallByKey(key);
      return builder.build();
   }

   public static CraftingBytecode compileRequest(IPatternDetails pattern, BigInteger requestedAmount) {
      if (requestedAmount.signum() <= 0) throw new IllegalArgumentException("positive crafting request required");
      IPatternDetails effective = unwrapScaled(pattern);
      CraftingBytecode patternBytecode = STATE.get().compiled.get(effective);
      if (patternBytecode == null) {
         compileIfAbsent(effective);
         patternBytecode = STATE.get().compiled.get(effective);
         if (patternBytecode == null) {
            throw new IllegalStateException("Failed to compile pattern: " + pattern);
         }
      }

      BigInteger outputPerCraft = patternBytecode.getExactOutputAmountPerCraft();
      // (v1.12.x GTL BIG-ORDER FIX) Saturating ceil-div — (a + b - 1) overflows to a
      // negative craft count for requestedAmount near Long.MAX_VALUE (10^18+ orders):
      // e.g. MAX + 2 - 1 wraps to Long.MIN_VALUE, / 2 → negative → the plan silently
      // crafts nothing ("大数量订单假阴/卡死"). a/b + (a%b!=0) never overflows.
      BigInteger craftTimes = ceilDiv(requestedAmount, outputPerCraft);
      CraftingBytecode.Builder builder = new CraftingBytecode.Builder();
      int outputIdx = builder.addConstant(patternBytecode.getOutput());
      builder.setOutput(outputIdx, requestedAmount);
      // (v1.10.8) Use the UNWRAPPED (original) pattern as the plan's pattern key — never the
      // virtual scaled wrapper — so AE2's CPU / getProviders / furnace pushPattern all match.
      int patternIdx = builder.addPattern(effective);
      builder.emitPushAmount(craftTimes);
      builder.emit(Opcode.CALL);
      builder.emitShort(patternIdx);
      return builder.build();
   }

   private static CraftingBytecode compileOrderedPattern(IPatternDetails pattern) {
      // #208: a slot's units/returns cannot be reconstructed from a global fuzzy-key union.
      var builder = new CraftingBytecode.Builder();
      var primary = pattern.getPrimaryOutput();
      if (primary.amount() <= 0) throw new IllegalArgumentException("positive pattern output required");
      builder.setOutput(builder.addConstant(primary.what()), primary.amount());
      int patternIndex = builder.addPattern(pattern);
      var inputs = pattern.getInputs();
      for (int slot = 0; slot < inputs.length; slot++) {
         var alternatives = inputs[slot].getPossibleInputs();
         if (alternatives.length == 0 || inputs[slot].getMultiplier() <= 0
               || java.util.Arrays.stream(alternatives).anyMatch(t -> t == null || t.amount() <= 0))
            throw new IllegalArgumentException("positive pattern input required");
         BigInteger amount = BigInteger.valueOf(alternatives[0].amount())
               .multiply(BigInteger.valueOf(inputs[slot].getMultiplier()));
         builder.emit(Opcode.DUP);
         builder.emitPushAmount(amount);
         builder.emit(Opcode.MUL);
         builder.emit(Opcode.REQUEST_INPUT);
         builder.emitShort(slot);
      }
      builder.emit(Opcode.RETURN_CONTAINERS);
      for (var output : pattern.getOutputs()) {
         if (output.amount() <= 0) throw new IllegalArgumentException("positive pattern output required");
         int index = builder.addConstant(output.what());
         builder.emit(Opcode.DUP);
         builder.emitPushLong(output.amount());
         builder.emit(Opcode.MUL);
         builder.emitInsertOutput(index);
      }
      builder.emitRecordPattern(patternIndex);
      builder.emit(Opcode.RETURN);
      return builder.build();
   }


   public static void invalidate(IPatternDetails pattern) {
      STATE.get().compiled.remove(unwrapScaled(pattern));
   }

   public static void clearCache() {
      STATE.get().compiled.clear();
   }

   public static int getCompiledCount() {
      return STATE.get().compiled.size();
   }

   public static IPatternDetails findCompiledByOutput(AEKey outputKey) {
      if (outputKey != null && outputKey.getId() != null) {

         for (Entry<IPatternDetails, CraftingBytecode> entry : STATE.get().compiled.entrySet()) {
            GenericStack patternOutput = entry.getKey().getPrimaryOutput();
            if (patternOutput != null && patternOutput.what() != null) {
               ResourceLocation patternId = patternOutput.what().getId();
               if (outputKey.equals(patternOutput.what())) {
                  return entry.getKey();
               }
            }

            for (GenericStack out : entry.getKey().getOutputs()) {
               if (out != null && outputKey.equals(out.what())) {
                  return entry.getKey();
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   // --- Network-key overloads (1.0.0 logic uses a single global cache, so the network key is ignored). ---
   public static void compileIfAbsent(Object network, IPatternDetails pattern) {
      compileIfAbsent(pattern);
   }

   public static CraftingBytecode getCompiled(Object network, IPatternDetails pattern) {
      return getCompiled(pattern);
   }

   public static CraftingBytecode compileRequest(Object network, IPatternDetails pattern, long requestedAmount) {
      return compileRequest(pattern, requestedAmount);
   }

   public static CraftingBytecode compileRequest(Object network, IPatternDetails pattern, BigInteger requestedAmount) {
      return compileRequest(pattern, requestedAmount);
   }

   public static BigInteger ceilDiv(BigInteger amount, BigInteger output) {
      if (amount.signum() < 0 || output.signum() <= 0)
         throw new IllegalArgumentException("invalid exact division");
      var divided = amount.divideAndRemainder(output);
      return divided[1].signum() == 0 ? divided[0] : divided[0].add(BigInteger.ONE);
   }

   public static IPatternDetails findCompiledByOutput(Object network, AEKey outputKey) {
      return findCompiledByOutput(outputKey);
   }

   /**
    * (v1.12.x GTL BIG-ORDER FIX) Saturated ceil-division. The naive
    * {@code (a + b - 1) / b} overflows when {@code a} is near {@link Long#MAX_VALUE}
    * (10^18+ orders), producing a NEGATIVE craft count — the VM then silently crafts
    * nothing and the plan reports false missing (or the job stalls). The remainder form
    * never overflows and equals ceil(a/b) for positive longs.
    */
   public static long ceilDiv(long a, long b) {
      if (a <= 0L) return 0L;
      if (b <= 0L) return 0L;
      return a / b + (a % b == 0L ? 0L : 1L);
   }
}
