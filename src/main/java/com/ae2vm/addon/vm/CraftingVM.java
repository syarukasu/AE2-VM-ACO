package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * BigInteger fork of AE2-VM's compiled-pattern stack machine.
 * #208: state-dependent recipe effects use guarded, ordered block replay, never
 * an unconditionally multiplied one-craft stock/branch result. No ACO planner is called.
 */
public final class CraftingVM {
    private Function<AEKey, IPatternDetails> patternResolver;
    private Function<AEKey, List<IPatternDetails>> allPatternsResolver;
    private Predicate<AEKey> emitterResolver = key -> false;
    private LongConsumer progress = ignored -> { };
    private volatile boolean executing;
    private long instructions, work;
    private BigInteger skipped = BigInteger.ZERO;

    public CraftingVM(Object networkKey, Function<AEKey, IPatternDetails> patternResolver) {
        this.patternResolver = Objects.requireNonNull(patternResolver);
    }
    public void setPatternResolver(Function<AEKey, IPatternDetails> resolver) { patternResolver = Objects.requireNonNull(resolver); }
    public void setAllPatternsResolver(Function<AEKey, List<IPatternDetails>> resolver) { allPatternsResolver = Objects.requireNonNull(resolver); }
    public void setEmitterResolver(Predicate<AEKey> resolver) { emitterResolver = Objects.requireNonNull(resolver); }
    public void setProgressListener(LongConsumer listener) { progress = Objects.requireNonNull(listener); }
    public boolean isExecuting() { return executing; }
    public long instructions() { return instructions; }
    public long work() { return work; }
    public BigInteger skippedIterations() { return skipped; }

    public VmCraftingPlan execute(CraftingBytecode request, VmSimulationState simulation) {
        return execute(request, simulation, false);
    }

    public synchronized VmCraftingPlan execute(CraftingBytecode request, VmSimulationState simulation, boolean craftLess) {
        if (request.getExactOutputAmountPerCraft().signum() <= 0)
            throw new IllegalArgumentException("positive VM order required");
        Function<AEKey, List<IPatternDetails>> choices = allPatternsResolver != null ? allPatternsResolver : key -> {
            var pattern = patternResolver.apply(key);
            return pattern == null ? List.of() : List.of(pattern);
        };
        var interpreter = new VmInstructionExecution(request, simulation, choices, emitterResolver, progress);
        executing = true;
        try {
            return interpreter.execute(request.getExactOutputAmountPerCraft(), craftLess);
        } finally {
            instructions = interpreter.instructions();
            work = interpreter.work();
            skipped = interpreter.skippedIterations();
            executing = false;
        }
    }
}
