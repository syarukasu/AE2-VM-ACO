package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.nativeengine.NativeVmAccess.ReadKind;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.addon.vm.VmSimulationState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import net.minecraft.world.level.Level;

/** Per-order coefficients, stock and candidate observations captured before VM arithmetic. */
final class NativeVmPlanningContext {
    private final IGrid grid;
    private final Level level;
    private final ICraftingSimulationRequester requester;
    private final VmAccounting.Stock stock;
    private final Runnable checkpoint;
    private final NativeVmAccess access;
    private final NativeVmTiming timing;
    private final Map<IPatternDetails, LivePattern> programs = new IdentityHashMap<>();
    private final Map<IPatternDetails, String> ids = new IdentityHashMap<>();
    private final Map<String, IPatternDetails> bindings = new LinkedHashMap<>();
    private final Map<AEKey, List<IPatternDetails>> producerChoices = new HashMap<>();
    private final Map<AEKey, List<IPatternDetails>> programsByKey = new HashMap<>();
    private final Map<IPatternDetails, RuntimeException> preparationFailures = new IdentityHashMap<>();
    private final Map<Object, java.util.Set<AEKey>> candidateFamilies = new HashMap<>();
    private final Map<Object, List<LiveInput>> inputsByFamily = new HashMap<>();
    private final Map<ICraftingService, Map<AEKey, Boolean>> emitterAnswers = new IdentityHashMap<>();
    private boolean producerServiceResolved;
    private ICraftingService producerService;
    private boolean emitterServiceResolved;
    private ICraftingService emitterService;
    private boolean catalogPrepared;
    private boolean candidateDomainClosed;
    private final java.util.Set<Object> openCandidateFamilies = new java.util.HashSet<>();

    NativeVmPlanningContext(IGrid grid, Level level, ICraftingSimulationRequester requester,
            VmAccounting.Stock stock, NativeVmAccess access, NativeVmTiming timing) {
        this.grid = grid;
        this.level = level;
        this.requester = requester;
        this.stock = stock;
        this.access = access;
        this.checkpoint = access::checkpoint;
        this.timing = timing;
        stock.amounts().forEach((key, amount) -> {
            if (amount.signum() > 0) includeCandidate(key);
        });
    }

    private void includeCandidate(AEKey key) {
        candidateFamilies.computeIfAbsent(key.getPrimaryKey(), ignored -> new java.util.LinkedHashSet<>()).add(key);
    }

    /** #218/#215: capture declared references, never the closure of future returned tool states. */
    void prepare(AEKey root) {
        if (catalogPrepared) return;
        var pending = new java.util.ArrayDeque<AEKey>();
        var visited = new java.util.HashSet<AEKey>();
        pending.add(root);
        while (!pending.isEmpty()) {
            checkpoint.run();
            var key = pending.remove();
            if (!visited.add(key)) continue;
            canEmit(emitterService(), key);
            for (var pattern : candidates(key)) {
                var captured = (LivePattern) pattern;
                for (var output : captured.getOutputs()) includeCandidate(output.what());
                try {
                    for (var slot : captured.getInputs()) {
                        var input = (LiveInput) slot;
                        input.prepare();
                        includeCandidate(input.crafted);
                        pending.add(input.crafted);
                        for (var template : input.templates) {
                            includeCandidate(template.what());
                            pending.add(template.what());
                        }
                    }
                } catch (java.util.concurrent.CancellationException cancelled) {
                    throw cancelled;
                } catch (RuntimeException failure) {
                    // A broken unused alternative must not abort a funded earlier producer.
                    preparationFailures.put(captured, failure);
                }
            }
        }
        var dynamicCandidateFamilies = new java.util.HashSet<Object>();
        // Outputs discovered late in the graph must be known to inputs visited earlier.
        for (var captured : programs.values()) {
            if (preparationFailures.containsKey(captured)) continue;
            for (var slot : captured.inputs) {
                var input = (LiveInput) slot;
                for (var family : java.util.Arrays.stream(input.templates).map(t -> t.what().getPrimaryKey()).distinct().toList())
                    inputsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>()).add(input);
                if (input.exact != null) continue;
                var candidates = new java.util.LinkedHashSet<AEKey>();
                for (var template : input.templates)
                    candidates.addAll(candidateFamilies.getOrDefault(template.what().getPrimaryKey(), java.util.Set.of()));
                input.prepareCandidates(candidates);
                if (!input.nativeCrafting || !input.metadata.nativeExact())
                    for (var template : input.templates)
                        if (input.metadata.rule() == null || !input.metadata.rule().covers(template.what()))
                            dynamicCandidateFamilies.add(template.what().getPrimaryKey());
            }
        }
        candidateDomainClosed = preparationFailures.isEmpty();
        for (var captured : programs.values()) {
            if (captured.inputs == null) continue;
            for (var slot : captured.inputs) for (var returned : ((LiveInput) slot).remainders.values()) {
                if (returned == null) continue;
                var family = candidateFamilies.get(returned.getPrimaryKey());
                if (family != null && !family.contains(returned)
                        && dynamicCandidateFamilies.contains(returned.getPrimaryKey())) {
                    candidateDomainClosed = false;
                    openCandidateFamilies.add(returned.getPrimaryKey());
                }
            }
        }
        catalogPrepared = true;
    }

    boolean requiresLiveAccess() {
        return !catalogPrepared || !candidateDomainClosed;
    }

    VmQuantities plan(AEKey root, BigInteger requested, boolean craftLess, LongConsumer progress) {
        try (var scope = PatternCompiler.openScope()) {
            var vm = new CraftingVM(this, key -> {
                var choices = candidates(key);
                return choices.isEmpty() ? null : choices.get(0);
            });
            vm.setAllPatternsResolver(this::candidates);
            vm.setEmitterResolver(key -> canEmit(emitterService(), key));
            vm.setProgressListener(progress);
            vm.setCheckpoint(checkpoint);
            com.ae2vm.addon.vm.VmCraftingPlan result;
            try (var span = timing.phase(NativeVmTiming.Phase.VM)) {
                try {
                    result = vm.execute(PatternCompiler.compileRequest(root, requested),
                            new VmSimulationState(stock.amounts(), stock.complete()), craftLess);
                } finally {
                    timing.execution(vm.stateCopies(), vm.branchTrials(), vm.branchEntries(), vm.aggregatedBlocks());
                }
            }
            Map<String, BigInteger> crafts = new LinkedHashMap<>();
            result.patternTimes().forEach((pattern, count) -> crafts.merge(
                    Objects.requireNonNull(ids.get(pattern)), count, BigInteger::add));
            return new VmQuantities(root, result.finalOutput().amount(), crafts,
                    result.usedItems().quantities(), result.emittedItems().quantities(),
                    result.missingItems().quantities(), result.bytes(), Map.of(), result.multiplePaths(),
                    vm.instructions(), vm.work(), vm.skippedIterations());
        }
    }

    private ICraftingService producerService() {
        if (!producerServiceResolved) {
            producerService = access.read(ReadKind.GRID, () -> {
                var node = requester.getGridNode();
                return node == null ? null : node.getGrid().getCraftingService();
            });
            producerServiceResolved = true;
        }
        return producerService;
    }

    private ICraftingService emitterService() {
        if (!emitterServiceResolved) {
            emitterService = access.read(ReadKind.GRID, grid::getCraftingService);
            emitterServiceResolved = true;
        }
        return emitterService;
    }

    private boolean canEmit(ICraftingService service, AEKey key) {
        if (service == null) return false;
        var answers = emitterAnswers.computeIfAbsent(service, ignored -> new HashMap<>());
        var known = answers.get(key);
        if (known != null) return known;
        if (catalogPrepared) throw new IllegalStateException("Emitter key outside the order snapshot: " + key);
        boolean answer = access.read(ReadKind.EMITTER, () -> service.canEmitFor(key));
        answers.put(key, answer);
        return answer;
    }

    private List<IPatternDetails> choicesFor(ICraftingService service, AEKey key) {
        var known = producerChoices.get(key);
        if (known != null) return known;
        if (catalogPrepared) throw new IllegalStateException("Producer key outside the order snapshot: " + key);
        var choices = access.read(ReadKind.PRODUCER, () -> List.copyOf(service.getCraftingFor(key)));
        producerChoices.put(key, choices);
        return choices;
    }

    private List<IPatternDetails> candidates(AEKey key) {
        checkpoint.run();
        var known = programsByKey.get(key);
        if (known != null) return known;
        var service = producerService();
        if (service == null) {
            programsByKey.put(key, List.of());
            return List.of();
        }
        // AE2 fixes each node's ordered producer list when that node is first explored.
        var choices = choicesFor(service, key);
        var result = new ArrayList<IPatternDetails>(choices.size());
        for (int start = 0; start < choices.size(); start += 16) {
            checkpoint.run();
            var batch = choices.subList(start, Math.min(start + 16, choices.size()));
            var missing = batch.stream().filter(source -> !programs.containsKey(source)).toList();
            if (!missing.isEmpty()) {
                var skeletons = access.read(ReadKind.PATTERN, () -> {
                    var captured = new IdentityHashMap<IPatternDetails, PatternSkeleton>();
                    for (var source : missing) {
                        var coefficients = NativePatternCoefficients.read(source);
                        var semantics = coefficients.pattern();
                        captured.put(source, new PatternSkeleton(source.getDefinition(), semantics,
                                semantics.getInputs().clone(), semantics.getOutputs().clone(), coefficients.scale()));
                    }
                    return captured;
                });
                for (var source : missing) {
                    if (programs.containsKey(source)) continue;
                    var program = new LivePattern(source, service, skeletons.get(source));
                    String id = "vm-" + bindings.size();
                    programs.put(source, program);
                    ids.put(program, id);
                    bindings.put(id, source);
                    timing.patternType(source.getClass().getName());
                }
            }
            for (var source : batch) result.add(programs.get(source));
        }
        var frozen = List.copyOf(result);
        programsByKey.put(key, frozen);
        return frozen;
    }

    private record InputMetadata(GenericStack[] templates, long multiplier, boolean nativeExact, NativeCraftingRule rule) { }

    private record PatternSkeleton(AEItemKey definition, IPatternDetails semantics, IPatternDetails.IInput[] inputs,
            GenericStack[] outputs, BigInteger scale) { }

    private final class LiveInput implements PatternCompiler.DetachedInput {
        private final IPatternDetails.IInput source;
        private final IPatternDetails pattern;
        private final ICraftingService service;
        private final InputMetadata metadata;
        private final GenericStack[] templates;
        private final long multiplier;
        private final BigInteger scale;
        private AEKey crafted;
        private boolean emitted;
        private AEKey exact;
        private boolean prepared;
        private final boolean nativeCrafting;
        private final Map<AEKey, Boolean> valid;
        private final Map<AEKey, AEKey> remainders;
        private final Map<AEKey, RuntimeException> failures = new HashMap<>();

        LiveInput(IPatternDetails pattern, IPatternDetails.IInput source,
                appeng.api.networking.crafting.ICraftingService service, InputMetadata metadata, BigInteger scale) {
            this.pattern = pattern;
            this.source = source;
            this.service = service;
            this.metadata = metadata;
            nativeCrafting = pattern.getClass() == appeng.crafting.pattern.AECraftingPattern.class
                    && source.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern$Input");
            valid = new HashMap<>();
            remainders = new HashMap<>();
            templates = metadata.templates();
            multiplier = metadata.multiplier();
            this.scale = scale;
        }

        private void prepare() {
            checkpoint.run();
            if (prepared) return;
            var primary = templates[0];
            boolean nativeProcessing = pattern instanceof AEProcessingPattern
                    && source.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input");
            stock.amount(primary.what());
            emitted = canEmit(service, primary.what());
            boolean nativeExact = nativeCrafting && templates.length == 1 && metadata.nativeExact();
            if (nativeProcessing && templates.length == 1) {
                exact = primary.what();
                valid.put(primary.what(), true);
                remainders.put(primary.what(), null);
            } else {
                observe(primary.what());
                exact = nativeExact && accepted(primary.what())
                        && remainders.containsKey(primary.what()) && remainders.get(primary.what()) == null
                        ? primary.what() : null;
            }
            AEKey selected = primary.what();
            // #218: the exact native processing input cannot select another fuzzy key.
            if (service != null && !emitted && exact == null
                    && choicesFor(service, primary.what()).isEmpty()) {
                for (var template : templates) {
                    checkpoint.run();
                    if (template.amount() != primary.amount()) continue;
                    var fuzzy = access.read(ReadKind.FUZZY, () -> service.getFuzzyCraftable(template.what(),
                            candidate -> { observe(candidate); return accepted(candidate); }));
                    if (fuzzy != null) { selected = fuzzy; break; }
                }
            }
            crafted = selected;
            stock.amount(crafted);
            prepared = true;
        }

        public GenericStack[] getPossibleInputs() { return templates; }
        public long getMultiplier() { return multiplier; }
        public BigInteger exactMultiplier() { return BigInteger.valueOf(multiplier).multiply(scale); }
        public AEKey craftedKey() { prepare(); return crafted; }
        public boolean emittable() { prepare(); return emitted; }
        public AEKey exactKey() { prepare(); return exact; }
        public boolean mayReturnItems() {
            prepare();
            if (exact != null) return false;
            if (!catalogPrepared || remainders.values().stream().anyMatch(Objects::nonNull)) return true;
            for (var template : templates)
                if (openCandidateFamilies.contains(template.what().getPrimaryKey())) return true;
            return false;
        }
        public boolean mayConsumeOutput(AEKey key) {
            prepare();
            if (exact != null) return exact.equals(key);
            if (rejectsUnmatchedKeyLocally(key)) return false;
            for (var template : templates) {
                if (!template.what().getPrimaryKey().equals(key.getPrimaryKey())) continue;
                return !valid.containsKey(key) || Boolean.TRUE.equals(valid.get(key));
            }
            return false;
        }
        public boolean isCandidatePrepared(AEKey key) {
            prepare();
            return exact != null || rejectsUnmatchedKeyLocally(key) || valid.containsKey(key) || failures.containsKey(key)
                    || observeRule(key);
        }
        public void prepareCandidates(Collection<AEKey> keys) {
            prepare();
            var pending = new java.util.LinkedHashSet<AEKey>();
            for (var key : keys) if (!isCandidatePrepared(key))
                pending.add(key);
            if (pending.isEmpty()) return;
            // The VM supplies its bounded, actually stocked candidate window, not all possible NBT.
            access.read(ReadKind.INPUT_VALIDITY, () -> { for (var key : pending) observe(key); return null; });
        }
        public boolean isValid(AEKey key, Level ignored) {
            prepare();
            stock.amount(key);
            checkpoint.run();
            if (exact != null) {
                return exact.equals(key);
            }
            observe(key);
            return accepted(key);
        }

        private boolean accepted(AEKey key) {
            var failure = failures.get(key);
            if (failure != null) throw failure;
            return Boolean.TRUE.equals(valid.get(key));
        }

        private boolean rejectsUnmatchedKeyLocally(AEKey key) {
            // #218: specialize AE2's fixed predicate before entering the live-read dispatcher.
            return nativeCrafting && metadata.nativeExact() && !templates[0].what().equals(key);
        }

        private void observe(AEKey key) {
            checkpoint.run();
            if (valid.containsKey(key) || failures.containsKey(key)) return;
            if (rejectsUnmatchedKeyLocally(key)) {
                valid.put(key, false);
                return;
            }
            if (observeRule(key)) return;
            if (catalogPrepared) {
                // #218: share one boundary for an encountered key, never enumerate its future returns.
                access.read(ReadKind.INPUT_VALIDITY, () -> {
                    observeLive(key);
                    for (var peer : inputsByFamily.getOrDefault(key.getPrimaryKey(), List.of())) {
                        checkpoint.run();
                        if (access.readWindowExpired()) break;
                        if (!peer.isCandidatePrepared(key)) peer.observeLive(key);
                    }
                    return null;
                });
                return;
            }
            observeLive(key);
        }

        private boolean observeRule(AEKey key) {
            var rule = metadata.rule();
            if (rule == null) return false;
            var decision = rule.evaluate(key);
            if (decision == null) return false;
            valid.put(key, decision.accepted());
            remainders.put(key, decision.remainder());
            return true;
        }

        private void observeLive(AEKey key) {
            try {
                var kind = nativeCrafting && templates[0].what().equals(key) ? ReadKind.INPUT_REMAINDER : ReadKind.INPUT_VALIDITY;
                var decision = access.read(kind, () -> {
                    boolean accepted = nativeCrafting && templates[0].what().equals(key) || source.isValid(key, level);
                    return new CandidateDecision(accepted,
                            accepted || templates[0].what().equals(key) ? readRemainder(key) : null);
                });
                valid.put(key, decision.accepted());
                remainders.put(key, decision.remainder());
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException failure) {
                failures.put(key, failure);
            }
        }
        public AEKey getRemainingKey(AEKey key) {
            prepare();
            checkpoint.run();
            // #218: exactKey already promises no return; repeated tree visits need no live callback.
            if (exact != null && exact.equals(key)) {
                return null;
            }
            if (nativeCrafting && !(key instanceof AEItemKey)) return null;
            // One order uses one pattern snapshot. Key includes item NBT, so returned tool states stay distinct.
            if (failures.containsKey(key)) throw failures.get(key);
            if (!remainders.containsKey(key) && !observeRule(key))
                remainders.put(key, access.read(ReadKind.INPUT_REMAINDER, () -> readRemainder(key)));
            return remainders.get(key);
        }

        private AEKey readRemainder(AEKey key) {
            var remainder = source.getRemainingKey(key);
            timing.remainderRead(source.getClass().getName(), key.getId().toString(), remainder == null);
            return remainder;
        }
    }

    private record CandidateDecision(boolean accepted, AEKey remainder) { }

    private final class LivePattern implements PatternCompiler.DetachedPattern {
        private final IPatternDetails source;
        private final ICraftingService service;
        private final PatternSkeleton skeleton;
        private IPatternDetails.IInput[] inputs;

        LivePattern(IPatternDetails source, ICraftingService service, PatternSkeleton skeleton) {
            this.source = source;
            this.service = service;
            this.skeleton = skeleton;
        }

        public AEItemKey getDefinition() { return skeleton.definition(); }
        public GenericStack[] getOutputs() { return skeleton.outputs(); }
        public BigInteger outputScale() { return skeleton.scale(); }
        public int inputCount() { return skeleton.inputs().length; }
        public IPatternDetails.IInput[] getInputs() {
            var failure = preparationFailures.get(this);
            if (failure != null) throw failure;
            if (inputs == null) {
                var metadata = access.read(ReadKind.INPUT_METADATA, () -> {
                    var captured = new InputMetadata[skeleton.inputs().length];
                    var semantics = skeleton.semantics();
                    boolean nativeCrafting = semantics.getClass() == appeng.crafting.pattern.AECraftingPattern.class;
                    boolean nativeExact = nativeCrafting
                            && !((appeng.crafting.pattern.AECraftingPattern) semantics).canSubstitute();
                    var rules = nativeCrafting ? NativeCraftingRule.capture(
                            (appeng.crafting.pattern.AECraftingPattern) semantics, level) : null;
                    for (int i = 0; i < captured.length; i++) {
                        var input = skeleton.inputs()[i];
                        captured[i] = new InputMetadata(input.getPossibleInputs().clone(), input.getMultiplier(),
                                nativeExact, rules == null ? null : rules[i]);
                    }
                    return captured;
                });
                var wrapped = new IPatternDetails.IInput[metadata.length];
                for (int i = 0; i < wrapped.length; i++)
                    wrapped[i] = new LiveInput(skeleton.semantics(), skeleton.inputs()[i], service, metadata[i], skeleton.scale());
                inputs = wrapped;
            }
            return inputs;
        }
    }

    Map<String, IPatternDetails> bindings() { return Map.copyOf(bindings); }

    boolean widePendingOutputs(Map<String, BigInteger> executions) {
        Map<AEKey, BigInteger> totals = new LinkedHashMap<>();
        executions.forEach((id, count) -> {
            for (var output : programs.get(bindings.get(id)).getOutputs())
                totals.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount()))
                        .multiply(programs.get(bindings.get(id)).outputScale()), BigInteger::add);
        });
        return totals.values().stream().anyMatch(value -> value.bitLength() > 63);
    }
}
