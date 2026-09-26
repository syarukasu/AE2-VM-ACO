package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.addon.vm.VmSimulationState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongConsumer;
import net.minecraft.world.level.Level;

/** Per-order VM state. Only live callbacks enter AE2's exclusive interval. */
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

    NativeVmPlanningContext(IGrid grid, Level level, ICraftingSimulationRequester requester,
            VmAccounting.Stock stock, NativeVmAccess access, NativeVmTiming timing) {
        this.grid = grid;
        this.level = level;
        this.requester = requester;
        this.stock = stock;
        this.access = access;
        this.checkpoint = access::checkpoint;
        this.timing = timing;
    }

    VmQuantities plan(AEKey root, BigInteger requested, boolean craftLess, LongConsumer progress) {
        try (var scope = PatternCompiler.openScope()) {
            var vm = new CraftingVM(this, key -> {
                var choices = candidates(key);
                return choices.isEmpty() ? null : choices.get(0);
            });
            vm.setAllPatternsResolver(this::candidates);
            vm.setEmitterResolver(key -> access.read(() -> grid.getCraftingService().canEmitFor(key)));
            vm.setProgressListener(progress);
            vm.setCheckpoint(checkpoint);
            com.ae2vm.addon.vm.VmCraftingPlan result;
            try (var span = timing.phase(NativeVmTiming.Phase.VM)) {
                try {
                    result = vm.execute(PatternCompiler.compileRequest(root, requested),
                            new VmSimulationState(stock.amounts()), craftLess);
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

    private List<IPatternDetails> candidates(AEKey key) {
        checkpoint.run();
        var service = access.read(() -> {
            var node = requester.getGridNode();
            return node == null ? null : node.getGrid().getCraftingService();
        });
        if (service == null) return List.of();
        // AE2 fixes each node's ordered producer list when that node is first explored.
        var choices = access.read(() -> List.copyOf(service.getCraftingFor(key)));
        var result = new ArrayList<IPatternDetails>(choices.size());
        for (var source : choices) {
            checkpoint.run();
            var program = programs.get(source);
            if (program == null) {
                var inputs = access.read(() -> source.getInputs().clone());
                var slots = new IPatternDetails.IInput[inputs.length];
                for (int i = 0; i < inputs.length; i++) {
                    checkpoint.run();
                    var input = inputs[i];
                    slots[i] = new LiveInput(source, input, service);
                }
                program = access.read(() -> new LivePattern(source.getDefinition(), slots, source.getOutputs().clone()));
                String id = "vm-" + bindings.size();
                programs.put(source, program);
                ids.put(program, id);
                bindings.put(id, source);
                timing.patternType(source.getClass().getName());
            }
            result.add(program);
        }
        return result;
    }

    private final class LiveInput implements PatternCompiler.DetachedInput {
        private final IPatternDetails.IInput source;
        private final GenericStack[] templates;
        private final long multiplier;
        private final AEKey crafted;
        private final boolean emitted;
        private final AEKey exact;

        LiveInput(IPatternDetails pattern, IPatternDetails.IInput source,
                appeng.api.networking.crafting.ICraftingService service) {
            this.source = source;
            templates = access.read(() -> source.getPossibleInputs().clone());
            multiplier = access.read(source::getMultiplier);
            var primary = templates[0];
            stock.amount(primary.what());
            emitted = access.read(() -> service.canEmitFor(primary.what()));
            AEKey selected = primary.what();
            if (!emitted && access.read(() -> service.getCraftingFor(primary.what()).isEmpty())) {
                for (var template : templates) {
                    checkpoint.run();
                    if (template.amount() != primary.amount()) continue;
                    var fuzzy = access.read(() -> service.getFuzzyCraftable(template.what(),
                            candidate -> source.isValid(candidate, level)));
                    if (fuzzy != null) { selected = fuzzy; break; }
                }
            }
            crafted = selected;
            stock.amount(crafted);
            boolean nativeExact = access.read(() -> templates.length == 1
                    && (source.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input")
                    || (pattern.getClass() == appeng.crafting.pattern.AECraftingPattern.class
                    && !((appeng.crafting.pattern.AECraftingPattern) pattern).canSubstitute()
                    && source.getClass().getName().equals("appeng.crafting.pattern.AECraftingPattern$Input"))));
            exact = nativeExact && access.read(() -> source.isValid(primary.what(), level)
                    && source.getRemainingKey(primary.what()) == null) ? primary.what() : null;
        }

        public GenericStack[] getPossibleInputs() { return templates; }
        public long getMultiplier() { return multiplier; }
        public AEKey craftedKey() { return crafted; }
        public boolean emittable() { return emitted; }
        public AEKey exactKey() { return exact; }
        public boolean isCandidatePrepared(AEKey key) { return true; }
        public boolean isValid(AEKey key, Level ignored) {
            stock.amount(key);
            return access.read(() -> source.isValid(key, level));
        }
        public AEKey getRemainingKey(AEKey key) { return access.read(() -> source.getRemainingKey(key)); }
    }

    private record LivePattern(AEItemKey definition, IPatternDetails.IInput[] inputs, GenericStack[] outputs)
            implements PatternCompiler.DetachedPattern {
        public AEItemKey getDefinition() { return definition; }
        public IPatternDetails.IInput[] getInputs() { return inputs; }
        public GenericStack[] getOutputs() { return outputs; }
    }

    Map<String, IPatternDetails> bindings() { return Map.copyOf(bindings); }

    boolean widePendingOutputs(Map<String, BigInteger> executions) {
        Map<AEKey, BigInteger> totals = new LinkedHashMap<>();
        executions.forEach((id, count) -> {
            for (var output : programs.get(bindings.get(id)).outputs())
                totals.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount())), BigInteger::add);
        });
        return totals.values().stream().anyMatch(value -> value.bitLength() > 63);
    }
}
