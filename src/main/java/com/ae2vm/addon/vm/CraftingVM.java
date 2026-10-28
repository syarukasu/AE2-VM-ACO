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
 * #218: shared exact demands are compiled and combined before applying quantities.
 * #208: state-dependent effects retain ordered semantics, never an unconditionally
 * multiplied one-craft stock/branch result. Prepared catalogs may explicitly use
 * #236 order-wide recipe allocation. No ACO planner is called.
 */
public final class CraftingVM {
    private Function<AEKey, IPatternDetails> patternResolver;
    private Function<AEKey, List<IPatternDetails>> allPatternsResolver;
    private Predicate<AEKey> emitterResolver = key -> false;
    private LongConsumer progress = ignored -> { };
    private Runnable checkpoint = () -> { };
    private volatile boolean executing;
    private long instructions, work;
    private long stateCopies, branchTrials, branchEntries;
    private long aggregatedBlocks;
    private BigInteger skipped = BigInteger.ZERO;
    private VmStockGuard stockGuard;
    private VmRouteCache previousRoutes, routes;
    private java.util.Map<IPatternDetails, String> routePatternIds;
    private long reusedRoutes;
    private boolean orderAllocation;
    private volatile VmInstructionExecution activeInterpreter;
    private String planningStatus = "not-started";

    public CraftingVM(Object networkKey, Function<AEKey, IPatternDetails> patternResolver) {
        this.patternResolver = Objects.requireNonNull(patternResolver);
    }
    public void setPatternResolver(Function<AEKey, IPatternDetails> resolver) { patternResolver = Objects.requireNonNull(resolver); }
    public void setAllPatternsResolver(Function<AEKey, List<IPatternDetails>> resolver) { allPatternsResolver = Objects.requireNonNull(resolver); }
    public void setEmitterResolver(Predicate<AEKey> resolver) { emitterResolver = Objects.requireNonNull(resolver); }
    public void setProgressListener(LongConsumer listener) { progress = Objects.requireNonNull(listener); }
    public void setCheckpoint(Runnable control) { checkpoint = Objects.requireNonNull(control); }
    /** #236: enable only with an already prepared catalog; lazy/live resolvers retain ordered access. */
    public void setOrderAllocation(boolean enabled) { orderAllocation = enabled; }
    public boolean isExecuting() { return executing; }
    public long instructions() { var active = activeInterpreter; return active == null ? instructions : active.instructions(); }
    public long work() { var active = activeInterpreter; return active == null ? work : active.work(); }
    public long stateCopies() { var active = activeInterpreter; return active == null ? stateCopies : active.stateCopies(); }
    public long branchTrials() { var active = activeInterpreter; return active == null ? branchTrials : active.branchTrials(); }
    public long branchEntries() { var active = activeInterpreter; return active == null ? branchEntries : active.branchEntries(); }
    public long aggregatedBlocks() { var active = activeInterpreter; return active == null ? aggregatedBlocks : active.aggregatedBlocks(); }
    /** #236: sampled on the calculation worker, without per-operation trace retention. */
    public String planningStatus() { var active = activeInterpreter; return active == null ? planningStatus : active.planningStatus(); }
    public BigInteger skippedIterations() { return skipped; }
    public VmStockGuard stockGuard() { return stockGuard; }
    public void setValidatedRoutes(VmRouteCache previous, java.util.Map<IPatternDetails, String> ids) {
        previousRoutes = previous;
        routePatternIds = java.util.Collections.unmodifiableMap(new java.util.IdentityHashMap<>(ids));
    }
    public VmRouteCache routes() { return routes; }
    public long reusedRoutes() { return reusedRoutes; }

    public VmCraftingPlan execute(CraftingBytecode request, VmSimulationState simulation) {
        return execute(request, simulation, false);
    }

    public synchronized VmCraftingPlan execute(CraftingBytecode request, VmSimulationState simulation, boolean craftLess) {
        stockGuard = null;
        routes = null;
        reusedRoutes = 0;
        if (request.getExactOutputAmountPerCraft().signum() <= 0)
            throw new IllegalArgumentException("positive VM order required");
        Function<AEKey, List<IPatternDetails>> choices = allPatternsResolver != null ? allPatternsResolver : key -> {
            var pattern = patternResolver.apply(key);
            return pattern == null ? List.of() : List.of(pattern);
        };
        var interpreter = new VmInstructionExecution(request, simulation, choices, emitterResolver, progress,
                true, true, checkpoint);
        if (orderAllocation) interpreter.allocateWholeOrder();
        if (routePatternIds != null) interpreter.setValidatedRoutes(previousRoutes, routePatternIds);
        activeInterpreter = interpreter;
        executing = true;
        try {
            var result = interpreter.execute(request.getExactOutputAmountPerCraft(), craftLess);
            stockGuard = interpreter.stockGuard();
            routes = interpreter.routes();
            return result;
        } finally {
            instructions = interpreter.instructions();
            work = interpreter.work();
            stateCopies = interpreter.stateCopies();
            branchTrials = interpreter.branchTrials();
            branchEntries = interpreter.branchEntries();
            aggregatedBlocks = interpreter.aggregatedBlocks();
            skipped = interpreter.skippedIterations();
            reusedRoutes = interpreter.reusedRoutes();
            planningStatus = interpreter.planningStatus();
            activeInterpreter = null;
            executing = false;
        }
    }
}
