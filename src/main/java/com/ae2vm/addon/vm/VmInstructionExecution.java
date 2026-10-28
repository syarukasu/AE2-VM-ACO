package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * #208: ordered execution of compiled VM blocks. A captured one-craft effect is
 * not a recipe: stock alternatives, returns and failed branch trials are stateful.
 * This executor keeps those operations in bytecode order, inside the VM fork.
 * #236: prepared exact catalogs can opt into order-wide quotas; unsupported
 * callbacks retain ordered execution. Both policies share the same stock ledgers.
 */
final class VmInstructionExecution {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static BigInteger scaled(BigInteger amount, BigInteger factor) {
        return factor.equals(ONE) ? amount : amount.equals(ONE) ? factor : amount.multiply(factor);
    }
    private static BigInteger divided(BigInteger amount, BigInteger unit) {
        if (unit.equals(ONE)) return amount;
        if (amount.signum() >= 0 && unit.signum() > 0) {
            int shift = unit.getLowestSetBit();
            if (unit.bitLength() == shift + 1) return amount.shiftRight(shift);
        }
        return amount.divide(unit);
    }
    static boolean integralMultiple(BigInteger amount, BigInteger unit) {
        if (unit.signum() <= 0) throw new IllegalArgumentException("Nonpositive period");
        if (unit.equals(ONE) || amount.signum() == 0) return true;
        int shift = unit.getLowestSetBit();
        return unit.bitLength() == shift + 1 ? amount.getLowestSetBit() >= shift
                : amount.remainder(unit).signum() == 0;
    }
    private static final Object EMPTY_STOCK_KEYS = new Object();
    private final Function<AEKey, List<IPatternDetails>> resolver;
    private final Predicate<AEKey> emitters;
    private final Map<AEKey, BigInteger> initial;
    private final boolean completeStock;
    private final KeyCounter initialKeys = new KeyCounter();
    private final KeyCounter emptyKeys = new KeyCounter();
    private long emptyFamilyLookups;
    private final Node root;
    private final Map<AEKey, List<IPatternDetails>> knownProducers = new HashMap<>();
    private final VmDemandProgram.Compiler demandCompiler;
    private final VmDemandProgram.Compiler quotaCompiler;
    private final VmDemandProgram.Compiler simulationCompiler;
    private final Map<AEKey, Optional<VmLinearDemand>> linearPrograms = new HashMap<>();
    private final Map<VmLinearDemand, Optional<BitSet>> linearFamilies = new IdentityHashMap<>();
    private record LinearRequest(VmLinearDemand program, BigInteger amount) { }
    private final Map<LinearRequest, VmLinearDemand.Effect> linearEffects = new LinkedHashMap<>(512, 0.75f, true);
    private int linearEffectWeight;
    private long linearEffectHits;
    private final Map<VmLinearDemand, VmZeroSupply.Node> linearCapacities = new IdentityHashMap<>();
    private boolean sharedLinearCapacity = true;
    VmInstructionExecution sharedLinearCapacity(boolean enabled) { sharedLinearCapacity = enabled; return this; }
    private boolean roundedLinearDemands = true;
    private long linearBlocks, linearSteps;
    VmInstructionExecution roundedLinearDemands(boolean enabled) { roundedLinearDemands = enabled; return this; }
    private boolean sharedSimulationDemands = true;
    VmInstructionExecution sharedSimulationDemands(boolean enabled) { sharedSimulationDemands = enabled; return this; }
    private final VmBatchProgram.Cache batchCompiler = new VmBatchProgram.Cache();
    private final VmFormulaProgram.Compiler formulaCompiler;
    private final VmInputFacts inputFacts = new VmInputFacts();
    private boolean formulaCompilation = true;
    private boolean finiteFormulaAdmission = true;
    VmInstructionExecution finiteFormulaAdmission(boolean enabled) { finiteFormulaAdmission = enabled; return this; }
    private final Map<IPatternDetails, Map<Integer, PatternCompiler.FiniteUse>> finiteToolShapes = new IdentityHashMap<>();
    private long finiteShapeHits, finiteFormulaSkips;
    private record ToolDomain(List<AEKey> templates, BitSet families, Map<BitSet, List<AEKey>> positive) { }
    private final Map<IPatternDetails.IInput, ToolDomain> toolDomains = new IdentityHashMap<>();
    private long emptyToolDomains;
    private record InputDomain(GenericStack[] templates, BitSet families,
            Map<BitSet, GenericStack[]> positive) { }
    private final Map<IPatternDetails.IInput, InputDomain> inputDomains = new IdentityHashMap<>();
    private boolean projectedInputs = true;
    VmInstructionExecution projectedInputs(boolean enabled) { projectedInputs = enabled; return this; }
    private boolean fusedTransfers = true;
    VmInstructionExecution fusedTransfers(boolean enabled) { fusedTransfers = enabled; return this; }
    private long formulaAttempts, formulaNanos;
    VmInstructionExecution formulaCompilation(boolean enabled) { formulaCompilation = enabled; return this; }
    private final VmBranchProfile branchProfile = Boolean.getBoolean("ae2vm.branches.profile") ? new VmBranchProfile() : null;
    private final Map<IPatternDetails, ProcessMetadata> processMetadata = new IdentityHashMap<>();
    private boolean sharedDefinitions = true;
    private long createdNodes, outputSummaries, pathStrings;
    VmInstructionExecution sharedDefinitions(boolean enabled) { sharedDefinitions = enabled; return this; }
    long outputSummaries() { return outputSummaries; }
    private boolean supplyReachability = true;
    private VmSupplyReachability supplyGraph;
    private VmSupplyUpperBound supplyLimits;
    private boolean quantityCeilings = true;
    private long quantityBoundReductions;
    VmInstructionExecution quantityCeilings(boolean enabled) { quantityCeilings = enabled; return this; }
    private BitSet initialSupplySeeds;
    private final Map<VmSupplyReachability.Query, Boolean> supplyClosures = new LinkedHashMap<>(512, 0.75f, true);
    private final Map<Integer, List<BitSet>> supplyCuts = new HashMap<>();
    private int supplyCutCount, supplyCutWords;
    private long seededSupplyHits;
    private long supplyCutHits;
    private long supplyClosuresBuilt, unreachableSupplies, earlyQuotaBounds, finiteQuotaRetries;
    VmInstructionExecution supplyReachability(boolean enabled) { supplyReachability = enabled; return this; }
    long unreachableSupplies() { return unreachableSupplies; }
    private final Map<VmDemandProgram, CapacityShape> capacityShapes = new IdentityHashMap<>();
    private final Map<KeyCounter, Map<IndexChange, KeyCounter>> indexChanges = new IdentityHashMap<>();
    private int retainedIndexChanges, retainedIndexMembers;
    private record IndexChange(AEKey key, boolean positive) { }
    private boolean multiple;
    private boolean simulate;
    private boolean orderAllocation;
    private boolean preferredProjection;
    private boolean projectedQuotas = true;
    private int projectionMinimumBits = 16;
    private long quotaProjections, projectedQuotaCommits;
    private String orderProjection = "none";
    String planningStatus() {
        return "allocation=" + (orderAllocation ? "whole-order" : "ordered") + ",projection=" + orderProjection
                + ",linear=" + linearBlocks + ",quota=" + jointQuotaReductions + ",finite=" + finiteShapeHits;
    }
    private static String diagnosticKey(AEKey key) {
        try { return String.valueOf(key.getId()); }
        catch (RuntimeException | LinkageError unavailable) { return "unknown"; }
    }
    private java.util.function.Consumer<Map<AEKey, BigInteger>> projectedShortageObserver;
    VmInstructionExecution observeProjectedShortages(java.util.function.Consumer<Map<AEKey, BigInteger>> observer) {
        projectedShortageObserver = observer; return this;
    }
    VmInstructionExecution projectedQuotas(boolean enabled) { projectedQuotas = enabled; return this; }
    VmInstructionExecution projectionMinimumBits(int bits) { projectionMinimumBits = bits; return this; }
    private boolean finiteAllocation;
    private boolean allocationChoices;
    private final Map<AEKey, List<IPatternDetails>> secondaryProducers = new HashMap<>();
    private final Set<IPatternDetails> allocationPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean allocationReturns;
    private boolean scopedSupplyBounds = true;
    // #236: the audited probe cache has no stable whole-plan win; retain opt-in differential coverage only.
    private boolean reuseQuotaProbes;
    private long isolatedQuotaProbes, reusedQuotaProbes;
    private final LinkedHashMap<ProbeKey, ProbeResult> probeResults = new LinkedHashMap<>(16, 0.75f, true);
    private int probeResultKeys;
    private ProbeObservation activeProbe;
    private boolean shortagePlanning = true;
    private final VmZeroSupply zeroSupply;
    private long provenZeroRequests, planPasses;
    private long provenQuotaBounds;
    private boolean constantSeedBounds = true;
    private long constantSeedFailures;
    private boolean directPatternExecution = true;
    private long directPatternBlocks;
    VmInstructionExecution directPatternExecution(boolean enabled) { directPatternExecution = enabled; return this; }
    private boolean independentInputBudgets = true;
    private long independentBudgets;
    private boolean unchangedPrefixBudgets = true;
    private long unchangedBudgets;
    private boolean demandDrivenQuotaHints = true;
    private long unusedQuotaHints;
    private boolean repeatedQuotaHints = true;
    private long repeatedQuotaReductions;
    private boolean jointQuotaBounds = true;
    private long jointQuotaReductions;
    private final Map<VmDemandProgram, LinkedHashMap<Object, JointStockBounds>> jointStockBounds = new IdentityHashMap<>();
    private record JointStockBounds(BigInteger upper, BigInteger lower) { }
    VmInstructionExecution jointQuotaBounds(boolean enabled) { jointQuotaBounds = enabled; return this; }
    long jointQuotaReductions() { return jointQuotaReductions; }
    VmInstructionExecution repeatedQuotaHints(boolean enabled) { repeatedQuotaHints = enabled; return this; }
    VmInstructionExecution demandDrivenQuotaHints(boolean enabled) { demandDrivenQuotaHints = enabled; return this; }
    long unusedQuotaHints() { return unusedQuotaHints; }
    VmInstructionExecution unchangedPrefixBudgets(boolean enabled) { unchangedPrefixBudgets = enabled; return this; }
    long unchangedBudgets() { return unchangedBudgets; }
    private final Map<IPatternDetails.IInput, Optional<Set<Family>>> slotDependencies = new IdentityHashMap<>();
    private boolean traceShortageProofs = true;
    VmInstructionExecution traceShortageProofs(boolean enabled) { traceShortageProofs = enabled; return this; }
    private final Map<AEKey, Optional<Set<Family>>> inputDependencies = new HashMap<>();
    private int inputDependencyWeight;
    VmInstructionExecution independentInputBudgets(boolean enabled) { independentInputBudgets = enabled; return this; }
    VmInstructionExecution constantSeedBounds(boolean enabled) { constantSeedBounds = enabled; return this; }
    long constantSeedFailures() { return constantSeedFailures; }

    VmInstructionExecution shortagePlanning(boolean enabled) { shortagePlanning = enabled; return this; }
    long provenZeroRequests() { return provenZeroRequests; }
    long planPasses() { return planPasses; }
    long provenQuotaBounds() { return provenQuotaBounds; }
    String shortageDiagnostics() { return zeroSupply.diagnostics() + ",quotaBounds=" + provenQuotaBounds
            + ",constantSeedFailures=" + constantSeedFailures + ",directPatterns=" + directPatternBlocks
            + ",independentBudgets=" + independentBudgets + ",unchangedBudgets=" + unchangedBudgets
            + ",unusedQuotaHints=" + unusedQuotaHints + ",quotaReductions=" + repeatedQuotaReductions
            + ",jointQuotaReductions=" + jointQuotaReductions + ",createdNodes=" + createdNodes
            + ",outputSummaries=" + outputSummaries + ",pathStrings=" + pathStrings
            + ",supplyClosures=" + supplyClosuresBuilt + ",unreachableSupplies=" + unreachableSupplies
            + ",earlyQuotaBounds=" + earlyQuotaBounds + ",finiteQuotaRetries=" + finiteQuotaRetries
            + ",formulaAttempts=" + formulaAttempts + ",formulaBlocks=" + formulaBlocks
            + ",formulaMs=" + formulaNanos / 1_000_000 + ",finiteShapeHits=" + finiteShapeHits
            + ",finiteFormulaSkips=" + finiteFormulaSkips + ",supplyCutHits=" + supplyCutHits
            + ",quantityBoundReductions=" + quantityBoundReductions + ",simulatedRequests=" + simulatedRequests
            + ",linearBlocks=" + linearBlocks + ",linearSteps=" + linearSteps + ",emptyFamilyLookups=" + emptyFamilyLookups
            + ",emptyToolDomains=" + emptyToolDomains + ",linearEffectHits=" + linearEffectHits
            + ",supplyCuts=" + supplyCutCount + ",supplyCutWords=" + supplyCutWords
            + ",seededSupplyHits=" + seededSupplyHits + ",quotaProjections=" + quotaProjections
            + ",projectedQuotaCommits=" + projectedQuotaCommits + ",orderProjection=" + orderProjection; }

    VmInstructionExecution reuseQuotaProbes(boolean enabled) { reuseQuotaProbes = enabled; return this; }
    long isolatedQuotaProbes() { return isolatedQuotaProbes; }
    long reusedQuotaProbes() { return reusedQuotaProbes; }
    private final Map<IPatternDetails, Boolean> returnFreeRegions = new IdentityHashMap<>();
    private final Map<IPatternDetails, Map<AEKey, BigInteger>> feedbackInputs = new IdentityHashMap<>();

    VmInstructionExecution allocateWholeOrder() {
        return allocateWholeOrder(true);
    }

    VmInstructionExecution allocateWholeOrder(boolean scopedSupplyBounds) {
        this.scopedSupplyBounds = scopedSupplyBounds;
        orderAllocation = true;
        finiteAllocation = true;
        return this;
    }
    private long instructions;
    private long work;
    private long simulatedRequests;
    private long stateCopies;
    private long branchTrials, branchEntries;
    private long aggregatedBlocks;
    private long compiledReentries;
    private long formulaBlocks;
    private long missingRegionBlocks;
    private long certifiedFailures;
    private final boolean sparseBranches, optimizedGuards, coalescedTraceReads;
    private long stockKeyScans, minimumSnapshots, replays;
    private BigInteger skipped = ZERO;
    private final java.util.function.LongConsumer progress;
    private final Runnable control;
    private final List<Trace> traces = new ArrayList<>();
    private final VmStockGuard.Builder orderGuard;
    private VmStockGuard stockGuard;
    private VmRouteCache previousRoutes, routes;
    private Map<IPatternDetails, String> routeIds;
    private Map<String, IPatternDetails> routePatterns;
    private final List<RouteCapture> routeCaptures = new ArrayList<>();
    private long reusedRoutes;
    private long stateReads, traceReads;
    private long lowWaterWrites;
    private long fixedTraceReads;
    private long traceMetadataWrites;

    long stateReads() { return stateReads; }
    long traceReads() { return traceReads; }
    long lowWaterWrites() { return lowWaterWrites; }
    long fixedTraceReads() { return fixedTraceReads; }
    long traceMetadataWrites() { return traceMetadataWrites; }

    void setValidatedRoutes(VmRouteCache previous, Map<IPatternDetails, String> ids) {
        previousRoutes = previous;
        routes = new VmRouteCache();
        routeIds = ids;
        routePatterns = new HashMap<>();
        ids.forEach((pattern, id) -> routePatterns.put(id, pattern));
    }
    VmRouteCache routes() { return routes; }
    long reusedRoutes() { return reusedRoutes; }

    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress) {
        this(request, inventory, resolver, emitters, progress, true, true);
    }
    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress, boolean sparseBranches, boolean optimizedGuards) {
        this(request, inventory, resolver, emitters, progress, sparseBranches, optimizedGuards, () -> { });
    }
    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress, boolean sparseBranches, boolean optimizedGuards, Runnable control) {
        this(request, inventory, resolver, emitters, progress, sparseBranches, optimizedGuards, control, true);
    }
    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress, boolean sparseBranches, boolean optimizedGuards, Runnable control,
            boolean coalescedTraceReads) {
        this.control = control;
        zeroSupply = new VmZeroSupply(key -> knownProducers.computeIfAbsent(key, resolver), emitters, this::checkpoint);
        this.coalescedTraceReads = coalescedTraceReads;
        demandCompiler = new VmDemandProgram.Compiler(knownProducers, resolver, this::checkpoint);
        quotaCompiler = new VmDemandProgram.Compiler(knownProducers, this::checkpoint).equivalentSources();
        simulationCompiler = new VmDemandProgram.Compiler(knownProducers, this::checkpoint, true);
        formulaCompiler = new VmFormulaProgram.Compiler(key -> knownProducers.computeIfAbsent(key, resolver), this::checkpoint, inputFacts);
        this.sparseBranches = sparseBranches;
        this.optimizedGuards = optimizedGuards;
        this.resolver = resolver;
        this.emitters = emitters;
        this.progress = progress;
        initial = new LinkedHashMap<>(inventory.initialStock());
        completeStock = inventory.complete();
        initial.put(request.getOutput(), ZERO);
        initial.forEach((key, amount) -> {
            if (!optimizedGuards || amount.signum() > 0) initialKeys.add(key, 1);
        });
        orderGuard = new VmStockGuard.Builder(inventory.initialStock(), request.getOutput(), completeStock && optimizedGuards);
        root = new Node(request.getOutput(), ONE, null, null, "r");
    }
    long instructions() { return instructions; }
    long work() { return work; }
    BigInteger capacityNodes() { return root.nodeCount(null); }
    long stateCopies() { return stateCopies; }
    long branchTrials() { return branchTrials; }
    long branchEntries() { return branchEntries; }
    long aggregatedBlocks() { return aggregatedBlocks; }
    long compiledReentries() { return compiledReentries; }
    long formulaBlocks() { return formulaBlocks; }
    long missingRegionBlocks() { return missingRegionBlocks; }
    long certifiedFailures() { return certifiedFailures; }
    long stockKeyScans() { return stockKeyScans; }
    long minimumSnapshots() { return minimumSnapshots; }
    long replays() { return replays; }
    BigInteger skippedIterations() { return skipped; }
    VmStockGuard stockGuard() { return stockGuard; }

    private BigInteger initialAmount(AEKey key) {
        routeRead(key);
        orderGuard.exact(key);
        return initial.getOrDefault(key, ZERO);
    }

    private boolean initialPositive(AEKey key) {
        routeRead(key);
        var available = initial.getOrDefault(key, ZERO);
        orderGuard.positive(key, available);
        return available.signum() > 0;
    }

    private VmCraftingPlan certified(VmCraftingPlan result) {
        stockGuard = orderGuard.finish();
        return result;
    }

    VmCraftingPlan execute(BigInteger amount, boolean craftLess) {
        try { return executePlan(amount, craftLess); }
        finally {
            if (branchProfile != null) {
                try { branchProfile.report(); }
                catch (RuntimeException diagnosticFailure) {
                    System.getLogger("ACO.VmBranchProfile").log(System.Logger.Level.WARNING, "Branch diagnostic report failed", diagnosticFailure);
                }
            }
        }
    }

    private VmCraftingPlan executePlan(BigInteger amount, boolean craftLess) {
        if (orderAllocation) orderAllocation = prepareOrderAllocation();
        orderProjection = !orderAllocation ? "not-attempted:ordered" : craftLess ? "not-attempted:craft-less"
                : !completeStock ? "not-attempted:incomplete-stock" : initialKeys.isEmpty() ? "not-attempted:empty-stock"
                : !optimizedGuards ? "not-attempted:unoptimized" : !projectedQuotas ? "not-attempted:disabled"
                : amount.bitLength() < projectionMinimumBits ? "not-attempted:small-order" : "pending";
        if (orderAllocation && supplyReachability && completeStock && optimizedGuards) {
            supplyGraph = VmSupplyReachability.compile(knownProducers.values(), this::checkpoint);
            if (quantityCeilings && allocationReturns)
                supplyLimits = VmSupplyUpperBound.compile(knownProducers, this::checkpoint);
            if (supplyGraph != null) initialSupplySeeds = supplyGraph.seeds(initial.entrySet().stream()
                    .filter(entry -> entry.getValue().signum() > 0).map(Map.Entry::getKey).toList());
        }
        if (optimizedGuards && (!orderAllocation || !allocationChoices)) {
            var direct = craftLess ? directCraftLessPlan(amount) : directPlan(amount);
            if (direct != null) return direct;
        }
        if (!craftLess && orderAllocation && completeStock && optimizedGuards && !initialKeys.isEmpty()
                && amount.bitLength() >= projectionMinimumBits && projectedQuotas) {
            var projected = projectedOrder(amount);
            if (projected != null) return certified(projected);
        }
        // #236: collect shortages locally; funded alternatives still use isolated non-simulation trials.
        if (shortagePlanning && orderAllocation && !craftLess)
            return certified(Objects.requireNonNull(attempt(amount, true)));
        var result = attempt(amount, false);
        if (result != null) return certified(result);
        if (craftLess) {
            BigInteger success = ZERO;
            for (var step = ONE.shiftLeft(amount.bitLength() - 1); step.signum() > 0; step = step.shiftRight(1)) {
                var candidate = success.add(step);
                if (candidate.compareTo(amount) >= 0) continue;
                var trial = attempt(candidate, false);
                if (trial != null) { success = candidate; result = trial; }
            }
            if (result != null) return certified(result);
        }
        return certified(Objects.requireNonNull(attempt(amount, true)));
    }

    private VmCraftingPlan directCraftLessPlan(BigInteger amount) {
        if (!completeStock || root.emit) return null;
        var choices = root.processes();
        if (choices.size() != 1) return null;
        var process = choices.get(0);
        var program = demandCompiler.getExpanded(process.pattern,
                this::initialPositive);
        if (program == null) return null;
        if (!program.period.equals(ONE)) {
            // #218: fractional shared paths have trial-dependent conceptual node charges.
            var seen = Collections.newSetFromMap(new IdentityHashMap<VmDemandProgram, Boolean>());
            var pending = new ArrayDeque<VmDemandProgram>();
            pending.add(program);
            while (!pending.isEmpty()) {
                checkpoint();
                var current = pending.removeFirst();
                if (!seen.add(current)) return null;
                for (var child : current.children) if (child != null) pending.addLast(child);
            }
        }
        // #218: raw-demand bounds cannot replace available intermediate stock or its producers.
        for (var key : program.intermediates)
            if (initialPositive(key)) return null;
        var output = process.output(root.key);
        var requestedTimes = PatternCompiler.ceilDiv(amount, output);
        var upperTimes = requestedTimes;
        for (var entry : program.needs.entrySet()) {
            checkpoint();
            var leaf = program.leaves.get(entry.getKey());
            if (leaf.emit()) continue;
            var coefficient = entry.getValue();
            if (coefficient.numerator().signum() <= 0) continue;
            var available = initialAmount(entry.getKey());
            var fundedTimes = available.multiply(coefficient.denominator()).divide(coefficient.numerator());
            if (leaf.stockOnly() && fundedTimes.compareTo(requestedTimes) < 0
                    && !knownProducers.computeIfAbsent(entry.getKey(), resolver).isEmpty()) return null;
            upperTimes = upperTimes.min(fundedTimes);
        }
        if (!program.period.equals(ONE)) {
            // Failed full-size trials also establish AE2's conceptual capacity tree.
            var requested = attempt(amount, false);
            if (requested != null) return requested;
        }
        // Exact whole periods form a funded lower bound; fractional requirements only an upper bound.
        var lowerTimes = upperTimes.divide(program.period).multiply(program.period);
        var upper = amount.min(upperTimes.multiply(output));
        var lower = amount.min(lowerTimes.multiply(output));
        if (upper.signum() == 0) {
            return integralMultiple(requestedTimes, program.period)
                    ? materializeDirect(amount, process, program, requestedTimes, true) : attempt(amount, true);
        }
        if (lower.equals(upper)) return materializeDirect(upper, process, program, lowerTimes, false);
        var result = attempt(upper, false);
        if (result != null) return result;
        var high = upper.subtract(ONE);
        result = lower.signum() > 0 ? attempt(lower, false) : null;
        while (lower.compareTo(high) < 0) {
            checkpoint();
            var candidate = lower.add(high).add(ONE).shiftRight(1);
            var trial = attempt(candidate, false);
            if (trial == null) high = candidate.subtract(ONE);
            else { lower = candidate; result = trial; }
        }
        return result != null ? result : attempt(amount, true);
    }

    private VmCraftingPlan projectedOrder(BigInteger amount) {
        orderProjection = "supply-bound";
        var entry = new State();
        var total = ZERO;
        boolean bounded = supplyLimits != null;
        for (var process : root.processes()) {
            var bound = optimisticLimit(process, entry, PatternCompiler.ceilDiv(amount, process.output(root.key)));
            if (bound == null) { bounded = false; break; }
            total = total.add(bound.multiply(process.output(root.key)));
        }
        boolean impossible = bounded && total.compareTo(amount) < 0;
        orderProjection = impossible ? "proven-shortage" : "unproven";
        if (branchProfile != null) branchProfile.rejected(root.key, impossible ? "order-proven-shortage" : "order-unproven");
        var state = new State();
        boolean oldSimulation = simulate;
        simulate = true; preferredProjection = true; orderGuard.unsupported();
        try {
            orderProjection = "demand-expansion";
            request(root, amount, state, null);
            if (!state.emitted.isEmpty()) { orderProjection = "declined:emitter"; return null; }
            boolean missing = !state.missing.isEmpty();
            // #236: headless fixture preparation may inspect a draft; this never certifies its feasibility.
            if (missing && projectedShortageObserver != null)
                projectedShortageObserver.accept(Map.copyOf(state.missing));
            if (missing && !impossible) {
                orderProjection = "conservation-proof";
                impossible = VmSupplyPotential.proves(knownProducers, initial, root.key, amount,
                        state.patterns, state.missing, this::checkpoint);
            }
            if (!missing && impossible) throw new IllegalStateException("Funded order contradicted optimistic supply ceiling");
            if (missing && !impossible) { orderProjection = "declined:uncertified-shortage"; return null; }
            orderProjection = missing ? "accepted:shortage" : "accepted:funded";
            planPasses++;
            state.bytes = state.bytes.add(new VmByteCost(root.nodeCount(null).multiply(BigInteger.valueOf(8)), ONE));
            return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(), missing, multiple,
                    new VmKeyCounter(state.used), new VmKeyCounter(state.emitted), new VmKeyCounter(state.missing), state.patterns);
        } catch (ProjectionUnsupported unsupported) { orderProjection = "declined:" + unsupported.getMessage(); return null; }
        finally { simulate = oldSimulation; preferredProjection = false; }
    }

    private VmCraftingPlan directPlan(BigInteger amount) {
        if (!completeStock || root.emit) return null;
        var choices = root.processes();
        if (choices.isEmpty()) return null;
        Process first = null;
        VmDemandProgram firstProgram = null;
        BigInteger firstTimes = null;
        for (var process : choices) {
            var program = demandCompiler.getExpanded(process.pattern,
                    this::initialPositive);
            if (program == null) return null;
            if (choices.size() > 1 && !program.period.equals(ONE)) return null;
            var times = PatternCompiler.ceilDiv(amount, process.output(root.key));
            if (!integralMultiple(times, program.period)) return null;
            for (var key : program.intermediates)
                if (initialPositive(key)) return null;
            boolean resolved = false;
            for (var entry : program.needs.entrySet()) {
                var key = entry.getKey();
                var leaf = program.leaves.get(key);
                if (leaf.emit() || !leaf.stockOnly()
                        || initialAmount(key).compareTo(entry.getValue().scale(times)) >= 0) continue;
                if (!knownProducers.computeIfAbsent(key, resolver).isEmpty()) return null;
                resolved = true;
            }
            if (resolved) {
                program = demandCompiler.getExpanded(process.pattern,
                        this::initialPositive);
                if (program == null || !integralMultiple(times, program.period)) return null;
            }
            for (var key : program.intermediates)
                if (initialPositive(key)) return null;
            boolean shortage = false, impossible = false;
            for (var entry : program.needs.entrySet()) {
                var key = entry.getKey();
                var leaf = program.leaves.get(key);
                var available = initialAmount(key);
                if (leaf.stockOnly() && available.compareTo(entry.getValue().scale(times)) < 0) return null;
                if (leaf.emit() || available.compareTo(entry.getValue().scale(times)) >= 0) continue;
                shortage = true;
                if (program.period.equals(ONE) && available.multiply(entry.getValue().denominator())
                        .compareTo(entry.getValue().numerator()) < 0) impossible = true;
            }
            if (first == null) { first = process; firstProgram = program; firstTimes = times; }
            if (!shortage) return materializeDirect(amount, process, program, times, false);
            if (choices.size() == 1) return materializeDirect(amount, process, program, times, true);
            if (!impossible) return null;
        }
        return materializeDirect(amount, first, firstProgram, firstTimes, true);
    }

    private VmCraftingPlan materializeDirect(BigInteger amount, Process process,
            VmDemandProgram program, BigInteger times, boolean simulation) {
        simulate = simulation;
        var state = new State();
        work++;
        state.charge(root.key, amount);
        applyAggregate(process, program, times, state);
        if (state.extract(root.key, amount).compareTo(amount) != 0)
            throw new IllegalStateException("VM demand program did not produce its requested output");
        state.bytes = state.bytes.add(new VmByteCost(root.nodeCount(null).multiply(BigInteger.valueOf(8)), ONE));
        return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(), simulation,
                multiple, new VmKeyCounter(state.used), new VmKeyCounter(state.emitted),
                new VmKeyCounter(state.missing), state.patterns);
    }

    private VmCraftingPlan attempt(BigInteger amount, boolean simulation) {
        planPasses++;
        simulate = simulation;
        var state = new State();
        try { request(root, amount, state, null); }
        catch (Missing absent) { return null; }
        state.bytes = state.bytes.add(new VmByteCost(root.nodeCount(null).multiply(BigInteger.valueOf(8)), ONE));
        return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(),
                simulation && (!shortagePlanning || !orderAllocation || !state.missing.isEmpty()),
                multiple, new VmKeyCounter(state.used), new VmKeyCounter(state.emitted),
                new VmKeyCounter(state.missing), state.patterns);
    }

    record RouteKey(String path, BigInteger count, boolean simulation) { }
    private record RouteDomain(List<AEKey> initial, List<AEKey> entry) { }
    private record RouteNode(List<RouteProcess> processes) { }
    private record RouteProcess(List<RouteNode> inputs, RouteShape shape) { }
    private record RouteShape(String pattern, List<RouteShape> children) { }
    record Route(Map<AEKey, BigInteger> initialAmounts, Map<AEKey, BigInteger> entryAmounts,
            Map<AEKey, BigInteger> entryUsed, Set<AEKey> ledger, Map<AEKey, RouteDomain> domains,
            Map<AEKey, BigInteger> stock, Map<AEKey, BigInteger> used,
            Map<AEKey, BigInteger> emitted, Map<AEKey, BigInteger> missing,
            Map<String, BigInteger> patterns, Map<Family, KeyCounter> indexes,
            Set<AEKey> entryProducers, Set<AEKey> exitProducers,
            VmByteCost bytes, RouteNode capacity, boolean failed, int weight) { }

    private static List<AEKey> domainKeys(KeyCounter index, AEKey query) {
        var result = new ArrayList<AEKey>();
        for (var entry : index.findFuzzy(query, FuzzyMode.IGNORE_ALL)) result.add(entry.getKey());
        return List.copyOf(result);
    }

    private void routeRead(AEKey key) {
        if (activeProbe != null) activeProbe.read(key);
        if (!routeCaptures.isEmpty()) routeCaptures.get(routeCaptures.size() - 1).read(key);
    }

    private void routeDomain(AEKey key) {
        if (activeProbe != null) activeProbe.domain(key);
        if (!routeCaptures.isEmpty()) routeCaptures.get(routeCaptures.size() - 1).domain(key);
    }

    private boolean accepts(Route route, State state) {
        if (!route.ledger().equals(state.stock.keySet())) return false;
        if (!route.entryProducers().equals(knownProducers.keySet())) return false;
        for (var entry : route.entryAmounts().entrySet()) {
            checkpoint();
            var key = entry.getKey();
            var available = state.stock.getOrDefault(key, initial.getOrDefault(key, ZERO));
            if (!entry.getValue().equals(available)
                    || !route.initialAmounts().get(key).equals(initial.getOrDefault(key, ZERO))
                    || !route.entryUsed().get(key).equals(state.used.getOrDefault(key, ZERO))) return false;
        }
        for (var entry : route.domains().entrySet()) {
            checkpoint();
            var key = entry.getKey();
            if (!entry.getValue().initial().equals(domainKeys(initialKeys, key))
                    || !entry.getValue().entry().equals(domainKeys(
                            state.keys.getOrDefault(Family.of(key), initialKeys), key))) return false;
        }
        return true;
    }

    private void applyRoute(Route route, Node node, State state) {
        // #236: enclosing summaries and whole-order proofs must see reused dependencies too.
        route.entryAmounts().keySet().forEach(key -> { routeRead(key); orderGuard.exact(key); });
        route.domains().keySet().forEach(key -> { routeDomain(key); orderGuard.domain(key, initialKeys); });
        route.stock().forEach(state::putStock);
        state.used.putAll(route.used());
        route.emitted().forEach((key, amount) -> merge(state.emitted, key, amount));
        route.missing().forEach((key, amount) -> merge(state.missing, key, amount));
        route.patterns().forEach((id, amount) -> merge(state.patterns,
                Objects.requireNonNull(routePatterns.get(id)), amount));
        state.keys.putAll(route.indexes());
        state.bytes = state.bytes.add(route.bytes());
        restoreCapacity(node, route.capacity());
        for (var key : route.exitProducers()) {
            checkpoint();
            knownProducers.computeIfAbsent(key, resolver);
        }
    }

    private void restoreCapacity(Node node, RouteNode saved) {
        checkpoint();
        if (saved.processes() == null) return;
        var processes = node.processes();
        if (processes.size() != saved.processes().size())
            throw new IllegalStateException("Validated route catalog changed");
        for (int i = 0; i < processes.size(); i++) {
            var process = processes.get(i);
            var old = saved.processes().get(i);
            process.aggregatedShape = restoreShape(old.shape());
            if (old.inputs() != null) {
                var inputs = process.inputs();
                for (int j = 0; j < inputs.size(); j++) restoreCapacity(inputs.get(j), old.inputs().get(j));
            }
        }
    }

    private CapacityShape restoreShape(RouteShape saved) {
        if (saved == null) return null;
        checkpoint();
        var children = new ArrayList<CapacityShape>();
        for (var child : saved.children()) children.add(restoreShape(child));
        return new CapacityShape(Objects.requireNonNull(routePatterns.get(saved.pattern())), children);
    }

    private final class RouteCapture implements AutoCloseable {
        final State state, before;
        final VmJournalMap.Snapshot<AEKey, BigInteger> beforeUsed;
        final Map<Family, KeyCounter> entryIndexes;
        final Set<AEKey> ledger;
        final Set<AEKey> entryProducers;
        final Map<AEKey, BigInteger> amounts = new LinkedHashMap<>();
        final Map<AEKey, RouteDomain> domains = new LinkedHashMap<>();
        final long workBefore = work;
        int weight;
        boolean supported = true;

        RouteCapture(State state) {
            this.state = state;
            before = state.replayCopy();
            beforeUsed = ((VmJournalMap<AEKey, BigInteger>) state.used).snapshot();
            entryIndexes = VmBranchMap.snapshot(state.keys);
            ledger = Set.copyOf(state.stock.keySet());
            entryProducers = Set.copyOf(knownProducers.keySet());
        }

        void read(AEKey key) {
            if (!supported || amounts.containsKey(key)) return;
            if (amounts.size() >= 4096) { supported = false; return; }
            var value = before.stock.get(key);
            amounts.put(key, value == null ? initial.getOrDefault(key, ZERO) : value);
        }

        void domain(AEKey key) {
            if (!supported || domains.containsKey(key)) return;
            if (domains.size() >= 4096) { supported = false; return; }
            var old = domainKeys(initialKeys, key);
            var entry = domainKeys(entryIndexes.getOrDefault(Family.of(key), initialKeys), key);
            weight += old.size() + entry.size();
            if (weight > 8192) { supported = false; return; }
            domains.put(key, new RouteDomain(old, entry));
        }

        private <K> Map<K, BigInteger> changes(Map<K, BigInteger> after, Map<K, BigInteger> snapshot, boolean delta) {
            var result = new LinkedHashMap<K, BigInteger>();
            for (var key : ((VmJournalMap.Snapshot<K, BigInteger>) snapshot).changedKeys()) {
                var value = after.get(key);
                result.put(key, delta ? value.subtract(snapshot.getOrDefault(key, ZERO)) : value);
            }
            weight += result.size();
            return Collections.unmodifiableMap(result);
        }

        Route finish(Node node, boolean failed) {
            if (!supported) return null;
            var savedStock = changes(state.stock, before.stock, false);
            var savedUsed = changes(state.used, beforeUsed, false);
            savedStock.keySet().forEach(this::read);
            savedUsed.keySet().forEach(this::read);
            if (!supported) return null;
            var original = new HashMap<AEKey, BigInteger>();
            var used = new HashMap<AEKey, BigInteger>();
            amounts.keySet().forEach(key -> {
                original.put(key, initial.getOrDefault(key, ZERO));
                used.put(key, beforeUsed.getOrDefault(key, ZERO));
            });
            var emitted = changes(state.emitted, before.emitted, true);
            var missing = changes(state.missing, before.missing, true);
            var patterns = new LinkedHashMap<String, BigInteger>();
            changes(state.patterns, before.patterns, true).forEach((pattern, count) ->
                    patterns.put(Objects.requireNonNull(routeIds.get(pattern)), count));
            var indexes = new HashMap<Family, KeyCounter>();
            state.keys.forEach((family, index) -> {
                if (entryIndexes.get(family) != index) indexes.put(family, index);
            });
            var exitProducers = Set.copyOf(knownProducers.keySet());
            weight += amounts.size() * 3 + ledger.size() + indexes.size()
                    + entryProducers.size() + exitProducers.size();
            var capacity = captureCapacity(node);
            if (!supported || weight > 16384) return null;
            return new Route(Map.copyOf(original), Map.copyOf(amounts), Map.copyOf(used), ledger,
                    Map.copyOf(domains), savedStock, savedUsed, emitted, missing, Map.copyOf(patterns),
                    Map.copyOf(indexes), entryProducers, exitProducers,
                    state.bytes.subtract(before.bytes), capacity, failed, weight);
        }

        private RouteNode captureCapacity(Node node) {
            checkpoint();
            if (++weight > 16384) { supported = false; return null; }
            if (node.processes == null) return new RouteNode(null);
            var saved = new ArrayList<RouteProcess>();
            for (var process : node.processes) {
                List<RouteNode> inputs = null;
                if (process.inputs != null) {
                    inputs = new ArrayList<>();
                    for (var child : process.inputs) inputs.add(captureCapacity(child));
                }
                saved.add(new RouteProcess(inputs == null ? null : Collections.unmodifiableList(inputs),
                        captureShape(process.aggregatedShape)));
                if (!supported) return null;
            }
            return new RouteNode(List.copyOf(saved));
        }

        private RouteShape captureShape(CapacityShape shape) {
            if (shape == null) return null;
            checkpoint();
            if (++weight > 16384) { supported = false; return null; }
            var children = new ArrayList<RouteShape>();
            for (var child : shape.children) {
                children.add(captureShape(child));
                if (!supported) return null;
            }
            return new RouteShape(Objects.requireNonNull(routeIds.get(shape.pattern)),
                    Collections.unmodifiableList(children));
        }

        @Override public void close() { before.closeCheckpoint(); beforeUsed.close(); }
    }

    private final class Node {
        final AEKey key;
        final BigInteger unit;
        final IPatternDetails.IInput input;
        final Node ancestor;
        final boolean emit;
        final String path;
        final int depth;
        List<Process> processes;
        private Set<AEKey> ancestorKeys;
        VmZeroSupply.Node zeroCapacity, lastZeroProof;
        VmZeroSupply.Node zeroCandidate;
        boolean zeroChecked;

        Set<AEKey> ancestors() {
            if (ancestorKeys == null) {
                var keys = new HashSet<AEKey>();
                for (var cursor = this; cursor != null; cursor = cursor.ancestor) keys.add(cursor.key);
                ancestorKeys = Collections.unmodifiableSet(keys);
            }
            return ancestorKeys;
        }

        Node(AEKey encoded, BigInteger unit, IPatternDetails.IInput input, Node ancestor, String path) {
            createdNodes++;
            if (path != null) pathStrings++;
            this.path = path;
            this.depth = ancestor == null ? 0 : ancestor.depth + 1;
            this.input = input;
            this.ancestor = ancestor;
            this.unit = unit;
            key = input instanceof PatternCompiler.DetachedInput detached ? detached.craftedKey() : encoded;
            emit = input instanceof PatternCompiler.DetachedInput detached ? detached.emittable() : emitters.test(encoded);
        }

        BigInteger nodeCount(CapacityShape inherited) {
            return nodeCount(inherited, null);
        }
        BigInteger nodeCount(CapacityShape inherited, VmZeroSupply.Node zero) {
            var count = new CapacityCount();
            countNodes(count, inherited, zero);
            return count.total();
        }
        void countNodes(CapacityCount count, CapacityShape inherited, VmZeroSupply.Node zero) {
            if (zeroCapacity != null) zero = zeroCapacity;
            if (processes == null && zero != null) { count.add(zeroNodes(zero, inherited)); return; }
            count.add(1);
            boolean inheritedFound = false;
            if (processes != null) for (var process : processes) {
                var shape = inherited != null && inherited.pattern == process.pattern ? inherited : null;
                inheritedFound |= shape != null;
                process.countNodes(count, shape, zero == null ? null : zero.process(process.pattern));
            }
            if (zero != null) for (var failed : zero.processes)
                if (processes == null || processes.stream().noneMatch(p -> p.pattern == failed.pattern()))
                    count.add(failed.nodes());
            if (inherited != null && !inheritedFound) count.add(inherited.nodes);
        }

        List<Process> processes() {
            if (processes != null) return processes;
            processes = new ArrayList<>();
            var definitions = knownProducers.computeIfAbsent(key, resolver);
            for (var pattern : definitions) {
                if (ancestor != null && ancestor.conflicts(pattern)) continue;
                processes.add(new Process(pattern, this, processes.size()));
            }
            multiple |= processes.size() > 1;
            return processes;
        }

        boolean conflicts(IPatternDetails pattern) {
            var metadata = sharedDefinitions ? processMetadata.get(pattern) : null;
            if (metadata != null && metadata.conflictKeys != null) {
                for (var cursor = this; cursor != null; cursor = cursor.ancestor)
                    if (metadata.conflictKeys.contains(cursor.key)) return true;
                return false;
            }
            for (Node cursor = this; cursor != null; cursor = cursor.ancestor) {
                for (var output : pattern.getOutputs()) if (cursor.key.equals(output.what())) return true;
                for (var slot : pattern.getInputs())
                    if (cursor.key.equals(slot.getPossibleInputs()[0].what())) return true;
            }
            return false;
        }
    }

    private final class Process {
        final IPatternDetails pattern;
        List<Node> inputs;
        final Node parent;
        final int position;
        ProcessMetadata metadata;
        CapacityShape aggregatedShape;
        private VmDemandProgram checkedAggregation;
        private VmMissingProgram missingProgram;
        private boolean missingProgramChecked;
        private List<VmMissingProgram.FailureCut> failureCuts;
        private boolean fullyVisited;
        private int visitChecks;
        private int projectedChunks;
        private boolean structurallyAggregatable;
        Process(IPatternDetails pattern, Node parent, int position) {
            this.position = position;
            this.pattern = pattern;
            this.parent = parent;
        }
        boolean allowsAggregation(VmDemandProgram program) {
            if (checkedAggregation != program) {
                checkedAggregation = program;
                structurallyAggregatable = true;
                for (var ancestor = parent.ancestor; ancestor != null; ancestor = ancestor.ancestor)
                    if (program.dependencies.contains(ancestor.key)) {
                        structurallyAggregatable = false;
                        break;
                    }
            }
            return structurallyAggregatable;
        }
        List<Node> inputs() {
            if (inputs == null) {
                inputs = new ArrayList<>();
                for (var slot : pattern.getInputs()) {
                    var primary = slot.getPossibleInputs()[0];
                    inputs.add(new Node(primary.what(), BigInteger.valueOf(primary.amount()), slot, parent,
                            sharedDefinitions && orderAllocation ? null : parent.path + "/" + position + "/" + inputs.size()));
                }
            }
            return inputs;
        }
        ProcessMetadata metadata() {
            if (metadata == null) metadata = processMetadata.computeIfAbsent(pattern,
                    VmInstructionExecution.this::inspectProcess);
            return metadata;
        }
        BigInteger nodeCount(CapacityShape inherited) {
            return nodeCount(inherited, null);
        }
        BigInteger nodeCount(CapacityShape inherited, VmZeroSupply.Process zero) {
            var count = new CapacityCount();
            countNodes(count, inherited, zero);
            return count.total();
        }
        void countNodes(CapacityCount count, CapacityShape inherited, VmZeroSupply.Process zero) {
            var shape = CapacityShape.union(inherited, aggregatedShape);
            if (inputs == null && zero != null) { count.add(zeroProcessNodes(zero, shape)); return; }
            if (inputs == null) {
                count.add(pattern instanceof PatternCompiler.DetachedPattern detached
                        ? detached.inputCount() : pattern.getInputs().length);
                if (shape != null) for (var child : shape.children) if (child != null) count.add(child.nodes);
                return;
            }
            for (int i = 0; i < inputs.size(); i++)
                inputs.get(i).countNodes(count, shape == null ? null : shape.children.get(i),
                        zero != null && i == 0 ? zero.first() : null);
        }
        private VmPatternOutputs outputs;
        VmPatternOutputs outputs() {
            // #236: immutable definitions are shared only within this calculation, never stock or path state.
            if (sharedDefinitions) {
                var definition = metadata();
                if (definition.outputs == null) {
                    definition.outputs = VmPatternOutputs.inspect(pattern);
                    outputSummaries++;
                }
                return definition.outputs;
            }
            if (outputs == null) { outputs = VmPatternOutputs.inspect(pattern); outputSummaries++; }
            return outputs;
        }
        BigInteger output(AEKey key) {
            return outputs().amount(key);
        }
    }

    /** #236: only concrete slot counts are packed; virtual capacities remain arbitrary precision. */
    static final class CapacityCount {
        private long concrete;
        private BigInteger virtual = ZERO;
        void add(long count) {
            if (count < 0) throw new IllegalArgumentException("Negative capacity node count");
            if (count > Long.MAX_VALUE - concrete) { virtual = virtual.add(BigInteger.valueOf(concrete)); concrete = 0; }
            concrete += count;
        }
        void add(BigInteger count) { virtual = virtual.add(count); }
        BigInteger total() { return virtual.add(BigInteger.valueOf(concrete)); }
    }

    private CapacityShape capacityShape(VmDemandProgram program) {
        if (program == null) return null;
        var cached = capacityShapes.get(program);
        if (cached != null) return cached;
        var children = new ArrayList<CapacityShape>();
        for (var child : program.children) children.add(capacityShape(child));
        var result = new CapacityShape(program.pattern, children);
        capacityShapes.put(program, result);
        return result;
    }

    private BigInteger zeroNodes(VmZeroSupply.Node zero, CapacityShape inherited) {
        if (inherited == null) return zero.nodes;
        checkpoint();
        var match = zero.process(inherited.pattern);
        return match == null ? zero.nodes.add(inherited.nodes)
                : zero.nodes.subtract(match.nodes()).add(zeroProcessNodes(match, inherited));
    }

    private BigInteger zeroProcessNodes(VmZeroSupply.Process zero, CapacityShape shape) {
        if (shape == null) return zero.nodes();
        var count = BigInteger.valueOf(zero.slots() - 1).add(zeroNodes(zero.first(), shape.children.get(0)));
        for (int i = 1; i < shape.children.size(); i++)
            if (shape.children.get(i) != null) count = count.add(shape.children.get(i).nodes);
        return count;
    }

    /** #218: visited capacity nodes survive later stock-boundary summaries. No quantity state is retained. */
    private static final class CapacityShape {
        final IPatternDetails pattern;
        final List<CapacityShape> children;
        final BigInteger nodes;
        CapacityShape(IPatternDetails pattern, List<CapacityShape> children) {
            this.pattern = pattern;
            this.children = Collections.unmodifiableList(children);
            var count = BigInteger.valueOf(children.size());
            for (var child : children) if (child != null) count = count.add(child.nodes);
            nodes = count;
        }
        private record Pair(CapacityShape first, CapacityShape second) { }
        static CapacityShape union(CapacityShape first, CapacityShape second) {
            if (first == null) return second;
            if (second == null || first == second) return first;
            return union(first, second, new HashMap<>());
        }
        private static CapacityShape union(CapacityShape first, CapacityShape second, Map<Pair, CapacityShape> memo) {
            if (first == null) return second;
            if (second == null || first == second) return first;
            var pair = new Pair(first, second);
            var cached = memo.get(pair);
            if (cached != null) return cached;
            if (first.pattern != second.pattern || first.children.size() != second.children.size())
                throw new IllegalStateException("incompatible VM capacity shapes");
            var children = new ArrayList<CapacityShape>();
            boolean sameFirst = true, sameSecond = true;
            for (int i = 0; i < first.children.size(); i++) {
                var child = union(first.children.get(i), second.children.get(i), memo);
                children.add(child);
                sameFirst &= child == first.children.get(i);
                sameSecond &= child == second.children.get(i);
            }
            var result = sameFirst ? first : sameSecond ? second : new CapacityShape(first.pattern, children);
            memo.put(pair, result);
            return result;
        }
    }

    private static final class ProcessMetadata {
        private VmPatternOutputs outputs;
        private final boolean single, containers;
        private CraftingBytecode code;
        private BigInteger[] multipliers;
        private byte[] independentInputs;
        private Set<AEKey> conflictKeys;
        private boolean projectionPrepared;

        ProcessMetadata(boolean single, boolean containers) {
            this.single = single;
            this.containers = containers;
        }

        CraftingBytecode code(IPatternDetails pattern) {
            // #218: retain compiled blocks without compiling unused alternatives.
            if (code == null) {
                PatternCompiler.compileIfAbsent(pattern);
                code = Objects.requireNonNull(PatternCompiler.getCompiled(pattern));
            }
            return code;
        }
    }

    private ProcessMetadata inspectProcess(IPatternDetails pattern) {
        boolean limited = false, returned = false;
        boolean detachedOnly = true;
        var conflictKeys = new HashSet<AEKey>();
        for (var output : pattern.getOutputs()) conflictKeys.add(output.what());
        for (var slot : pattern.getInputs()) {
            var templates = slot.getPossibleInputs();
            conflictKeys.add(templates[0].what());
            detachedOnly &= slot instanceof PatternCompiler.DetachedInput;
            returned |= slot.getRemainingKey(templates[0].what()) != null;
            if (slot instanceof PatternCompiler.DetachedInput detached) {
                returned |= detached.mayReturnItems();
                for (var output : pattern.getOutputs()) limited |= detached.mayConsumeOutput(output.what());
            } else {
                for (int i = 1; i < templates.length; i++) {
                    try { returned |= slot.getRemainingKey(templates[i].what()) != null; }
                    catch (CancellationException cancelled) { throw cancelled; }
                    catch (RuntimeException unused) { returned = true; }
                }
            }
            for (var candidate : slot.getPossibleInputs())
                for (var output : pattern.getOutputs()) if (output.what().equals(candidate.what())) limited = true;
        }
        var metadata = new ProcessMetadata(limited || returned, returned);
        if (detachedOnly) metadata.conflictKeys = Set.copyOf(conflictKeys);
        return metadata;
    }

    private void request(Node node, BigInteger count, State state, Map<AEKey, BigInteger> returns) {
        checkpoint();
        // #236: an existing capacity tree or enclosing replay needs its ordinary ordered execution.
        if (orderAllocation || routes == null || node.processes != null || !traces.isEmpty() || returns != null
                || node.depth % 4 != 0 || routeCaptures.size() >= 8 || state.stock.size() > 8192) {
            requestUncached(node, count, state, returns);
            return;
        }
        var key = new RouteKey(node.path, count, simulate);
        var cached = previousRoutes == null ? null : previousRoutes.get(key);
        if (cached != null && accepts(cached, state)) {
            applyRoute(cached, node, state);
            routes.put(key, cached);
            reusedRoutes++;
            if (cached.failed()) throw new Missing();
            return;
        }
        var capture = new RouteCapture(state);
        routeCaptures.add(capture);
        boolean finished = false, failed = false;
        try {
            requestUncached(node, count, state, returns);
            finished = true;
        } catch (Missing absent) {
            finished = true; failed = true;
            throw absent;
        } finally {
            routeCaptures.remove(routeCaptures.size() - 1);
            try {
                if (finished && work - capture.workBefore >= 8) {
                    var summary = capture.finish(node, failed);
                    if (summary != null) routes.put(key, summary);
                }
            } finally {
                if (!routeCaptures.isEmpty()) {
                    var parent = routeCaptures.get(routeCaptures.size() - 1);
                    if (!capture.supported) parent.supported = false;
                    else {
                        capture.amounts.keySet().forEach(parent::read);
                        capture.domains.keySet().forEach(parent::domain);
                    }
                }
                capture.close();
            }
        }
    }

    private void requestUncached(Node node, BigInteger count, State state, Map<AEKey, BigInteger> returns) {
        checkpoint();
        if (preferredProjection && (node.emit || node.input instanceof PatternCompiler.DetachedInput input
                && !inputFacts.ready(input, node.key))) throw new ProjectionUnsupported("input:" + diagnosticKey(node.key));
        if (activeProbe != null && node.input != null && (!(node.input instanceof PatternCompiler.DetachedInput input)
                || !inputFacts.ready(input, node.key))) activeProbe.supported = false;
        if (branchProfile != null) branchProfile.request(node.key);
        if (orderAllocation) { routeRead(node.key); orderGuard.exact(node.key); }
        work++;
        if ((work & 1023) == 0) progress.accept(work);
        if (simulate) simulatedRequests++;
        var requestedAmount = scaled(count, node.unit);
        state.charge(node.key, requestedAmount);
        var remaining = count;
        boolean retainSeed = orderAllocation && state.rawAmount(node.key).signum() > 0
                && requestedAmount.compareTo(state.rawAmount(node.key)) > 0
                && node.processes().stream().anyMatch(process -> process.metadata().single
                        && feedbackSupported(process.pattern, node.key));
        if (retainSeed) {
            // #236: do not reserve the only catalyst as finished goods before growing its stock.
        } else if (node.input == null) {
            remaining = remaining.subtract(state.extract(node.key, remaining));
        } else if (node.input instanceof PatternCompiler.DetachedInput detached && detached.exactKey() != null) {
            // #218: native processing inputs are exact, not a fuzzy NBT-family search.
            var key = detached.exactKey();
            var wanted = requestedAmount;
            var taken = divided(state.read(key, wanted).min(wanted), node.unit);
            if (taken.signum() > 0) {
                state.extract(key, scaled(taken, node.unit));
                remaining = remaining.subtract(taken);
            }
        } else {
            // Use AE2's key index for ordering only; quantities are never stored as long.
            var templates = stockedTemplates(node.input, state);
            int nextTemplate = 0;
            Iterator<? extends Map.Entry<AEKey, ?>> candidates = Collections.emptyIterator();
            var unit = ONE;
            while (remaining.signum() > 0 && (candidates.hasNext() || nextTemplate < templates.length)) {
                // #218: batch across template boundaries, retaining each candidate's own unit and priority.
                var batch = new ArrayList<Candidate>(128);
                for (int scanned = 0; scanned < 128; scanned++) {
                    while (!candidates.hasNext() && nextTemplate < templates.length) {
                        checkpoint();
                        var template = templates[nextTemplate++];
                        unit = BigInteger.valueOf(template.amount());
                        candidates = state.fuzzy(template.what()).iterator();
                    }
                    if (!candidates.hasNext()) break;
                    checkpoint();
                    var key = candidates.next().getKey();
                    // #218: lookahead must not add guards for candidates the ordered cursor never reaches.
                    batch.add(new Candidate(key, unit));
                    if (batch.size() == 1 && node.input instanceof PatternCompiler.DetachedInput detached
                            && inputFacts.ready(detached, key)) break;
                }
                if (batch.isEmpty()) continue;
                for (int index = 0; index < batch.size(); index++) {
                    if (remaining.signum() == 0) break;
                    var candidate = batch.get(index);
                    var key = candidate.key();
                    if (optimizedGuards && state.amount(key).compareTo(candidate.unit()) < 0) {
                        // Keep the guard when this skip is actually needed: later returns can replenish it.
                        state.read(key, candidate.unit(), true);
                        continue;
                    }
                    if (node.input instanceof PatternCompiler.DetachedInput detached && !inputFacts.ready(detached, key)) {
                        if (preferredProjection) throw new ProjectionUnsupported("candidate:" + diagnosticKey(key));
                        if (activeProbe != null) activeProbe.supported = false;
                        detached.prepareCandidates(batch.subList(index, batch.size()).stream()
                                .filter(next -> !optimizedGuards || state.amount(next.key()).compareTo(next.unit()) >= 0)
                                .map(Candidate::key).toList());
                    }
                    if (!inputFacts.valid(node.input, key)) continue;
                    var wanted = scaled(remaining, candidate.unit());
                    var available = state.read(key, wanted);
                    var taken = divided(available.min(wanted), candidate.unit());
                    if (taken.signum() == 0) continue;
                    state.extract(key, scaled(taken, candidate.unit()));
                    remaining = remaining.subtract(taken);
                    addReturn(node, key, taken, returns);
                }
            }
        }
        if (remaining.signum() == 0) return;
        addReturn(node, node.key, remaining, returns);
        var demand = scaled(remaining, node.unit);
        if (node.emit) { merge(state.emitted, node.key, demand); return; }
        var choices = node.processes();
        if (simulate && choices.size() > 1 && !preferredProjection) {
            // #218: an unrelated shortage must not make an unfunded first route hide a funded alternative.
            simulate = false;
            try { demand = craft(node, demand, state, choices); }
            finally { simulate = true; }
            if (demand.signum() > 0) demand = craft(node, demand, state, choices.subList(0, 1));
        } else {
            demand = craft(node, demand, state, choices);
        }
        if (demand.signum() == 0) return;
        if (!simulate) throw new Missing(orderAllocation ? count.subtract(PatternCompiler.ceilDiv(demand, node.unit)) : null,
                null, new Shortage(node.key, requestedAmount, requestedAmount.subtract(demand),
                        demand, node.lastZeroProof));
        merge(state.missing, node.key, demand);
    }

    private GenericStack[] stockedTemplates(IPatternDetails.IInput input, State state) {
        if (!projectedInputs || !optimizedGuards || supplyGraph == null || orderGuard.supported()
                || !traces.isEmpty() || activeProbe != null || !routeCaptures.isEmpty()
                || !(input instanceof PatternCompiler.DetachedInput)) return input.getPossibleInputs();
        var domain = inputDomains.get(input);
        if (domain == null) {
            var templates = input.getPossibleInputs();
            domain = new InputDomain(templates, supplyGraph.knownFamilies(
                    Arrays.stream(templates).map(GenericStack::what).toList()), new LinkedHashMap<>());
            if (inputDomains.size() < 512) inputDomains.put(input, domain);
        }
        if (domain.families == null) return domain.templates;
        var present = (BitSet) state.supplySeeds.clone();
        present.and(domain.families);
        var selected = domain.positive.get(present);
        if (selected == null) {
            // Only membership is projected: template order, duplicates and units remain intact.
            selected = Arrays.stream(domain.templates)
                    .filter(template -> !supplyGraph.knownAbsent(state.supplySeeds, template.what()))
                    .toArray(GenericStack[]::new);
            if (domain.positive.size() >= 16) domain.positive.remove(domain.positive.keySet().iterator().next());
            domain.positive.put(present, selected);
        }
        return selected;
    }

    private boolean prepareOrderAllocation() {
        var pending = new ArrayDeque<AEKey>();
        var seen = new HashSet<AEKey>();
        boolean feedback = false;
        pending.add(root.key);
        while (!pending.isEmpty()) {
            checkpoint();
            var key = pending.removeFirst();
            if (!seen.add(key)) continue;
            var choices = knownProducers.computeIfAbsent(key, resolver);
            allocationChoices |= choices.size() > 1;
            for (var pattern : choices) {
                var metadata = processMetadata.computeIfAbsent(pattern, this::inspectProcess);
                boolean eligible = !metadata.containers;
                allocationReturns |= metadata.containers;
                for (var slot : pattern.getInputs()) {
                    if (slot instanceof PatternCompiler.DetachedInput input) {
                        pending.add(input.craftedKey());
                        if (!inputFacts.ready(input, input.craftedKey())) eligible = false;
                        if (metadata.single && (input.exactKey() == null
                                || !input.exactKey().equals(input.craftedKey())
                                || slot.getPossibleInputs().length != 1)) eligible = false;
                    } else {
                        eligible = false;
                        pending.add(slot.getPossibleInputs()[0].what());
                    }
                }
                if (eligible) {
                    allocationPatterns.add(pattern);
                    feedback |= metadata.single;
                }
                for (var output : pattern.getOutputs())
                    if (!output.what().equals(key))
                        secondaryProducers.computeIfAbsent(output.what(), ignored -> new ArrayList<>()).add(pattern);
            }
        }
        // #236: fixed chains need neither quota policy nor loss of validated route reuse.
        return !allocationPatterns.isEmpty() && (allocationChoices || feedback);
    }

    private Map<AEKey, BigInteger> feedbackNeeds(IPatternDetails pattern) {
        var cached = feedbackInputs.get(pattern);
        if (cached != null) return cached;
        var needs = new LinkedHashMap<AEKey, BigInteger>();
        for (var slot : pattern.getInputs()) {
            var input = slot.getPossibleInputs()[0];
            needs.merge(input.what(), PatternCompiler.inputMultiplier(slot)
                    .multiply(BigInteger.valueOf(input.amount())), BigInteger::add);
        }
        var result = Collections.unmodifiableMap(needs);
        feedbackInputs.put(pattern, result);
        return result;
    }

    private boolean feedbackSupported(IPatternDetails pattern, AEKey target) {
        if (!allocationPatterns.contains(pattern)) return false;
        if (processMetadata.computeIfAbsent(pattern, this::inspectProcess).containers) return false;
        for (var slot : pattern.getInputs()) if (slot.getPossibleInputs()[0].amount() != 1) return false;
        var consumed = feedbackNeeds(pattern).getOrDefault(target, ZERO);
        return VmPatternOutputs.inspect(pattern).amount(target).compareTo(consumed) > 0;
    }

    private BigInteger allocationTimes(Process process, BigInteger demand) {
        var output = process.output(process.parent.key);
        if (!process.metadata().single) return PatternCompiler.ceilDiv(demand, output);
        var consumed = feedbackNeeds(process.pattern).getOrDefault(process.parent.key, ZERO);
        if (consumed.signum() == 0) return PatternCompiler.ceilDiv(demand, output);
        return ONE.add(PatternCompiler.ceilDiv(demand.subtract(output).max(ZERO), output.subtract(consumed)));
    }

    private void runAllocated(Process process, BigInteger times, State state) {
        if (process.metadata().single) runFeedbackAllocated(process, times, state);
        else run(process, times, state);
    }

    private void runFeedbackAllocated(Process process, BigInteger times, State state) {
        var needs = feedbackNeeds(process.pattern);
        var outputs = process.outputs().amounts();
        var acquired = new HashMap<AEKey, BigInteger>();
        for (var node : process.inputs()) {
            checkpoint();
            if (acquired.containsKey(node.key)) continue;
            var consumed = needs.get(node.key);
            var returned = outputs.getOrDefault(node.key, ZERO);
            var seed = returned.signum() == 0 ? consumed.multiply(times)
                    : consumed.add(consumed.subtract(returned).max(ZERO).multiply(times.subtract(ONE)));
            try { request(node, seed, state, null); }
            catch (Missing missing) {
                // #236: the first zero-loss seed request is identical for every positive quota.
                if (constantSeedBounds && acquired.isEmpty() && returned.compareTo(consumed) >= 0)
                    throw new Missing(ZERO, null, missing.shortage, process);
                throw missing;
            }
            acquired.put(node.key, seed);
            state.charge(node.key, consumed.multiply(times).subtract(seed));
        }
        outputs.forEach((key, output) -> state.insert(key, output.multiply(times)
                .subtract(needs.getOrDefault(key, ZERO).multiply(times)).add(acquired.getOrDefault(key, ZERO))));
        merge(state.patterns, process.pattern, times);
        state.bytes = state.bytes.add(new VmByteCost(times, ONE));
    }

    private BigInteger allocate(Node node, BigInteger demand, State state, List<Process> choices) {
        Map<AEKey, Optional<BigInteger>> supply = null;
        for (var process : choices) {
            checkpoint();
            var wanted = allocationTimes(process, demand);
            if (simulate) {
                var direct = preparedShortage(process, wanted, demand, state);
                if (direct != null) return demand.subtract(direct);
                runAllocated(process, wanted, state);
                return demand.subtract(state.extract(node.key, demand));
            }
            // Preserve conceptual capacity visits and enclosing ordered-trace guards.
            boolean bounded = !allocationReturns || scopedSupplyBounds && traces.isEmpty()
                    && returnFree(process.pattern) && (supplyGraph != null || fullyVisited(process, new int[]{8192}));
            var optimistic = optimisticLimit(process, state, wanted);
            BigInteger bound = null;
            if (bounded && optimistic == null) {
                if (supply == null) supply = new HashMap<>();
                else supply.clear();
                bound = quotaBound(process.pattern, state, supply, new HashSet<>(), node.ancestors());
                if (bound != null && process.inputs == null) earlyQuotaBounds++;
            }
            var limit = bound == null ? wanted : wanted.min(bound);
            if (optimistic != null && optimistic.compareTo(limit) < 0) {
                quantityBoundReductions++; limit = optimistic;
            }
            // #236: a source excluded by the actual ancestor path is not an unbounded cycle.
            if (bounded && !process.metadata().single) for (var input : process.inputs()) {
                if (!(input.input instanceof PatternCompiler.DetachedInput detached)
                        || !input.key.equals(detached.exactKey())) continue;
                if (input.emit || secondaryProducers.containsKey(input.key) || !input.processes().isEmpty()) continue;
                routeRead(input.key);
                orderGuard.exact(input.key);
                var amount = PatternCompiler.inputMultiplier(input.input).multiply(input.unit);
                limit = limit.min(state.rawAmount(input.key).divide(amount));
            }
            if (limit.signum() == 0) continue;
            if (shortagePlanning && allocationReturns && !process.metadata().single && (traceShortageProofs || traces.isEmpty())
                    && !process.inputs().isEmpty()) {
                var first = process.inputs().get(0);
                if (first.input instanceof PatternCompiler.DetachedInput input && first.key.equals(input.exactKey())) {
                    var proof = zeroCandidate(first);
                    if (proof != null && acceptsZero(proof, state, true)) {
                        var available = state.rawAmount(first.key);
                        var maximum = available.divide(PatternCompiler.inputMultiplier(input).multiply(first.unit));
                        if (maximum.compareTo(limit) < 0) {
                            if (!traces.isEmpty()) state.read(first.key,
                                    PatternCompiler.inputMultiplier(input).multiply(first.unit).multiply(maximum.add(ONE)), true);
                            // #236: the skipped oversized request would expose this failed prefix, not its quantities.
                            first.zeroCapacity = proof;
                            multiple |= proof.multiple;
                            orderGuard.exact(first.key);
                            routeRead(first.key);
                            provenQuotaBounds++;
                            limit = maximum;
                            if (limit.signum() == 0) continue;
                        }
                    }
                }
            }
            // #236: derive shared constraints before expanding a predictably oversized transaction.
            if (jointQuotaBounds) {
                var joint = jointQuota(process, limit, state);
                if (joint != null && (joint.upper().compareTo(limit) < 0
                        || joint.whole().signum() > 0 && joint.whole().compareTo(limit) < 0)) {
                    jointQuotaReductions++;
                    limit = joint.upper();
                    if (limit.signum() == 0) continue;
                    var whole = joint.whole();
                    if (whole.signum() > 0 && whole.compareTo(limit) < 0
                            && allocateQuota(process, whole, state, false).accepted()) {
                        // #236: consume certified whole periods first; only the rounding tail needs quota search.
                        demand = demand.subtract(state.extract(node.key, demand));
                        if (demand.signum() == 0) return ZERO;
                        demand = allocate(node, demand, state, List.of(process));
                        if (demand.signum() == 0) return ZERO;
                        continue;
                    }
                }
            }
            var projected = projectedQuota(process, limit, state);
            if (projected.signum() > 0) {
                demand = demand.subtract(state.extract(node.key, demand));
                if (demand.signum() == 0) return ZERO;
                demand = allocate(node, demand, state, List.of(process));
                if (demand.signum() == 0) return ZERO;
                continue;
            }
            var trial = allocateQuota(process, limit, state, true);
            boolean adjusted = false;
            while (!trial.accepted() && trial.affordable() != null) {
                // #236: a first-input budget proposes a new quota, not a monotonic feasibility proof.
                var next = limit.subtract(ONE).min(trial.affordable());
                if (adjusted && (!repeatedQuotaHints || next.bitLength() >= limit.bitLength())) break;
                if (adjusted) repeatedQuotaReductions++;
                limit = next;
                if (limit.signum() == 0) break;
                trial = allocateQuota(process, limit, state, repeatedQuotaHints);
                adjusted = true;
            }
            if (limit.signum() == 0) continue;
            if (trial.accepted()) {
                demand = demand.subtract(state.extract(node.key, demand));
            } else {
                // #236: validate each accepted block; competing choices need not be monotone.
                for (var block = ONE.shiftLeft(limit.bitLength() - 1); block.signum() > 0;
                        block = block.shiftRight(1)) {
                    checkpoint();
                    var quota = block.min(allocationTimes(process, demand));
                    if (quota.equals(limit)) continue;
                    if (allocateQuota(process, quota, state, false).accepted())
                        demand = demand.subtract(state.extract(node.key, demand));
                    if (demand.signum() == 0) return ZERO;
                }
            }
            if (demand.signum() == 0) return ZERO;
        }
        return demand;
    }

    private record QuotaTrial(boolean accepted, BigInteger affordable, Shortage shortage) { }
    private static final class ProjectionUnsupported extends RuntimeException {
        ProjectionUnsupported(String reason) { super(reason, null, false, false); }
    }
    private record QuotaProjection(State state, Map<AEKey, BigInteger> missing) { }

    private QuotaProjection projectQuota(Process process, BigInteger times, State entry) {
        var trial = new State(entry);
        boolean previousSimulation = simulate;
        simulate = true; preferredProjection = true; quotaProjections++;
        orderGuard.unsupported();
        try {
            runAllocated(process, times, trial);
            if (!trial.emitted.equals(entry.emitted)) return null;
            var missing = new HashMap<AEKey, BigInteger>();
            trial.missing.forEach((key, n) -> {
                var delta = n.subtract(entry.missing.getOrDefault(key, ZERO));
                if (delta.signum() > 0) missing.put(key, delta);
            });
            return new QuotaProjection(trial, missing);
        } catch (ProjectionUnsupported unsupported) {
            if (branchProfile != null) branchProfile.rejected(process.parent.key, "projection-unsupported");
            return null;
        }
        finally { simulate = previousSimulation; preferredProjection = false; }
    }

    private BigInteger projectedQuota(Process process, BigInteger wanted, State state) {
        if (!projectedQuotas || !optimizedGuards || wanted.bitLength() < projectionMinimumBits
                || preferredProjection || process.metadata().single || process.projectedChunks >= 8
                || initialKeys.isEmpty() || !traces.isEmpty() || activeProbe != null || !routeCaptures.isEmpty()
                || quotaCompiler.get(process.pattern) != null) return ZERO;
        process.projectedChunks++;
        var high = projectQuota(process, wanted, state);
        if (high == null) return ZERO;
        if (high.missing.isEmpty()) {
            if (branchProfile != null) branchProfile.rejected(process.parent.key, "projection-full");
            high.state.commitTo(state); orderGuard.unsupported(); projectedQuotaCommits++; return wanted;
        }
        var upper = wanted.shiftLeft(1);
        var far = projectQuota(process, upper, state);
        if (far == null) return ZERO;
        var at = wanted;
        for (int attempt = 0; attempt < 8; attempt++) {
            var proposed = at.subtract(ONE);
            for (var missing : high.missing.entrySet()) {
                var rise = far.missing.getOrDefault(missing.getKey(), ZERO).subtract(missing.getValue());
                if (rise.signum() <= 0) {
                    if (branchProfile != null) branchProfile.rejected(process.parent.key, "projection-flat");
                    return ZERO;
                }
                proposed = proposed.min(at.subtract(PatternCompiler.ceilDiv(
                        missing.getValue().multiply(upper.subtract(at)), rise)));
            }
            if (proposed.signum() <= 0 || proposed.compareTo(at) >= 0) {
                if (branchProfile != null) branchProfile.rejected(process.parent.key, "projection-zero");
                return ZERO;
            }
            var candidate = projectQuota(process, proposed, state);
            if (candidate == null) return ZERO;
            if (candidate.missing.isEmpty()) {
                if (branchProfile != null) branchProfile.rejected(process.parent.key, "projection-partial");
                candidate.state.commitTo(state); orderGuard.unsupported(); projectedQuotaCommits++; return proposed;
            }
            far = high; upper = at; at = proposed; high = candidate;
        }
        return ZERO;
    }

    private BigInteger optimisticLimit(Process process, State state, BigInteger wanted) {
        if (simulate || supplyLimits == null || !traces.isEmpty() || activeProbe != null) return null;
        if (state.quantityVersion == null) state.quantityVersion = VmSupplyUpperBound.version();
        var result = supplyLimits.limit(process.pattern, state.quantityVersion, state::unobservedAmount, key -> {
            if (supplyGraph != null && supplyGraph.knownAbsent(state.supplySeeds, key)) return ZERO;
            var amount = ZERO;
            for (var entry : state.keys.getOrDefault(Family.of(key), initialKeys).findFuzzy(key, FuzzyMode.IGNORE_ALL))
                amount = amount.add(state.unobservedAmount(entry.getKey()));
            return amount;
        }, wanted, this::checkpoint);
        if (result != null) {
            // #236: this ceiling is scoped to this stock state, not an ordered/cross-order certificate.
            orderGuard.unsupported(); routeCaptures.forEach(capture -> capture.supported = false);
        }
        return result;
    }

    private record JointQuota(BigInteger upper, BigInteger whole) { }
    private JointQuota jointQuota(Process process, BigInteger upper, State state) {
        if (!traces.isEmpty()) return null;
        var program = quotaCompiler.get(process.pattern);
        if (program == null || !process.allowsAggregation(program)
                || program.leaves.values().stream().anyMatch(VmDemandProgram.Leaf::stockOnly)) return null;
        if (!aggregateFuzzyDomain(program, state)) return null;
        boolean memoize = activeProbe == null && routeCaptures.isEmpty();
        if (state.quantityVersion == null) state.quantityVersion = VmSupplyUpperBound.version();
        var version = VmSupplyUpperBound.stamp(state.quantityVersion);
        var versions = jointStockBounds.get(program);
        var bounds = memoize && versions != null ? versions.get(version) : null;
        if (bounds == null) {
            bounds = computeJointStockBounds(program, state);
            if (memoize && jointStockBounds.size() < 512) {
                if (versions == null) {
                    versions = new LinkedHashMap<>(4, 0.75f, true); jointStockBounds.put(program, versions);
                }
                if (versions.size() >= 4) versions.remove(versions.keySet().iterator().next());
                versions.put(version, bounds);
            }
        }
        var limit = bounds.upper == null ? upper : upper.min(bounds.upper);
        var lower = bounds.lower == null ? upper : upper.min(bounds.lower);
        return new JointQuota(limit, lower.divide(program.period).multiply(program.period));
    }

    private JointStockBounds computeJointStockBounds(VmDemandProgram program, State state) {
        var credits = new HashMap<AEKey, BigInteger>();
        var pending = new ArrayDeque<VmDemandProgram>(); pending.add(program);
        var visited = Collections.newSetFromMap(new IdentityHashMap<VmDemandProgram, Boolean>());
        var credited = new HashSet<AEKey>();
        while (!pending.isEmpty()) {
            checkpoint();
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (var child : current.children) if (child != null) {
                pending.addLast(child);
                var key = child.pattern.getPrimaryOutput().what();
                if (!credited.add(key)) continue;
                orderGuard.exact(key); routeRead(key);
                var stocked = state.rawAmount(key);
                if (stocked.signum() == 0) continue;
                var yield = VmPatternOutputs.inspect(child.pattern).amount(key);
                for (var entry : child.needs.entrySet()) {
                    var coefficient = entry.getValue();
                    var credit = PatternCompiler.ceilDiv(stocked.multiply(coefficient.numerator()),
                            yield.multiply(coefficient.denominator()));
                    credits.merge(entry.getKey(), credit, BigInteger::add);
                }
            }
        }
        BigInteger limit = null, lower = null;
        for (var entry : program.needs.entrySet()) {
            checkpoint();
            if (program.leaves.get(entry.getKey()).emit()) continue;
            var amount = entry.getValue();
            if (amount.numerator().signum() <= 0) continue;
            var key = entry.getKey();
            orderGuard.exact(key);
            routeRead(key);
            // #236: shared leaf demands are already summed by the fixed-source compiler.
            var available = state.rawAmount(key);
            var funded = available.multiply(amount.denominator()).divide(amount.numerator());
            lower = lower == null ? funded : lower.min(funded);
            // #236: upward-rounded intermediate credits are optimistic only; accepted quotas still run normally.
            var possible = available.add(credits.getOrDefault(key, ZERO))
                    .multiply(amount.denominator()).divide(amount.numerator());
            limit = limit == null ? possible : limit.min(possible);
        }
        return new JointStockBounds(limit, lower);
    }
    private record Shortage(AEKey key, BigInteger requested, BigInteger fulfilled,
            BigInteger missing, VmZeroSupply.Node zeroProof) { }
    private record QuotaProbe(Node input, BigInteger requested, BigInteger perCraft, BigInteger delivered) { }
    private record ProbeKey(Node input, BigInteger requested) { }
    private record ProbeDomain(AEKey key, KeyCounter index) { }
    private record ProbeResult(BigInteger delivered, Map<AEKey, BigInteger> amounts,
            Map<Family, ProbeDomain> domains, int weight) { }

    /** #236: entry is suspended while its isolated child runs, so no inventory copy is needed. */
    private final class ProbeObservation {
        final State entry;
        final ProbeObservation parent = activeProbe;
        final int depth = parent == null ? 1 : parent.depth + 1;
        final Map<AEKey, BigInteger> amounts = new HashMap<>();
        final Map<Family, ProbeDomain> domains = new HashMap<>();
        boolean supported = true;
        int weight;
        ProbeObservation(State entry) { this.entry = entry; }
        void read(AEKey key) {
            if (!supported || amounts.containsKey(key)) return;
            if (++weight > 4096) { supported = false; return; }
            amounts.put(key, entry.unobservedAmount(key));
        }
        void domain(AEKey key) {
            var family = Family.of(key);
            if (!supported || domains.containsKey(family)) return;
            var index = entry.keys.getOrDefault(family, initialKeys);
            if (++weight > 4096) { supported = false; return; }
            for (var ignored : index.findFuzzy(key, FuzzyMode.IGNORE_ALL))
                if (++weight > 4096) { supported = false; return; }
            domains.put(family, new ProbeDomain(key, index));
        }
        void finish() {
            activeProbe = parent;
            if (parent == null) return;
            if (!supported) parent.supported = false;
            else {
                amounts.keySet().forEach(parent::read);
                domains.values().forEach(domain -> parent.domain(domain.key()));
            }
        }
    }

    private BigInteger isolatedProbe(QuotaProbe probe, State state) {
        boolean eligible = reuseQuotaProbes && traces.isEmpty();
        var key = new ProbeKey(probe.input(), probe.requested());
        var cached = eligible ? probeResults.get(key) : null;
        if (cached != null) {
            boolean matches = true;
            for (var entry : cached.amounts().entrySet()) {
                checkpoint();
                if (!entry.getValue().equals(state.rawAmount(entry.getKey()))) { matches = false; break; }
            }
            if (matches) for (var entry : cached.domains().entrySet()) {
                checkpoint();
                if (state.keys.getOrDefault(entry.getKey(), initialKeys) != entry.getValue().index()) { matches = false; break; }
            }
            if (matches) {
                // #236: same node retains visited capacity; this is no quantity-monotonicity inference.
                for (var dependency : cached.amounts().keySet()) {
                    routeRead(dependency); orderGuard.exact(dependency);
                }
                for (var domain : cached.domains().values()) {
                    routeDomain(domain.key()); orderGuard.domain(domain.key(), initialKeys);
                }
                reusedQuotaProbes++;
                return cached.delivered();
            }
        }
        var isolated = new State(state);
        isolatedQuotaProbes++;
        var delivered = probe.requested();
        long startedWork = work;
        var observation = eligible && (activeProbe == null || activeProbe.depth < 8) ? new ProbeObservation(state) : null;
        if (observation != null) activeProbe = observation;
        try { request(probe.input(), probe.requested(), isolated, null); }
        catch (Missing absent) { delivered = absent.funded; }
        finally {
            if (observation != null) observation.finish();
            branchEntries += isolated.changedEntries();
            if (branchProfile != null) branchProfile.probed(work - startedWork);
        }
        if (observation != null && observation.supported && delivered != null) {
            var budget = new int[]{8192};
            boolean visited = probe.input().processes != null || delivered.equals(probe.requested());
            if (probe.input().processes != null) for (var process : probe.input().processes)
                if (!fullyVisited(process, budget)) { visited = false; break; }
            if (visited) {
                var previous = probeResults.remove(key);
                if (previous != null) probeResultKeys -= previous.weight();
                while (!probeResults.isEmpty() && (probeResults.size() >= 512 || probeResultKeys + observation.weight > 32768)) {
                    var iterator = probeResults.entrySet().iterator();
                    probeResultKeys -= iterator.next().getValue().weight();
                    iterator.remove();
                }
                probeResults.put(key, new ProbeResult(delivered, Map.copyOf(observation.amounts),
                        Map.copyOf(observation.domains), observation.weight));
                probeResultKeys += observation.weight;
            }
        }
        return delivered;
    }

    private QuotaTrial allocateQuota(Process process, BigInteger times, State state, boolean needsHint) {
        recordTrial(process);
        var trial = new State(state);
        trial.quotaOwner = process;
        trial.quotaEntry = state;
        try {
            runAllocated(process, times, trial);
            trial.commitTo(state);
            return new QuotaTrial(true, times, null);
        } catch (Missing unavailable) {
            if (branchProfile != null) branchProfile.quotaFailed();
            boolean fixedSeed = unavailable.constantSeedFor == process;
            var budget = fixedSeed ? ZERO : process.metadata().single ? null : unavailable.funded;
            if (fixedSeed) constantSeedFailures++;
            if (!process.metadata().single && unavailable.probe != null) {
                // #236: these rejected transactions have no caller for an affordable-quantity hint.
                if (demandDrivenQuotaHints && !needsHint) {
                    unusedQuotaHints++;
                    return new QuotaTrial(false, null, unavailable.shortage);
                }
                var probe = unavailable.probe;
                var delivered = unavailable.shortage != null && unavailable.shortage.zeroProof() != null
                        && unavailable.shortage.key().equals(probe.input().key)
                        && probe.input().input instanceof PatternCompiler.DetachedInput exact
                        && probe.input().key.equals(exact.exactKey())
                        && acceptsZero(unavailable.shortage.zeroProof(), state)
                        ? ZERO : isolatedProbe(probe, state);
                if (delivered != null && probe.delivered() != null)
                    budget = delivered.max(probe.delivered()).divide(probe.perCraft());
            }
            return new QuotaTrial(false, budget, unavailable.shortage);
        } finally {
            branchEntries += trial.changedEntries();
        }
    }

    /** #236: over-approximate dependencies so a sibling's unindexed remainder cannot fund a bound. */
    private boolean returnFree(IPatternDetails rootPattern) {
        var cached = returnFreeRegions.get(rootPattern);
        if (cached != null) return cached;
        var pending = new ArrayDeque<IPatternDetails>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        pending.add(rootPattern);
        while (!pending.isEmpty()) {
            checkpoint();
            var pattern = pending.removeFirst();
            if (!seen.add(pattern)) continue;
            var known = returnFreeRegions.get(pattern);
            if (Boolean.TRUE.equals(known)) continue;
            if (Boolean.FALSE.equals(known) || !allocationPatterns.contains(pattern)) {
                returnFreeRegions.put(rootPattern, false);
                return false;
            }
            for (var slot : pattern.getInputs()) {
                var key = ((PatternCompiler.DetachedInput) slot).craftedKey();
                pending.addAll(knownProducers.computeIfAbsent(key, resolver));
                pending.addAll(secondaryProducers.getOrDefault(key, List.of()));
            }
        }
        seen.forEach(pattern -> returnFreeRegions.put(pattern, true));
        return true;
    }

    private BigInteger quotaBound(IPatternDetails pattern, State state,
            Map<AEKey, Optional<BigInteger>> memo, Set<AEKey> path, Set<AEKey> ancestors) {
        // #236: an unsupported return/fuzzy boundary is not proof of zero supply.
        if (!allocationPatterns.contains(pattern)
                || allocationReturns && (!scopedSupplyBounds || !returnFree(pattern))) return null;
        BigInteger bound = null;
        var feedback = processMetadata.computeIfAbsent(pattern, this::inspectProcess).single;
        if (feedback && !feedbackSupported(pattern, pattern.getPrimaryOutput().what())) return null;
        var needs = feedback ? feedbackNeeds(pattern) : Map.<AEKey, BigInteger>of();
        var outputs = feedback ? VmPatternOutputs.inspect(pattern).amounts() : Map.<AEKey, BigInteger>of();
        for (var slot : pattern.getInputs()) {
            checkpoint();
            var input = (PatternCompiler.DetachedInput) slot;
            if (input.exactKey() == null) return null;
            if (input.emittable()) continue;
            var available = supplyBound(input.exactKey(), state, memo, path, ancestors);
            if (available == null) continue;
            var amount = PatternCompiler.inputMultiplier(slot)
                    .multiply(BigInteger.valueOf(slot.getPossibleInputs()[0].amount()));
            if (feedback) amount = needs.get(input.exactKey());
            var recycled = outputs.getOrDefault(input.exactKey(), ZERO);
            BigInteger next;
            if (recycled.signum() == 0) next = available.divide(amount);
            else if (available.compareTo(amount) < 0) next = ZERO;
            else if (recycled.compareTo(amount) >= 0) continue;
            else next = ONE.add(available.subtract(amount).divide(amount.subtract(recycled)));
            bound = bound == null ? next : bound.min(next);
            if (bound.signum() == 0) break;
        }
        return bound;
    }

    private BigInteger supplyBound(AEKey key, State state,
            Map<AEKey, Optional<BigInteger>> memo, Set<AEKey> path, Set<AEKey> ancestors) {
        checkpoint();
        if (emitters.test(key)) return null;
        routeRead(key);
        orderGuard.exact(key);
        BigInteger total = state.rawAmount(key);
        // #236: reproduce the VM ancestor exclusion; a cycle cannot manufacture its own seed.
        if (ancestors.contains(key)) return total;
        if (path.contains(key)) return null;
        var cached = memo.get(key);
        if (cached != null) return cached.orElse(null);
        var producers = new ArrayList<>(knownProducers.computeIfAbsent(key, resolver));
        producers.addAll(secondaryProducers.getOrDefault(key, List.of()));
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        for (var pattern : producers) {
            if (!seen.add(pattern)) continue;
            if (conflictsWithAncestors(pattern, ancestors)) continue;
            path.add(key);
            try {
                var bound = quotaBound(pattern, state, memo, path, ancestors);
                if (bound == null) { total = null; break; }
                total = total.add(bound.multiply(VmPatternOutputs.inspect(pattern).amount(key)));
            } finally { path.remove(key); }
        }
        memo.put(key, Optional.ofNullable(total));
        return total;
    }

    private boolean conflictsWithAncestors(IPatternDetails pattern, Set<AEKey> ancestors) {
        var metadata = sharedDefinitions ? processMetadata.get(pattern) : null;
        if (metadata != null && metadata.conflictKeys != null)
            return !Collections.disjoint(metadata.conflictKeys, ancestors);
        for (var output : pattern.getOutputs()) if (ancestors.contains(output.what())) return true;
        for (var input : pattern.getInputs()) if (ancestors.contains(input.getPossibleInputs()[0].what())) return true;
        return false;
    }

    /** #236: finite wear is division, not a search for a repeated Damage-state cycle. */
    private BigInteger finiteToolBatch(Process process, BigInteger demand, State state) {
        return finiteToolBatch(process, demand, state, false);
    }

    private Map<Integer, PatternCompiler.FiniteUse> finiteToolShape(Process process) {
        var saved = finiteToolShapes.get(process.pattern);
        if (saved != null) { finiteShapeHits++; return saved; }
        if (!process.metadata().containers) return null;
        var tools = new LinkedHashMap<Integer, PatternCompiler.FiniteUse>();
        var toolFamilies = new HashSet<Family>();
        var inputs = process.inputs();
        for (int slot = 0; slot < inputs.size(); slot++) {
            var input = inputs.get(slot);
            if (!(input.input instanceof PatternCompiler.DetachedInput detached)) return null;
            for (var output : process.pattern.getOutputs())
                if (detached.mayConsumeOutput(output.what())) return null;
            if (!detached.mayReturnItems()) continue;
            if (!input.unit.equals(ONE) || !detached.exactMultiplier().equals(ONE)) return null;
            var families = new HashSet<Family>();
            for (var template : detached.getPossibleInputs()) {
                if (template.amount() != 1) return null;
                families.add(Family.of(template.what()));
            }
            if (!Collections.disjoint(toolFamilies, families)) return null;
            toolFamilies.addAll(families);
            var fresh = inputFacts.finite(detached, input.key);
            if (fresh == null || fresh.uses().signum() <= 0 || fresh.after(fresh.uses()) != null) return null;
            tools.put(slot, fresh);
        }
        if (tools.isEmpty()) return null;
        for (int slot = 0; slot < inputs.size(); slot++) if (!tools.containsKey(slot))
            for (var template : inputs.get(slot).input.getPossibleInputs())
                if (toolFamilies.contains(Family.of(template.what()))) return null;
        var result = Collections.unmodifiableMap(tools);
        if (finiteToolShapes.size() < 512) finiteToolShapes.put(process.pattern, result);
        return result;
    }

    private BigInteger finiteToolBatch(Process process, BigInteger demand, State state, boolean partialHint) {
        var tools = finiteToolShape(process);
        if (tools == null) return ZERO;
        var inputs = process.inputs();
        var times = PatternCompiler.ceilDiv(demand, process.output(process.parent.key));
        var ceiling = optimisticLimit(process, state, times);
        if (ceiling != null && ceiling.compareTo(times) < 0) {
            quantityBoundReductions++;
            if (!partialHint) {
                if (branchProfile != null) branchProfile.rejected(process.parent.key, "finite-partial-bound");
                return ZERO;
            }
            times = ceiling;
            if (times.signum() == 0) return ZERO;
        }
        final var batchTimes = times;
        // #236: failure aborts the entire isolated projection; ordinary trials still need rollback.
        boolean projected = preferredProjection && simulate;
        var trial = projected ? state : new State(state);
        try {
            // Whole-order policy reserves ingredients before allocating this batch's tool uses.
            for (int slot = 0; slot < inputs.size(); slot++) if (!tools.containsKey(slot)) {
                var input = inputs.get(slot);
                var multiplier = ((PatternCompiler.DetachedInput) input.input).exactMultiplier();
                try { request(input, times.multiply(multiplier), trial, null); }
                catch (Missing absent) {
                    if (branchProfile != null) branchProfile.rejected(process.parent.key, "finite-ingredient");
                    if (partialHint && absent.funded != null && absent.shortage != null
                            && input.key.equals(absent.shortage.key())) {
                        var proposed = absent.funded.divide(multiplier);
                        if (proposed.signum() > 0 && proposed.compareTo(times) < 0) {
                            // #236: a proposal only; validate from the unchanged entry before committing.
                            finiteQuotaRetries++;
                            return finiteToolBatch(process, proposed.multiply(process.output(process.parent.key)), state, false);
                        }
                    }
                    return ZERO;
                }
            }
            var returns = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : tools.entrySet()) {
                var available = allocateToolUses(inputs.get(entry.getKey()), entry.getValue(), times, trial, returns);
                if (available == null) {
                    if (branchProfile != null) branchProfile.rejected(process.parent.key, "finite-tool-domain");
                    return ZERO;
                }
                if (available.compareTo(times) < 0) {
                    if (partialHint && available.signum() > 0) {
                        // #236: discarded tool acquisition proposes a prefix; revalidate all inputs from entry.
                        finiteQuotaRetries++;
                        return finiteToolBatch(process, available.multiply(process.output(process.parent.key)), state, false);
                    }
                    return ZERO;
                }
            }
            returns.forEach(trial::insert);
            var taken = ZERO;
            for (var output : process.outputs().amounts().entrySet()) {
                var produced = scaled(output.getValue(), batchTimes);
                if (output.getKey().equals(process.parent.key))
                    taken = trial.produceAndExtract(output.getKey(), produced, demand);
                else trial.insert(output.getKey(), produced);
            }
            trial.patterns.merge(process.pattern, times, BigInteger::add);
            trial.bytes = trial.bytes.add(new VmByteCost(times, ONE));
            // #236: do not reuse this non-affine allocation as an ordered trace or cross-order certificate.
            orderGuard.unsupported();
            routeCaptures.forEach(capture -> capture.supported = false);
            traces.forEach(trace -> trace.finiteAllocationUsed = true);
            if (!projected) trial.commitTo(state);
            return taken;
        } catch (Missing unsupported) {
            if (branchProfile != null) branchProfile.rejected(process.parent.key, "finite-tool-supply");
            return ZERO;
        }
    }

    private BigInteger allocateToolUses(Node tool, PatternCompiler.FiniteUse fresh, BigInteger times,
            State trial, Map<AEKey, BigInteger> returns) {
        var detached = (PatternCompiler.DetachedInput) tool.input;
        var candidates = new LinkedHashSet<AEKey>();
        var domain = toolDomains.get(detached);
        if (domain == null) {
            var templates = new LinkedHashMap<Family, AEKey>();
            for (var template : detached.getPossibleInputs()) templates.putIfAbsent(Family.of(template.what()), template.what());
            var keys = List.copyOf(templates.values());
            domain = new ToolDomain(keys, supplyGraph == null ? null : supplyGraph.knownFamilies(keys), new LinkedHashMap<>());
            if (toolDomains.size() < 512) toolDomains.put(detached, domain);
        }
        var templates = domain.templates;
        if (projectedInputs && domain.families != null && !orderGuard.supported() && traces.isEmpty()
                && activeProbe == null && routeCaptures.isEmpty()) {
            var present = (BitSet) trial.supplySeeds.clone();
            present.and(domain.families);
            templates = domain.positive.get(present);
            if (templates == null) {
                var selected = new ArrayList<AEKey>();
                for (var template : domain.templates)
                    if (!supplyGraph.knownAbsent(trial.supplySeeds, template)) selected.add(template);
                templates = List.copyOf(selected);
                if (domain.positive.size() >= 16) domain.positive.remove(domain.positive.keySet().iterator().next());
                domain.positive.put(present, templates);
            }
            if (templates.isEmpty()) emptyToolDomains++;
        }
        for (var template : templates)
            for (var entry : trial.fuzzy(template)) candidates.add(entry.getKey());
        var remaining = times;
        var broken = ZERO;
        for (var key : candidates) {
            if (remaining.signum() == 0) break;
            var available = trial.read(key, ONE);
            if (available.signum() == 0) continue;
            if (!inputFacts.ready(detached, key)) {
                // #236: observe only the demanded stocked state through the host's access window.
                detached.prepareCandidates(List.of(key));
                if (!inputFacts.ready(detached, key)) {
                    if (preferredProjection) throw new ProjectionUnsupported("finite-unprepared:" + diagnosticKey(key));
                    return null;
                }
            }
            if (!inputFacts.valid(detached, key)) continue;
            var law = inputFacts.finite(detached, key);
            if (law == null || law.uses().signum() <= 0 || law.after(law.uses()) != null) {
                if (preferredProjection) throw new ProjectionUnsupported("finite-law:" + diagnosticKey(key));
                return null;
            }
            var applied = remaining.min(available.multiply(law.uses()));
            var parts = applied.divideAndRemainder(law.uses());
            var count = parts[1].signum() == 0 ? parts[0] : parts[0].add(ONE);
            trial.extract(key, count);
            broken = broken.add(parts[0]);
            if (parts[1].signum() > 0) returns.merge(inputFacts.after(detached, law, parts[1]), ONE, BigInteger::add);
            remaining = remaining.subtract(applied);
        }
        var freshCount = ZERO;
        if (remaining.signum() > 0) {
            var parts = remaining.divideAndRemainder(fresh.uses());
            freshCount = parts[1].signum() == 0 ? parts[0] : parts[0].add(ONE);
            // No accepted stock remains. Normal request still owns source selection and shortages.
            try { request(tool, freshCount, trial, null); }
            catch (Missing absent) {
                if (absent.funded == null || absent.shortage == null
                        || !tool.key.equals(absent.shortage.key())) throw absent;
                return times.subtract(remaining).add(absent.funded.multiply(fresh.uses())).min(times);
            }
            broken = broken.add(parts[0]);
            if (parts[1].signum() > 0) returns.merge(inputFacts.after(detached, fresh, parts[1]), ONE, BigInteger::add);
        }
        trial.charge(tool.key, times.subtract(freshCount));
        trial.charge(tool.key, times.subtract(broken));
        return times;
    }

    private BigInteger craft(Node node, BigInteger demand, State state, List<Process> choices) {
        node.lastZeroProof = null;
        if (preferredProjection) {
            if (choices.isEmpty()) return demand;
            var process = choices.get(0);
            var best = ZERO;
            Process unbounded = null;
            // #236: a sole source has no selection decision; its exact execution still accounts for shortages.
            if (choices.size() > 1) for (var candidate : choices) {
                if (candidate.metadata().single) continue;
                BigInteger ceiling;
                simulate = false;
                try { ceiling = optimisticLimit(candidate, state,
                        PatternCompiler.ceilDiv(demand, candidate.output(node.key))); }
                finally { simulate = true; }
                if (ceiling == null) { if (unbounded == null) unbounded = candidate; continue; }
                var possible = ceiling.multiply(candidate.output(node.key)).min(demand);
                if (possible.compareTo(best) > 0) { process = candidate; best = possible; }
                if (possible.equals(demand)) break;
            }
            if (best.signum() == 0 && unbounded != null) process = unbounded;
            var metadata = process.metadata();
            if (!metadata.projectionPrepared) {
                for (var input : process.pattern.getInputs())
                    if (!(input instanceof PatternCompiler.DetachedInput detached)
                            || !inputFacts.ready(detached, detached.craftedKey()) || detached.emittable())
                        throw new ProjectionUnsupported("prepared-input");
                metadata.projectionPrepared = true;
            }
            if (process.metadata().containers) {
                if (finiteToolShape(process) == null) throw new ProjectionUnsupported("tool:" + diagnosticKey(node.key));
                var taken = finiteToolBatch(process, demand, state);
                if (taken.signum() == 0) throw new ProjectionUnsupported("finite-zero:" + diagnosticKey(node.key));
                return demand.subtract(taken);
            }
            if (process.metadata().single) {
                if (!feedbackSupported(process.pattern, node.key)) throw new ProjectionUnsupported("feedback:" + diagnosticKey(node.key));
                runFeedbackAllocated(process, allocationTimes(process, demand), state);
            } else return demand.subtract(run(process, PatternCompiler.ceilDiv(demand, process.output(node.key)), state, demand));
            return demand.subtract(state.extract(node.key, demand));
        }
        var linear = linearDemand(node, demand, state, choices);
        if (linear != null) return linear;
        if (!simulate && !choices.isEmpty() && supplyGraph != null && traces.isEmpty() && activeProbe == null
                && unreachable(node.key, state)) {
            // #236: skip only proven unfunded trials; the caller still collects the full shortage plan.
            orderGuard.unsupported();
            unreachableSupplies++;
            return demand;
        }
        // #236: mixed-return catalogs otherwise expand the same unfunded prefix for every quota.
        // The proof records failed capacity separately; it never commits a speculative item ledger.
        if (shortagePlanning && orderAllocation && allocationReturns && !simulate && (traceShortageProofs || traces.isEmpty())
                && choices.size() == node.processes().size()) {
            var proof = zeroCandidate(node);
            if (proof != null && acceptsZero(proof, state)) {
                node.zeroCapacity = node.lastZeroProof = proof;
                multiple |= proof.multiple;
                provenZeroRequests++;
                return demand;
            }
        }
        if (finiteAllocation && choices.size() == 1) {
            var taken = finiteToolBatch(choices.get(0), demand, state);
            if (taken.signum() > 0) return demand.subtract(taken);
        }
        if (orderAllocation) {
            if (choices.stream().allMatch(process -> allocationPatterns.contains(process.pattern)
                    && (!process.metadata().single || feedbackSupported(process.pattern, node.key))))
                return allocate(node, demand, state, choices);
            // #236: mixed nodes dispatch eligible producers individually below.
        }
        boolean alternatives = node.processes().size() > 1;
        for (var process : choices) {
            if (orderAllocation && traces.isEmpty() && allocationPatterns.contains(process.pattern)
                    && (!process.metadata().single || feedbackSupported(process.pattern, node.key))) {
                demand = allocate(node, demand, state, List.of(process));
                if (demand.signum() == 0) return ZERO;
                continue;
            }
            if (orderAllocation && finiteAllocation && choices.size() > 1) {
                var taken = finiteToolBatch(process, demand, state, true);
                demand = demand.subtract(taken);
                if (demand.signum() == 0) return ZERO;
            }
            if (optimizedGuards && !simulate && alternatives && certifiedFailure(process, state)) {
                recordTrial(process);
                certifiedFailures++;
                continue;
            }
            if (optimizedGuards && simulate && alternatives && choices.size() == 1
                    && compiledMissing(process, demand, state).signum() > 0) return ZERO;
            if (optimizedGuards && (alternatives || process.metadata().single)) {
                while (demand.signum() > 0) {
                    var taken = compiledBatch(process, demand, state);
                    boolean formula = taken.signum() == 0;
                    if (formula) taken = compiledFormula(process, demand, state);
                    if (taken.signum() == 0) break;
                    demand = demand.subtract(taken);
                    if (demand.signum() == 0) return ZERO;
                    // #236: finite formula prefixes must not starve the ordered repetition detector.
                    if (formula) break;
                }
            }
            var metadata = process.metadata();
            var output = process.output(node.key);
            while (optimizedGuards && alternatives && !metadata.single && demand.signum() > 0) {
                var program = demandCompiler.get(process.pattern);
                if (program == null) break;
                program = demandCompiler.stocked(program, key -> state.compareAmount(key, ONE).signum() > 0);
                if (program == null) break;
                var times = PatternCompiler.ceilDiv(demand, output);
                var batch = fundedAggregatePrefix(process, program, times, state);
                if (batch.signum() == 0) break;
                boolean transaction = alternatives;
                var trial = !transaction ? state : sparseBranches ? new State(state) : state.copy();
                if (transaction) recordTrial(process);
                applyAggregate(process, program, batch, trial);
                var taken = trial.extract(node.key, demand);
                if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                if (transaction) {
                    branchEntries += sparseBranches ? trial.changedEntries() : trial.entries();
                    if (sparseBranches) trial.commitTo(state);
                    else state.adopt(trial);
                }
                demand = demand.subtract(taken);
                if (demand.signum() == 0) return ZERO;
                // A depleted stocked boundary can still be crafted by this same producer.
            }
            // #217: bulk-only producers need no local replay snapshot. Enclosing traces still observe all reads.
            if (!alternatives && !metadata.single) {
                while (demand.signum() > 0) {
                    checkpoint();
                    run(process, PatternCompiler.ceilDiv(demand, output), state);
                    var taken = state.extract(node.key, demand);
                    if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                    demand = demand.subtract(taken);
                }
                return ZERO;
            }
            // A one-shot process cannot replay; enclosing traces still observe reads and failed trials.
            var window = !optimizedGuards || demand.compareTo(output) > 0 ? new Trace(state) : null;
            if (window != null) traces.add(window);
            var windowOutput = ZERO;
            int iterations = 0, windowSize = 64;
            try {
            while (demand.signum() > 0) {
                checkpoint();
                boolean transaction = alternatives;
                if (transaction) recordTrial(process);
                var target = !transaction ? state : sparseBranches ? new State(state) : state.copy();
                var times = transaction || metadata.single ? ONE : PatternCompiler.ceilDiv(demand, output);
                BigInteger taken;
                try {
                    var program = optimizedGuards && transaction && !metadata.single ? demandCompiler.get(process.pattern) : null;
                    if (program != null) program = demandCompiler.stocked(program, key -> target.compareAmount(key, ONE).signum() > 0);
                    var bulk = program == null ? ZERO : fundedAggregatePrefix(process, program,
                            PatternCompiler.ceilDiv(demand, output), target);
                    if (bulk.signum() > 0) applyAggregate(process, program, bulk, target);
                    else run(process, times, target);
                    taken = target.extract(node.key, demand);
                    if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                } catch (Missing failure) {
                    if (!transaction) throw failure;
                    break;
                } finally {
                    if (transaction) branchEntries += sparseBranches ? target.changedEntries() : state.entries();
                }
                if (transaction) {
                    if (sparseBranches) target.commitTo(state);
                    else state.adopt(target);
                }
                demand = demand.subtract(taken);
                if (window == null) continue;
                windowOutput = windowOutput.add(taken);
                // A multi-instruction period also covers changing tool damage and returned variants.
                var repeats = ZERO;
                boolean sameKeys;
                if (optimizedGuards) sameKeys = window.before.sameStockKeys(state);
                else {
                    stockKeyScans++;
                    sameKeys = window.before.stock.keySet().equals(state.stock.keySet());
                    window.captureMinimum(state);
                }
                if (!sameKeys && branchProfile != null) branchProfile.rejected(node.key, "stock-domain");
                traces.remove(traces.size() - 1);
                try {
                    if (sameKeys && demand.compareTo(windowOutput) >= 0) {
                        repeats = window.safeRepeats(state, demand.divide(windowOutput));
                        if (repeats.signum() > 0) {
                            if (optimizedGuards) window.captureMinimum(state);
                            window.replay(state, repeats);
                        }
                    }
                } finally { traces.add(window); }
                demand = demand.subtract(windowOutput.multiply(repeats));
                if (demand.signum() > 0 && (repeats.signum() > 0 || !sameKeys
                        || ++iterations >= windowSize)) {
                    window.finish(state); traces.remove(traces.size() - 1);
                    window = null;
                    // #236: after stock depletion, the tail may now be a certifiable region.
                    if (optimizedGuards) {
                        var compiled = simulate && alternatives && choices.size() == 1
                                ? compiledMissing(process, demand, state) : ZERO;
                        if (compiled.signum() == 0) compiled = compiledBatch(process, demand, state);
                        if (compiled.signum() == 0) compiled = compiledFormula(process, demand, state);
                        if (compiled.signum() > 0) compiledReentries++;
                        demand = demand.subtract(compiled);
                        if (demand.signum() == 0) return ZERO;
                    }
                    window = new Trace(state); traces.add(window);
                    windowOutput = ZERO; iterations = 0;
                    // #218: grow only an exhausted search, not successful replay or a changed key domain.
                    windowSize = repeats.signum() > 0 || !sameKeys ? 64 : Math.min(1_048_576, windowSize * 2);
                }
            }
            } finally {
                if (window != null) { window.finish(state); traces.remove(traces.size() - 1); }
            }
            if (demand.signum() == 0) return ZERO;
        }
        return demand;
    }

    private BigInteger linearDemand(Node node, BigInteger demand, State state, List<Process> choices) {
        if (!roundedLinearDemands || !orderAllocation || !completeStock || choices.isEmpty() || !node.unit.equals(ONE)
                || activeProbe != null || !routeCaptures.isEmpty()) return null;
        var saved = linearPrograms.get(node.key);
        if (saved == null) {
            saved = Optional.ofNullable(VmLinearDemand.compile(node.key, knownProducers, this::checkpoint));
            if (linearPrograms.size() < 512) linearPrograms.put(node.key, saved);
        }
        var program = saved.orElse(null);
        if (program == null || choices.get(0).pattern != program.steps.get(0).sources().get(0).pattern()) return null;
        // #236: equivalent templates do not imply a closed fuzzy domain; observe and check current variants.
        for (var key : program.fuzzy) for (var entry : state.fuzzy(key))
            if (!key.equals(entry.getKey()) && state.rawAmount(entry.getKey()).signum() > 0) return null;
        for (var ancestor = node.ancestor; ancestor != null; ancestor = ancestor.ancestor)
            if (program.dependencies.contains(ancestor.key)) return null;
        orderGuard.unsupported();
        linearBlocks++;
        if (simulate && traces.isEmpty() && emptyLinearSupply(program, state)) {
            applyEmptyLinear(program, node, demand, state);
            return ZERO;
        }
        var funded = simulate ? demand : demand.min(linearSupply(program, 0, state));
        if (funded.signum() > 0) linearCraft(program, 0, node, funded, state, simulate);
        return demand.subtract(funded);
    }

    private boolean emptyLinearSupply(VmLinearDemand program, State state) {
        if (supplyGraph == null) return false;
        var mask = linearFamilies.computeIfAbsent(program,
                ignored -> Optional.ofNullable(supplyGraph.knownFamilies(program.dependencies)));
        return mask.isPresent() && !mask.get().intersects(state.supplySeeds);
    }

    private void applyEmptyLinear(VmLinearDemand program, Node node, BigInteger demand, State state) {
        var key = new LinearRequest(program, demand);
        var effect = linearEffects.get(key);
        if (effect == null) {
            effect = program.emptyEffect(demand, this::checkpoint);
            while (!linearEffects.isEmpty() && (linearEffects.size() >= 512
                    || linearEffectWeight + program.steps.size() > 4096)) {
                var oldest = linearEffects.keySet().iterator();
                linearEffectWeight -= oldest.next().program.steps.size(); oldest.remove();
            }
            linearEffects.put(key, effect); linearEffectWeight += program.steps.size();
        } else linearEffectHits++;
        if (sharedLinearCapacity && (node.zeroCapacity == null || node.zeroCapacity == linearCapacities.get(program))) {
            if (node.zeroCapacity == null) node.zeroCapacity = linearCapacities.computeIfAbsent(program, this::linearCapacity);
            multiple |= node.zeroCapacity.multiple;
            for (int i = 0; i < program.steps.size(); i++) {
                checkpoint(); linearSteps++;
                if (state.extract(program.steps.get(i).input(), effect.inputs().get(i)).signum() != 0)
                    throw new IllegalStateException("Empty linear effect encountered stock");
            }
            linearSteps++;
            merge(state.missing, program.steps.get(program.steps.size() - 1).input(), effect.missing());
            for (int i = program.steps.size() - 1; i >= 0; i--) {
                var step = program.steps.get(i);
                if (effect.surplus().get(i).signum() > 0) state.insert(step.key(), effect.surplus().get(i));
                state.patterns.merge(step.sources().get(0).pattern(), effect.times().get(i), BigInteger::add);
            }
            state.bytes = state.bytes.add(effect.bytes());
            return;
        }
        var path = new ArrayList<Process>(program.steps.size());
        for (int i = 0; i < program.steps.size(); i++) {
            checkpoint(); linearSteps++;
            var process = node.processes().get(0);
            path.add(process);
            node = process.inputs().get(0);
            if (state.extract(node.key, effect.inputs().get(i)).signum() != 0)
                throw new IllegalStateException("Empty linear effect encountered stock");
        }
        linearSteps++;
        merge(state.missing, node.key, effect.missing());
        for (int i = path.size() - 1; i >= 0; i--) {
            var process = path.get(i);
            if (effect.surplus().get(i).signum() > 0) state.insert(process.parent.key, effect.surplus().get(i));
            state.patterns.merge(process.pattern, effect.times().get(i), BigInteger::add);
        }
        state.bytes = state.bytes.add(effect.bytes());
    }

    private VmZeroSupply.Node linearCapacity(VmLinearDemand program) {
        VmZeroSupply.Node child = null;
        for (int i = program.steps.size() - 1; i >= 0; i--) {
            checkpoint();
            var step = program.steps.get(i);
            var unvisited = new VmZeroSupply.Node(step.input(), ONE, List.of(), new HashMap<>(), Set.of());
            var processes = new ArrayList<VmZeroSupply.Process>();
            for (int j = 0; j < step.sources().size(); j++)
                processes.add(new VmZeroSupply.Process(step.sources().get(j).pattern(), 1,
                        j == 0 && child != null ? child : unvisited));
            child = new VmZeroSupply.Node(step.key(), ONE, processes, new HashMap<>(), Set.of());
        }
        return child;
    }

    private BigInteger linearSupply(VmLinearDemand program, int start, State state) {
        if (traces.isEmpty() && emptyLinearSupply(program, state)) return ZERO;
        var steps = program.steps;
        var available = linearStock(steps.get(steps.size() - 1).input(), state);
        for (int i = steps.size() - 1; i >= start; i--) {
            checkpoint();
            var step = steps.get(i);
            var output = linearStock(step.key(), state);
            for (var source : step.sources()) {
                var times = divided(available, source.consumed());
                output = output.add(scaled(times, source.output()));
                available = available.subtract(scaled(times, source.consumed()));
            }
            available = output;
        }
        return available;
    }

    private BigInteger linearStock(AEKey key, State state) {
        var available = state.unobservedAmount(key);
        // #236: quota arithmetic must not escape enclosing replay's stock domain.
        if (!traces.isEmpty()) state.read(key, available.add(ONE));
        return available;
    }

    private void linearCraft(VmLinearDemand program, int index, Node node, BigInteger demand, State state, boolean shortage) {
        checkpoint(); linearSteps++;
        if (index == program.steps.size()) {
            if (!shortage) throw new IllegalStateException("Funded linear demand exceeded its certified supply");
            merge(node.emit ? state.emitted : state.missing, node.key, demand);
            return;
        }
        var step = program.steps.get(index);
        var processes = node.processes();
        if (processes.size() != step.sources().size()) throw new IllegalStateException("Linear source path changed");
        if (!shortage || step.sources().size() > 1) {
            for (int i = 0; i < step.sources().size(); i++) {
                var source = step.sources().get(i);
                var bound = divided(linearSupply(program, index + 1, state), source.consumed());
                var times = PatternCompiler.ceilDiv(demand, source.output()).min(bound);
                if (times.signum() == 0) continue;
                demand = demand.subtract(linearRecipe(program, index, processes.get(i), source, times, state, false, demand));
                if (demand.signum() == 0) return;
            }
            if (!shortage) throw new IllegalStateException("Funded linear source did not fulfill demand");
        }
        var source = step.sources().get(0);
        var taken = linearRecipe(program, index, processes.get(0), source,
                PatternCompiler.ceilDiv(demand, source.output()), state, true, demand);
        if (!taken.equals(demand)) throw new IllegalStateException("Incomplete linear simulation");
    }

    private BigInteger linearRecipe(VmLinearDemand program, int index, Process process, VmLinearDemand.Source source,
            BigInteger times, State state, boolean shortage, BigInteger requested) {
        var input = process.inputs().get(0);
        var amount = scaled(times, source.consumed());
        state.charge(input.key, amount);
        var remaining = amount.subtract(state.extract(input.key, amount));
        if (remaining.signum() > 0) linearCraft(program, index + 1, input, remaining, state, shortage);
        state.patterns.merge(process.pattern, times, BigInteger::add);
        state.bytes = state.bytes.add(new VmByteCost(times, ONE));
        return state.produceAndExtract(process.parent.key, scaled(times, source.output()), requested);
    }

    private boolean unreachable(AEKey key, State state) {
        if (supplyGraph.contains(state.supplySeeds, key)) { seededSupplyHits++; return false; }
        var query = supplyGraph.query(key, state.supplySeeds, this::checkpoint);
        for (var cut : supplyCuts.getOrDefault(query.family(), List.of())) {
            if (!cut.intersects(query.seeds())) { supplyCutHits++; return true; }
        }
        var cached = supplyClosures.get(query);
        if (cached == null) {
            var cut = supplyGraph.blockedCut(query, this::checkpoint);
            cached = cut != null;
            int words = cut == null ? 0 : 1 + ((cut.length() + 63) >>> 6);
            if (cut != null && supplyCutCount < 512) {
                var cuts = supplyCuts.computeIfAbsent(query.family(), ignored -> new ArrayList<>());
                if (cuts.size() < 8) { cuts.add(cut); supplyCutCount++; supplyCutWords += words; }
            }
            supplyClosuresBuilt++;
            if (supplyClosures.size() >= 512) supplyClosures.remove(supplyClosures.keySet().iterator().next());
            supplyClosures.put(query, cached);
        }
        return cached;
    }

    private boolean acceptsZero(VmZeroSupply.Node proof, State state) {
        return acceptsZero(proof, state, false);
    }
    private VmZeroSupply.Node zeroCandidate(Node node) {
        if (!node.zeroChecked) {
            node.zeroCandidate = zeroSupply.get(node.key, node.ancestor == null ? Set.of() : node.ancestor.ancestors());
            node.zeroChecked = true;
        }
        return node.zeroCandidate;
    }
    private boolean acceptsZero(VmZeroSupply.Node proof, State state, boolean producersOnly) {
        var guards = producersOnly ? proof.producerGuards : proof.guards;
        for (var entry : guards.entrySet()) {
            checkpoint();
            if (state.rawAmount(entry.getKey()).compareTo(entry.getValue()) >= 0) return false;
        }
        for (var template : proof.domains) {
            checkpoint();
            for (var entry : state.fuzzy(template))
                if (state.rawAmount(entry.getKey()).signum() != 0) return false;
        }
        for (var key : guards.keySet()) {
            // #236: an enclosing replay must retain the failed-prefix stock boundary.
            if (!traces.isEmpty()) state.read(key, guards.get(key), true);
            routeRead(key);
            orderGuard.exact(key);
        }
        return true;
    }

    private boolean certifiedFailure(Process process, State state) {
        try {
            if (process.failureCuts == null) process.failureCuts = VmMissingProgram.failureCuts(process.pattern,
                    key -> knownProducers.computeIfAbsent(key, resolver), this::checkpoint);
            if (process.failureCuts.isEmpty()) return false;
            if (!process.fullyVisited && ((process.visitChecks++ & 63) != 0
                    || !fullyVisited(process, new int[]{8192}))) return false;
            for (var cut : process.failureCuts) {
                var available = state.rawAmount(cut.key());
                if (available.compareTo(cut.amount()) >= 0) continue;
                // #236: failed branches cannot add keys or quantities to their parent's stock journal.
                routeRead(cut.key());
                orderGuard.read(cut.key(), available, cut.amount(), true);
                if (coalescedTraceReads) {
                    if (!traces.isEmpty()) traces.get(traces.size() - 1).read(cut.key(), available, cut.amount(), true);
                } else for (var trace : traces) trace.read(cut.key(), available, cut.amount(), true);
                return true;
            }
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) { return false; }
        return false;
    }

    private boolean fullyVisited(Process process, int[] budget) {
        if (process.fullyVisited) return true;
        checkpoint();
        if (--budget[0] < 0) return false;
        var slots = process.pattern.getInputs();
        for (int i = 0; i < slots.length; i++) {
            if (!(slots[i] instanceof PatternCompiler.DetachedInput input)) return false;
            if (input.emittable()) continue;
            var definitions = knownProducers.computeIfAbsent(input.craftedKey(), resolver);
            if (definitions.stream().allMatch(process.parent::conflicts)) continue;
            if (process.inputs == null) return false;
            var node = process.inputs.get(i);
            if (node.processes == null) return false;
            for (var child : node.processes) if (!fullyVisited(child, budget)) return false;
        }
        process.fullyVisited = true;
        return true;
    }

    private boolean missingRegion(Process process, State state) {
        try {
            if (!process.missingProgramChecked) {
                var ancestors = new ArrayList<AEKey>();
                for (var node = process.parent.ancestor; node != null; node = node.ancestor) ancestors.add(node.key);
                process.missingProgram = VmMissingProgram.compile(process.pattern, process.parent.key, ancestors,
                        key -> knownProducers.computeIfAbsent(key, resolver), this::checkpoint, true);
                process.missingProgramChecked = true;
            }
            if (process.missingProgram == null) return missingDeclined(process, "structure");
            if (process.missingProgram.sharedIntermediates && !selectedVisited(process, null, new int[]{8192}))
                return missingDeclined(process, "unvisited");
            var bounds = process.missingProgram.certify(state::rawAmount, this::checkpoint);
            if (bounds == null) return missingDeclined(process, "variable-source");
            for (var entry : bounds.entrySet()) {
                state.read(entry.getKey(), entry.getValue().add(ONE), true);
                orderGuard.exact(entry.getKey());
            }
            return true;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean missingDeclined(Process process, String reason) {
        if (branchProfile != null) branchProfile.rejected(process.parent.key, "missing-" + reason);
        return false;
    }

    private BigInteger compiledMissing(Process process, BigInteger demand, State state) {
        if (demand.compareTo(process.output(process.parent.key)) <= 0
                || process.metadata().single || !missingRegion(process, state)) return ZERO;
        // #236: expand fixed-route demand with exact ceil division; never multiply a one-craft snapshot.
        run(process, PatternCompiler.ceilDiv(demand, process.output(process.parent.key)), state);
        var taken = state.extract(process.parent.key, demand);
        if (!taken.equals(demand)) throw new IllegalStateException("Certified missing region did not cover demand");
        missingRegionBlocks++;
        return taken;
    }

    private boolean selectedVisited(Process process, CapacityShape inherited, int[] budget) {
        checkpoint();
        if (--budget[0] < 0) return false;
        var shape = CapacityShape.union(process.aggregatedShape, inherited);
        if (process.inputs == null) return shape != null && selectedShapeVisited(shape, process.parent, budget);
        for (int i = 0; i < process.inputs.size(); i++) {
            var node = process.inputs.get(i);
            var input = (PatternCompiler.DetachedInput) node.input;
            if (input.emittable()) continue;
            var definitions = knownProducers.computeIfAbsent(node.key, resolver);
            if (definitions.stream().allMatch(process.parent::conflicts)) continue;
            var childShape = shape == null ? null : shape.children.get(i);
            if (node.processes == null) {
                if (childShape == null || !selectedShapeVisited(childShape, node, budget)) return false;
            } else if (node.processes.isEmpty() || !selectedVisited(node.processes.get(0), childShape, budget)) return false;
        }
        return true;
    }

    private boolean selectedShapeVisited(CapacityShape shape, Node parent, int[] budget) {
        checkpoint();
        if (--budget[0] < 0) return false;
        var slots = shape.pattern.getInputs();
        for (int i = 0; i < slots.length; i++) {
            var input = (PatternCompiler.DetachedInput) slots[i];
            if (input.emittable()) continue;
            var definitions = knownProducers.computeIfAbsent(input.craftedKey(), resolver).stream()
                    .filter(p -> !parent.conflicts(p)).toList();
            if (definitions.isEmpty()) continue;
            var child = shape.children.get(i);
            if (child == null || child.pattern != definitions.get(0)) return false;
            var node = new Node(input.craftedKey(), BigInteger.ONE, input, parent, parent.path);
            if (!selectedShapeVisited(child, node, budget)) return false;
        }
        return true;
    }

    private BigInteger compiledBatch(Process process, BigInteger demand, State state) {
        if (demand.compareTo(process.output(process.parent.key)) <= 0) return ZERO;
        VmBatchProgram program;
        try {
            program = batchCompiler.compile(process.pattern, process.parent.key,
                    key -> knownProducers.computeIfAbsent(key, resolver),
                    key -> state.amount(key).signum() > 0, this::checkpoint);
        }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) {
            // #236: speculative child inspection cannot expose an unused branch's exception.
            // Ordered execution below still raises it if that branch is actually demanded.
            return ZERO;
        }
        if (program == null) {
            profile(process, VmBranchProfile.REJECTED);
            return ZERO;
        }
        for (var ancestor = process.parent.ancestor; ancestor != null; ancestor = ancestor.ancestor)
            if (program.intermediates.contains(ancestor.key) || program.transfers.containsKey(ancestor.key)) return ZERO;
        var times = PatternCompiler.ceilDiv(demand, program.output);
        if (times.compareTo(ONE) <= 0) return ZERO;
        // Manufacturing is a boundary, not an assumed shortage. Stop before it is needed.
        for (var entry : program.transfers.entrySet()) {
            checkpoint();
            var key = entry.getKey();
            var transfer = entry.getValue();
            if (transfer.consumed().signum() == 0) continue;
            var available = state.amount(key);
            var funded = transfer.funded(program.usableStock(key, available), times);
            if (funded.equals(times) || simulate && transfer.emit()) continue;
            boolean missingLeaf = false;
            if (simulate) {
                var definitions = knownProducers.computeIfAbsent(key, resolver);
                missingLeaf = definitions.stream().allMatch(process.parent::conflicts);
            }
            if (!missingLeaf) times = times.min(funded);
            if (times.compareTo(ONE) <= 0) {
                profile(process, VmBranchProfile.BOUNDARY);
                return ZERO;
            }
        }
        var taken = demand.min(times.multiply(program.output));
        for (var key : program.intermediates) {
            state.read(key, ONE);
            orderGuard.exact(key);
        }
        // #236: do not infer callbacks from a net delta; this program has certified fixed inputs only.
        for (var entry : program.transfers.entrySet()) {
            checkpoint();
            var key = entry.getKey();
            var transfer = entry.getValue();
            var available = state.amount(key);
            if (transfer.consumed().signum() == 0) {
                if (transfer.produced().signum() > 0) state.insert(key, transfer.produced().multiply(times));
                continue;
            }
            var required = transfer.required(times);
            state.read(key, required);
            orderGuard.exact(key);
            var usable = program.usableStock(key, available);
            var residue = available.subtract(usable);
            var missing = transfer.shortage(usable, times);
            var low = residue.add(usable.subtract(required).max(ZERO));
            state.extract(key, available.subtract(low));
            state.insert(key, residue.add(transfer.after(usable, times)).subtract(low));
            if (missing.signum() > 0) {
                merge(transfer.emit() ? state.emitted : state.missing, key, missing);
                if (!transfer.emit()) for (var input : process.inputs())
                    if (input.key.equals(key)) input.processes();
            }
        }
        var surplus = times.multiply(program.output).subtract(taken);
        if (surplus.signum() > 0) state.insert(process.parent.key, surplus);
        final var repetitions = times;
        program.crafts.forEach((pattern, count) -> merge(state.patterns, pattern, count.multiply(repetitions)));
        state.bytes = state.bytes.add(program.bytes.multiply(times));
        process.aggregatedShape = CapacityShape.union(process.aggregatedShape,
                batchCapacityShape(program.shape, new IdentityHashMap<>()));
        aggregatedBlocks++;
        profile(process, VmBranchProfile.APPLIED);
        return taken;
    }

    private BigInteger compiledFormula(Process process, BigInteger demand, State state) {
        if (!formulaCompilation) return ZERO;
        // #236: certified finite-use arithmetic already owns this shape; ordinary fallback is still complete.
        if (orderAllocation && finiteFormulaAdmission && finiteToolShapes.containsKey(process.pattern)) {
            finiteFormulaSkips++; return ZERO;
        }
        if (demand.compareTo(process.output(process.parent.key).multiply(BigInteger.TWO)) < 0) return ZERO;
        if (initial.size() + state.stock.size() > 8192) return ZERO;
        // #236: a journal checkpoint survives applying the formula without copying unrelated stock.
        try (var stock = ((VmJournalMap<AEKey, BigInteger>) state.stock).snapshot()) {
        var snapshot = new VmOverlayMap<>(initial, stock);
        var ancestors = new ArrayList<AEKey>();
        for (var node = process.parent.ancestor; node != null; node = node.ancestor) ancestors.add(node.key);
        VmFormulaProgram program;
        formulaAttempts++;
        long formulaStart = System.nanoTime();
        try {
            program = formulaCompiler.compile(process.pattern, process.parent.key, snapshot, ancestors, simulate, demand);
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) { return ZERO; }
        finally { formulaNanos += System.nanoTime() - formulaStart; }
        if (program == null) {
            if (branchProfile != null) branchProfile.rejected(process.parent.key, "formula-" + formulaCompiler.lastDecline);
            return ZERO;
        }
        var times = program.repetitions(key -> snapshot.getOrDefault(key, ZERO), demand.divide(program.output));
        if (times.signum() <= 0 || program.output.multiply(times).compareTo(process.output(process.parent.key)) <= 0) return ZERO;
        if (branchProfile != null && times.equals(ONE) && program.output.compareTo(demand) < 0)
            branchProfile.rejected(process.parent.key, "formula-prefix-" + formulaCompiler.lastDecline);
        // #236: capacity visits include rejected alternatives, but never their rolled-back quantities.
        installFormulaCalls(process, program.call);
        // #236: unchanged positive counts do not prove an unchanged fuzzy candidate domain.
        for (var key : program.domains) state.fuzzy(key);
        for (var key : program.guards.keySet()) {
            if (program.constantDelta(key)) {
                // #236: constant effects admit the certified interval in enclosing traces, not just this stock point.
                var bound = program.guards.get(key);
                var drift = program.movement(key, times.subtract(ONE)).b();
                state.read(key, bound.min().subtract(drift.min(ZERO)).max(ZERO));
                if (bound.max() != null)
                    state.read(key, bound.max().subtract(drift.max(ZERO)).add(ONE), true);
            } else state.read(key, snapshot.getOrDefault(key, ZERO).add(ONE));
            orderGuard.exact(key);
        }
        for (var key : program.stock.keySet()) {
            var before = snapshot.getOrDefault(key, ZERO);
            var low = program.minimum(key, before, times);
            var after = program.movement(key, times).at(before);
            if (low.signum() < 0 || after.compareTo(low) < 0) throw new IllegalStateException("Invalid VM formula inventory proof");
            state.extract(key, before.subtract(low));
            state.insert(key, after.subtract(low));
        }
        program.missing.forEach((key, quantity) -> merge(state.missing, key,
                program.total(key, quantity, times).at(snapshot.getOrDefault(key, ZERO))));
        program.emitted.forEach((key, quantity) -> merge(state.emitted, key,
                program.total(key, quantity, times).at(snapshot.getOrDefault(key, ZERO))));
        program.crafts.forEach((pattern, count) -> merge(state.patterns, pattern, count.multiply(times)));
        state.bytes = state.bytes.add(program.bytes.multiply(times));
        formulaBlocks++;
        return program.output.multiply(times);
        }
    }

    private void installFormulaCalls(Process process, VmFormulaProgram.Call call) {
        if (!call.entered) return;
        var inputs = process.inputs();
        call.children.forEach((slot, calls) -> {
            var choices = inputs.get(slot).processes();
            for (var child : calls) {
                var selected = choices.stream().filter(p -> p.pattern == child.pattern).findFirst()
                        .orElseThrow(() -> new IllegalStateException("VM formula provider changed"));
                installFormulaCalls(selected, child);
            }
        });
    }

    private void recordTrial(Process process) {
        branchTrials++;
        profile(process, VmBranchProfile.TRIAL);
    }

    private void profile(Process process, int event) {
        if (branchProfile != null) branchProfile.record(process.pattern, process.parent.key, event);
    }

    private CapacityShape batchCapacityShape(VmBatchProgram.Shape shape,
            Map<VmBatchProgram.Shape, CapacityShape> shapes) {
        if (shape == null) return null;
        var cached = shapes.get(shape);
        if (cached != null) return cached;
        var children = new ArrayList<CapacityShape>();
        for (var child : shape.children()) children.add(batchCapacityShape(child, shapes));
        var result = new CapacityShape(shape.pattern(), children);
        shapes.put(shape, result);
        return result;
    }

    private void addReturn(Node node, AEKey consumed, BigInteger count, Map<AEKey, BigInteger> returns) {
        if (returns == null || node.input == null) return;
        var key = inputFacts.returned(node.input, consumed);
        if (key != null) merge(returns, key, count);
    }

    private boolean independentInput(Process process, int slot) {
        if (!independentInputBudgets || process.metadata().containers) return false;
        var metadata = process.metadata();
        var inputs = process.pattern.getInputs();
        if (metadata.independentInputs == null) metadata.independentInputs = new byte[inputs.length];
        if (metadata.independentInputs[slot] != 0) return metadata.independentInputs[slot] > 0;
        metadata.independentInputs[slot] = -1;
        var later = inputFamilies(inputs[slot]);
        if (later == null) return false;
        for (int i = 0; i < slot; i++) {
            var earlier = inputFamilies(inputs[i]);
            if (earlier == null || !Collections.disjoint(earlier, later)) return false;
        }
        metadata.independentInputs[slot] = 1;
        return true;
    }

    private boolean preparedNonReturning(IPatternDetails.IInput input) {
        if (!(input instanceof PatternCompiler.DetachedInput detached) || detached.craftedKey() == null
                || detached.mayReturnItems() || !inputFacts.ready(detached, detached.craftedKey())) return false;
        for (var template : input.getPossibleInputs())
            if (!inputFacts.ready(detached, template.what())) return false;
        return true;
    }

    private Set<Family> inputFamilies(IPatternDetails.IInput input) {
        var cached = slotDependencies.get(input);
        if (cached != null) return cached.orElse(null);
        var result = compileInputFamilies(input);
        int weight = result == null ? 1 : result.size() + 1;
        if (slotDependencies.size() < 4096 && weight <= 65536 - inputDependencyWeight) {
            slotDependencies.put(input, Optional.ofNullable(result));
            inputDependencyWeight += weight;
        }
        return result;
    }

    private Set<Family> compileInputFamilies(IPatternDetails.IInput input) {
        if (!preparedNonReturning(input)) return null;
        var families = dependencyFamilies(((PatternCompiler.DetachedInput) input).craftedKey());
        if (families == null) return null;
        Set<Family> extended = null;
        for (var template : input.getPossibleInputs()) {
            var family = Family.of(template.what());
            if (!families.contains(family)) {
                if (extended == null) extended = new HashSet<>(families);
                extended.add(family);
            }
        }
        return extended == null ? families : Set.copyOf(extended);
    }

    private boolean unchangedPrefix(Process process, int slot, State state) {
        var checked = process.metadata().independentInputs;
        if (!unchangedPrefixBudgets || simulate || !traces.isEmpty() || state.quotaOwner != process
                || checked == null || checked[slot] >= 0) return false;
        var dependencies = inputFamilies(process.inputs().get(slot).input);
        if (dependencies == null) return false;
        var changes = (VmBranchMap<AEKey, BigInteger>) ((VmJournalMap<AEKey, BigInteger>) state.stock).backing();
        return !changes.anyChanged((key, value) -> {
            checkpoint();
            return dependencies.contains(Family.of(key)) && !value.equals(state.quotaEntry.unobservedAmount(key));
        });
    }

    private Set<Family> dependencyFamilies(AEKey rootKey) {
        var cached = inputDependencies.get(rootKey);
        if (cached != null) return cached.orElse(null);
        var pending = new ArrayDeque<AEKey>(); pending.add(rootKey);
        var visited = new HashSet<AEKey>();
        Set<Family> families = new HashSet<>();
        int budget = 8192;
        outer: while (!pending.isEmpty()) {
            checkpoint();
            var key = pending.removeFirst();
            if (!visited.add(key)) continue;
            if (--budget < 0 || families.size() > 4096) { families = null; break; }
            families.add(Family.of(key));
            if (emitters.test(key)) continue;
            for (var pattern : knownProducers.computeIfAbsent(key, resolver)) {
                var metadata = processMetadata.get(pattern);
                if (metadata == null || metadata.containers) { families = null; break outer; }
                for (var output : pattern.getOutputs()) families.add(Family.of(output.what()));
                for (var input : pattern.getInputs()) {
                    if (!preparedNonReturning(input)) { families = null; break outer; }
                    pending.add(((PatternCompiler.DetachedInput) input).craftedKey());
                    for (var template : input.getPossibleInputs()) families.add(Family.of(template.what()));
                }
            }
        }
        if (families != null) families = Set.copyOf(families);
        int weight = families == null ? 1 : families.size() + 1;
        if (inputDependencies.size() < 512 && weight <= 65536 - inputDependencyWeight) {
            inputDependencies.put(rootKey, Optional.ofNullable(families));
            inputDependencyWeight += weight;
        }
        return families;
    }

    /** Execute original stack arithmetic/output opcodes plus slot-aware input/return instructions. */
    private void run(Process process, BigInteger times, State state) {
        run(process, times, state, null);
    }

    private BigInteger run(Process process, BigInteger times, State state, BigInteger requested) {
        var metadata = process.metadata();
        if (optimizedGuards && !metadata.single) {
            var program = demandCompiler.get(process.pattern);
            var aggregated = program == null ? null : aggregate(process, program, times, state, requested);
            if (aggregated != null) return aggregated;
            if (preferredProjection && program != null && !program.period.equals(ONE)) {
                var bulk = divided(times, program.period).multiply(program.period);
                if (bulk.signum() > 0 && aggregate(process, program, bulk, state, null) != null) {
                    // #236: an integral bulk has no rounded intermediate residue; only the tail expands.
                    var tail = times.subtract(bulk);
                    if (tail.signum() > 0) run(process, tail, state);
                    return requested == null ? ZERO : state.extract(process.parent.key, requested);
                }
            }
            if (sharedSimulationDemands && simulate && orderAllocation && supplyGraph != null
                    && traces.isEmpty() && activeProbe == null && routeCaptures.isEmpty()) {
                var simulation = simulationCompiler.get(process.pattern);
                if (simulation != null && !simulation.alternatives.isEmpty()
                        && canAggregate(process, simulation, times, state)) {
                    orderGuard.unsupported();
                    return applyAggregate(process, simulation, times, state, requested);
                }
            }
        }
        var code = metadata.code(process.pattern);
        if (directPatternExecution && orderAllocation && !metadata.single && allocationPatterns.contains(process.pattern)
                && code.getPatternPool().length == 1 && code.getPatternPool()[0] == process.pattern) {
            return runPrepared(process, times, state, requested);
        }
        var buffer = ByteBuffer.wrap(code.getCode());
        var stack = new ArrayDeque<BigInteger>();
        stack.push(times);
        var returns = metadata.containers ? new LinkedHashMap<AEKey, BigInteger>() : null;
        boolean firstInput = true;
        while (buffer.hasRemaining()) {
            checkpoint(); instructions++;
            switch (Opcode.fromCode(buffer.get())) {
                case PUSH_LONG -> stack.push(BigInteger.valueOf(buffer.getLong()));
                case PUSH_BIG_INTEGER -> stack.push(code.getQuantity(Short.toUnsignedInt(buffer.getShort())));
                case DUP -> stack.push(stack.element());
                case POP -> stack.pop();
                case MUL -> stack.push(scaled(stack.pop(), stack.pop()));
                case ADD -> stack.push(stack.pop().add(stack.pop()));
                case SUB -> { var right = stack.pop(); stack.push(stack.pop().subtract(right)); }
                case DIV_ROUNDUP -> { var right = stack.pop(); stack.push(PatternCompiler.ceilDiv(stack.pop(), right)); }
                case REQUEST_INPUT -> {
                    int slot = Short.toUnsignedInt(buffer.getShort());
                    var input = process.inputs().get(slot);
                    var amount = stack.pop();
                    try {
                        if (input.unit.equals(ONE)) request(input, amount, state, returns);
                        else {
                            var raw = amount.divideAndRemainder(input.unit);
                            if (raw[1].signum() != 0) throw new IllegalStateException("non-integral VM input template");
                            request(input, raw[0], state, returns);
                        }
                    } catch (Missing absent) {
                        // #236: disjoint prepared subtrees see the same stock before and after earlier inputs.
                        boolean independent = firstInput || orderAllocation && independentInput(process, slot);
                        if (orderAllocation && !firstInput && independent) independentBudgets++;
                        var multiplier = PatternCompiler.inputMultiplier(input.input);
                        throw new Missing(orderAllocation && independent && absent.funded != null
                                ? absent.funded.divide(multiplier) : null,
                                orderAllocation && !independent && absent.funded != null
                                        ? new QuotaProbe(input, amount.divide(input.unit), multiplier, absent.funded) : null,
                                absent.shortage);
                    }
                    firstInput = false;
                }
                case RETURN_CONTAINERS -> {
                    if (returns != null) returns.forEach((key, amount) -> { state.insert(key, amount); state.charge(key, amount); });
                }
                case INSERT_OUTPUT -> state.insert(code.getConstantPool()[Short.toUnsignedInt(buffer.getShort())], stack.pop());
                case RECORD_PATTERN -> {
                    var pattern = code.getPatternPool()[Short.toUnsignedInt(buffer.getShort())];
                    var count = stack.pop();
                    merge(state.patterns, pattern, count);
                    state.bytes = state.bytes.add(new VmByteCost(count, ONE));
                }
                case RETURN, HALT -> { return requested == null ? ZERO : state.extract(process.parent.key, requested); }
                default -> throw new IllegalStateException("Unexpected opcode in ordered VM pattern");
            }
        }
        throw new IllegalStateException("Unterminated VM program");
    }

    private BigInteger preparedShortage(Process process, BigInteger times, BigInteger demand, State state) {
        if (!directPatternExecution || !optimizedGuards || orderGuard.supported() || !traces.isEmpty()
                || activeProbe != null || !routeCaptures.isEmpty() || process.metadata().single
                || !allocationPatterns.contains(process.pattern)) return null;
        return run(process, times, state, demand);
    }

    private BigInteger runPrepared(Process process, BigInteger times, State state, BigInteger requested) {
        var metadata = process.metadata();
        var inputs = process.inputs();
        if (metadata.multipliers == null) {
            metadata.multipliers = new BigInteger[inputs.size()];
            for (int i = 0; i < inputs.size(); i++) metadata.multipliers[i] = PatternCompiler.inputMultiplier(inputs.get(i).input);
        }
        directPatternBlocks++;
        for (int i = 0; i < inputs.size(); i++) {
            checkpoint();
            var input = inputs.get(i);
            var multiplier = metadata.multipliers[i];
            var amount = scaled(times, multiplier);
            boolean unchanged = i > 0 && unchangedPrefix(process, i, state);
            try { request(input, amount, state, null); }
            catch (Missing absent) {
                boolean independent = i == 0 || unchanged || independentInput(process, i);
                if (unchanged) unchangedBudgets++;
                if (i != 0 && independent) independentBudgets++;
                throw new Missing(independent && absent.funded != null ? absent.funded.divide(multiplier) : null,
                        !independent && absent.funded != null ? new QuotaProbe(input, amount, multiplier, absent.funded) : null,
                        absent.shortage);
            }
        }
        var taken = ZERO;
        for (var output : process.outputs().amounts().entrySet()) {
            checkpoint();
            var produced = scaled(times, output.getValue());
            if (requested != null && output.getKey().equals(process.parent.key))
                taken = state.produceAndExtract(output.getKey(), produced, requested);
            else state.insert(output.getKey(), produced);
        }
        merge(state.patterns, process.pattern, times);
        state.bytes = state.bytes.add(new VmByteCost(times, ONE));
        return taken;
    }

    /** #218: combine shared demands once. No inventory snapshot or one-craft trial is multiplied. */
    private BigInteger aggregate(Process process, VmDemandProgram program, BigInteger times, State state, BigInteger requested) {
        program = demandCompiler.stocked(program, key -> state.compareAmount(key, ONE).signum() > 0);
        if (program == null || !canAggregate(process, program, times, state)) return null;
        return applyAggregate(process, program, times, state, requested);
    }

    private BigInteger fundedAggregatePrefix(Process process, VmDemandProgram program, BigInteger upper, State state) {
        // #218: fractional per-craft calls visit different AE2 nodes and cannot share capacity accounting.
        if (!program.period.equals(ONE) || !process.allowsAggregation(program)) return ZERO;
        if (canAggregate(process, program, upper, state)) return upper;
        var limit = upper;
        List<AEKey> positiveBounds = null;
        for (var entry : program.needs.entrySet()) {
            if (program.leaves.get(entry.getKey()).emit()) continue;
            var amount = entry.getValue();
            if (amount.numerator().signum() <= 0) continue;
            var key = entry.getKey();
            var funded = state.rawAmount(key).multiply(amount.denominator()).divide(amount.numerator());
            if (funded.signum() == 0) {
                // #236: unused surplus cannot change a zero prefix; ordered fallback still runs.
                state.read(key, PatternCompiler.ceilDiv(amount.numerator(), amount.denominator()), true);
                return ZERO;
            }
            if (positiveBounds == null) positiveBounds = new ArrayList<>();
            positiveBounds.add(key);
            limit = limit.min(funded);
        }
        if (positiveBounds != null) positiveBounds.forEach(key -> { routeRead(key); orderGuard.exact(key); });
        limit = limit.divide(program.period).multiply(program.period);
        return limit.signum() > 0 && canAggregate(process, program, limit, state) ? limit : ZERO;
    }

    private boolean canAggregate(Process process, VmDemandProgram program, BigInteger times, State state) {
        if (!integralMultiple(times, program.period)) return false;
        if (!process.allowsAggregation(program)) return false;
        if (!aggregateFuzzyDomain(program, state)) return false;
        for (var key : program.intermediates) if (state.compareAmount(key, ONE).signum() != 0) return false;
        if (!program.alternatives.isEmpty()) {
            if (!simulate || supplyGraph == null || !traces.isEmpty() || activeProbe != null
                    || !routeCaptures.isEmpty()) return false;
            for (var key : program.alternatives) if (!unreachable(key, state)) return false;
        }
        for (var entry : program.needs.entrySet()) {
            var leaf = program.leaves.get(entry.getKey());
            if (leaf.stockOnly() || !simulate && !leaf.emit()) {
                var required = entry.getValue().scale(times);
                if (state.compareAmount(entry.getKey(), required).compareTo(required) < 0) return false;
            }
        }
        return true;
    }

    private boolean aggregateFuzzyDomain(VmDemandProgram program, State state) {
        for (var key : program.fuzzy) for (var entry : state.fuzzy(key))
            if (!entry.getKey().equals(key) && state.compareAmount(entry.getKey(), ONE).signum() > 0) return false;
        return true;
    }

    private void applyAggregate(Process process, VmDemandProgram program, BigInteger times, State state) {
        applyAggregate(process, program, times, state, null);
    }

    private BigInteger applyAggregate(Process process, VmDemandProgram program, BigInteger times, State state, BigInteger requested) {
        checkpoint();
        aggregatedBlocks++;
        multiple |= !program.alternatives.isEmpty();
        // Preserve only actual dependencies for an enclosing state-dependent repetition.
        for (var key : program.intermediates) state.read(key, ONE);
        for (var entry : program.needs.entrySet()) {
            checkpoint();
            var key = entry.getKey();
            var leaf = program.leaves.get(key);
            var demand = entry.getValue().scale(times);
            var taken = state.read(key, demand).min(demand).divide(leaf.unit()).multiply(leaf.unit());
            state.extract(key, taken);
            var remaining = demand.subtract(taken);
            if (remaining.signum() > 0) merge(leaf.emit() ? state.emitted : state.missing, key, remaining);
        }
        var taken = ZERO;
        for (var output : program.outputs.entrySet()) {
            var produced = output.getValue().scale(times);
            if (requested != null && output.getKey().equals(process.parent.key))
                taken = state.produceAndExtract(output.getKey(), produced, requested);
            else state.insert(output.getKey(), produced);
        }
        program.crafts.forEach((pattern, amount) -> merge(state.patterns, pattern, amount.scale(times)));
        state.bytes = state.bytes.add(program.bytes.multiply(times));
        process.aggregatedShape = CapacityShape.union(process.aggregatedShape, capacityShape(program));
        return taken;
    }

    private KeyCounter changedIndex(KeyCounter source, AEKey key, boolean positive) {
        var change = new IndexChange(key, positive);
        var changes = indexChanges.get(source);
        var cached = changes == null ? null : changes.get(change);
        if (cached != null) return cached;
        var copy = new KeyCounter();
        int members = 0;
        for (var entry : source.findFuzzy(key, FuzzyMode.IGNORE_ALL)) {
            if (!entry.getKey().equals(key)) { copy.add(entry.getKey(), 1); members++; }
        }
        if (positive) { copy.add(key, 1); members++; }
        // #236: source and result are immutable; candidate order remains AE2's own.
        if (retainedIndexChanges < 4096 && members <= 65536 - retainedIndexMembers) {
            indexChanges.computeIfAbsent(source, ignored -> new HashMap<>()).put(change, copy);
            retainedIndexChanges++;
            retainedIndexMembers += members;
        }
        return copy;
    }

    private final class State {
        private Process quotaOwner;
        private State quotaEntry;
        final Map<AEKey, BigInteger> stock, used, emitted, missing;
        final Map<IPatternDetails, BigInteger> patterns;
        private Map<Trace, Map<AEKey, BigInteger>> lows = Collections.emptyMap();
        final Map<Family, KeyCounter> keys;
        private Object stockKeyVersion = EMPTY_STOCK_KEYS;
        private Object quantityVersion;
        private BitSet supplySeeds = initialSupplySeeds;
        VmByteCost bytes = VmByteCost.ZERO;
        State() {
            stock = new VmJournalMap<>(); used = new VmJournalMap<>(); emitted = new VmJournalMap<>();
            missing = new VmJournalMap<>(); patterns = new VmJournalMap<>(); keys = new HashMap<>();
        }
        State(State parent) {
            stock = new VmJournalMap<>(new VmBranchMap<>(parent.stock, true));
            // #236: the synchronous parent is suspended for every ledger until commit/discard.
            used = new VmJournalMap<>(new VmBranchMap<>(parent.used, true));
            emitted = new VmJournalMap<>(new VmBranchMap<>(parent.emitted, true));
            missing = new VmJournalMap<>(new VmBranchMap<>(parent.missing, true));
            patterns = new VmJournalMap<>(new VmBranchMap<>(parent.patterns, true)); keys = new VmBranchMap<>(parent.keys, true);
            if (!parent.lows.isEmpty()) {
                lows = new IdentityHashMap<>(parent.lows.size());
                parent.lows.forEach((trace, low) -> lows.put(trace, new VmBranchMap<>(low, true)));
            }
            stockKeyVersion = parent.stockKeyVersion; supplySeeds = parent.supplySeeds; bytes = parent.bytes;
            quantityVersion = parent.quantityVersion;
        }
        State(State source, boolean checkpoint) {
            stock = ((VmJournalMap<AEKey, BigInteger>) source.stock).snapshot();
            emitted = ((VmJournalMap<AEKey, BigInteger>) source.emitted).snapshot();
            missing = ((VmJournalMap<AEKey, BigInteger>) source.missing).snapshot();
            patterns = ((VmJournalMap<IPatternDetails, BigInteger>) source.patterns).snapshot();
            used = Map.of(); keys = Map.of();
            stockKeyVersion = source.stockKeyVersion; supplySeeds = source.supplySeeds; bytes = source.bytes;
            quantityVersion = source.quantityVersion;
        }
        long changedEntries() {
            long count = 0;
            for (var map : List.of(stock, used, emitted, missing, patterns))
                count += ((VmJournalMap<?, ?>) map).changedEntries();
            count += ((VmBranchMap<?, ?>) keys).changedEntries();
            for (var map : lows.values()) count += ((VmBranchMap<?, ?>) map).changedEntries();
            return count;
        }
        void commitTo(State parent) {
            for (var map : List.of(stock, used, emitted, missing, patterns)) ((VmJournalMap<?, ?>) map).commit();
            ((VmBranchMap<?, ?>) keys).commit();
            // Child traces have finished; only inherited low-water maps survive here.
            for (var map : lows.values()) ((VmBranchMap<?, ?>) map).commit();
            parent.stockKeyVersion = stockKeyVersion; parent.supplySeeds = supplySeeds; parent.bytes = bytes;
            parent.quantityVersion = quantityVersion;
        }
        State copy() { stateCopies++; var copy = new State(); copy.adopt(this); return copy; }
        State replayCopy() {
            if (sparseBranches) return new State(this, true);
            // #218: replay reads these quantities only, never copied low-water maps, used counts or fuzzy indexes.
            stateCopies++;
            var copy = new State();
            replace(copy.stock, stock); replace(copy.emitted, emitted);
            replace(copy.missing, missing); replace(copy.patterns, patterns);
            copy.stockKeyVersion = stockKeyVersion; copy.supplySeeds = supplySeeds; copy.bytes = bytes;
            copy.quantityVersion = quantityVersion;
            return copy;
        }
        void closeCheckpoint() {
            for (var map : List.of(stock, emitted, missing, patterns))
                if (map instanceof VmJournalMap.Snapshot<?, ?> snapshot) snapshot.close();
        }
        long entries() {
            return (long) stock.size() + used.size() + emitted.size() + missing.size() + patterns.size()
                    + keys.size() + lows.values().stream().mapToLong(Map::size).sum();
        }
        void adopt(State other) {
            replace(stock, other.stock); replace(used, other.used); replace(emitted, other.emitted);
            replace(missing, other.missing); replace(patterns, other.patterns);
            stockKeyVersion = other.stockKeyVersion;
            quantityVersion = other.quantityVersion;
            supplySeeds = other.supplySeeds;
            lows = other.lows.isEmpty() ? Collections.emptyMap() : new IdentityHashMap<>(other.lows.size());
            other.lows.forEach((trace, low) -> lows.put(trace, VmBranchMap.snapshot(low)));
            keys.clear(); VmBranchMap.copyInto(other.keys, keys); bytes = other.bytes;
        }
        BigInteger amount(AEKey key) {
            routeRead(key);
            return unobservedAmount(key);
        }
        void openTrace(Trace trace, Map<AEKey, BigInteger> low) {
            // #236: ordinary quota trials have no replay trace or low-water table.
            if (!(lows instanceof IdentityHashMap)) lows = new IdentityHashMap<>(1);
            lows.put(trace, low);
        }
        BigInteger rawAmount(AEKey key) {
            if (activeProbe != null) activeProbe.read(key);
            return unobservedAmount(key);
        }
        BigInteger unobservedAmount(AEKey key) {
            var value = stock.get(key);
            return value != null ? value : initial.getOrDefault(key, ZERO);
        }
        BigInteger compareAmount(AEKey key, BigInteger threshold) {
            var value = amount(key);
            orderGuard.read(key, value, threshold, false);
            if (orderGuard.supported()) {
                if (coalescedTraceReads) {
                    if (!traces.isEmpty()) traces.get(traces.size() - 1).compare(key);
                } else for (var trace : traces) trace.compare(key);
            }
            return value;
        }
        boolean sameStockKeys(State other) {
            // #217: identity proves membership equality, not quantity equality. Branches inherit, never increment, tokens.
            if (stockKeyVersion == other.stockKeyVersion) return true;
            if (stock.size() != other.stock.size()) return false;
            stockKeyScans++;
            if (!stock.keySet().equals(other.stock.keySet())) return false;
            stockKeyVersion = other.stockKeyVersion;
            return true;
        }
        private void putStock(AEKey key, BigInteger amount) {
            routeRead(key);
            var previous = stock.put(key, amount);
            if (previous == null) {
                stockKeyVersion = new Object();
                previous = initial.getOrDefault(key, ZERO);
            }
            if (!previous.equals(amount)) quantityVersion = null;
            // #236: unchanged membership in this run must remain unchanged on reuse too.
            orderGuard.positive(key, previous);
            orderGuard.positive(key, amount);
            // #236: membership changes only across zero, not on every quantity update.
            if (optimizedGuards && (previous.signum() > 0) != (amount.signum() > 0)) {
                index(key);
                if (supplyGraph != null) supplySeeds = supplyGraph.membership(supplySeeds, key,
                        !keys.getOrDefault(Family.of(key), initialKeys).findFuzzy(key, FuzzyMode.IGNORE_ALL).isEmpty());
            }
        }
        Collection<? extends Map.Entry<AEKey, ?>> fuzzy(AEKey key) {
            routeDomain(key);
            // #236: guard the initial domain, not the depleted/returned mid-execution index.
            orderGuard.domain(key, initialKeys);
            var family = Family.of(key);
            KeyCounter domain;
            if (supplyGraph != null && supplyGraph.knownAbsent(supplySeeds, key)) {
                emptyFamilyLookups++;
                domain = emptyKeys;
            } else domain = keys.getOrDefault(family, initialKeys);
            if (optimizedGuards) {
                if (coalescedTraceReads) {
                    if (!traces.isEmpty()) traces.get(traces.size() - 1).domain(family, domain);
                } else for (var trace : traces) trace.domain(family, domain);
            }
            return domain.findFuzzy(key, FuzzyMode.IGNORE_ALL);
        }
        void index(AEKey key) {
            routeDomain(key);
            // Reconstruction can precede the first search of this family.
            orderGuard.domain(key, initialKeys);
            var family = Family.of(key);
            var source = keys.getOrDefault(family, initialKeys);
            boolean present = source.get(key) > 0;
            boolean positive = !optimizedGuards || amount(key).signum() > 0;
            if (present == positive) return;
            // #236: keep exhausted variants out of search, without changing the BigInteger ledger.
            keys.put(family, changedIndex(source, key, positive));
        }
        BigInteger read(AEKey key, BigInteger requested) {
            return read(key, requested, false);
        }
        BigInteger read(AEKey key, BigInteger requested, boolean belowUnit) {
            stateReads++;
            routeRead(key);
            var available = stock.get(key);
            if (available == null) {
                available = initial.getOrDefault(key, ZERO);
                stock.put(key, available);
                stockKeyVersion = new Object();
            }
            if (!optimizedGuards) index(key);
            orderGuard.read(key, available, requested, belowUnit);
            if (coalescedTraceReads) {
                if (!traces.isEmpty()) traces.get(traces.size() - 1).read(key, available, requested, belowUnit);
            } else for (var trace : traces) trace.read(key, available, requested, belowUnit);
            return available;
        }
        BigInteger extract(AEKey key, BigInteger requested) {
            var available = read(key, requested);
            var taken = available.min(requested);
            var left = available.subtract(taken);
            if (taken.signum() > 0) putStock(key, left);
            else orderGuard.positive(key, available);
            if (!optimizedGuards) index(key);
            if (coalescedTraceReads) {
                if (!traces.isEmpty()) {
                    var trace = traces.get(traces.size() - 1);
                    lowWaterWrites++;
                    lows.get(trace).merge(key, left.min(trace.before.amount(key)), BigInteger::min);
                }
            } else lows.forEach((trace, low) -> {
                lowWaterWrites++;
                low.merge(key, left.min(trace.before.amount(key)), BigInteger::min);
            });
            var required = initial.getOrDefault(key, ZERO).subtract(left);
            if (required.signum() > 0) used.merge(key, required, BigInteger::max);
            return taken;
        }
        void insert(AEKey key, BigInteger quantity) {
            putStock(key, amount(key).add(quantity));
            if (!optimizedGuards) index(key);
        }
        BigInteger produceAndExtract(AEKey key, BigInteger produced, BigInteger requested) {
            if (!fusedTransfers || !optimizedGuards || orderGuard.supported() || !traces.isEmpty()
                    || activeProbe != null || !routeCaptures.isEmpty()) {
                insert(key, produced);
                return extract(key, requested);
            }
            // #236: no observer sees the temporary output; retain the exact surplus and initial-stock use.
            var available = amount(key).add(produced);
            var taken = available.min(requested);
            var left = available.subtract(taken);
            putStock(key, left);
            var required = initial.getOrDefault(key, ZERO).subtract(left);
            if (required.signum() > 0) used.merge(key, required, BigInteger::max);
            return taken;
        }
        void charge(AEKey key, BigInteger amount) {
            bytes = bytes.add(VmByteCost.forStack(amount, key.getType().getAmountPerByte()));
        }
    }

    /** Per-block comparison domain; partial reads deliberately require exact equality. */
    private final class Trace {
        boolean finiteAllocationUsed;
        final State before;
        final Map<AEKey, BigInteger> lower = new HashMap<>(), upper = new HashMap<>();
        final Map<Family, Set<KeyCounter>> fuzzyDomains = new HashMap<>();
        final Set<AEKey> compared = new HashSet<>();
        Map<AEKey, BigInteger> minimum;
        Trace(State state) {
            if (activeProbe != null) activeProbe.supported = false;
            before = state.replayCopy();
            state.openTrace(this, sparseBranches ? new LinkedHashMap<>() : VmBranchMap.snapshot(state.stock));
        }
        void compare(AEKey key) {
            if (!orderGuard.supported()) return;
            traceMetadataWrites++;
            if (compared.size() >= 8192 && !compared.contains(key)) orderGuard.unsupported();
            else compared.add(key);
        }
        void domain(Family family, KeyCounter index) {
            traceMetadataWrites++;
            fuzzyDomains.computeIfAbsent(family,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(index);
        }
        void finish(State state) {
            // #236: failed child trials constrain their parent too, even when their stock is discarded.
            if (coalescedTraceReads && traces.size() > 1) {
                var parent = traces.get(traces.size() - 2);
                compared.forEach(parent::compare);
                fuzzyDomains.forEach((family, indexes) -> indexes.forEach(index -> parent.domain(family, index)));
                for (var entry : lower.entrySet()) {
                    var key = entry.getKey();
                    var offset = before.amount(key).subtract(parent.before.amount(key));
                    parent.lower.merge(key, entry.getValue().subtract(offset), BigInteger::max);
                    var max = upper.get(key);
                    if (max != null) parent.upper.merge(key, max.subtract(offset), BigInteger::min);
                }
            }
            minimum = state.lows.remove(this);
            if (coalescedTraceReads && traces.size() > 1) {
                // #236: merge only committed minima; failed branch journals have already been discarded.
                var parent = state.lows.get(traces.get(traces.size() - 2));
                minimum.forEach((key, low) -> parent.merge(key, low, BigInteger::min));
            }
            before.closeCheckpoint();
        }
        void captureMinimum(State state) {
            minimumSnapshots++;
            minimum = VmBranchMap.snapshot(state.lows.get(this));
        }
        void read(AEKey key, BigInteger available, BigInteger requested, boolean belowUnit) {
            traceReads++;
            if (coalescedTraceReads && !upper.isEmpty()) {
                var bound = upper.get(key);
                if (bound != null && bound.equals(lower.get(key))) {
                    // #236: this execution's start amount already is the only admitted value.
                    fixedTraceReads++;
                    return;
                }
            }
            var start = before.amount(key);
            var offset = available.subtract(start);
            if (belowUnit) {
                // #218: zero consumption is invariant throughout [0, unit), not at one exact quantity.
                lower.merge(key, offset.negate(), BigInteger::max);
                upper.merge(key, requested.subtract(ONE).subtract(offset), BigInteger::min);
            } else if (available.compareTo(requested) >= 0) {
                lower.merge(key, requested.subtract(offset), BigInteger::max);
            } else {
                lower.merge(key, start, BigInteger::max);
                upper.merge(key, start, BigInteger::min);
            }
        }
        BigInteger safeRepeats(State after, BigInteger limit) {
            if (finiteAllocationUsed) return ZERO;
            if (limit.signum() <= 0) return ZERO;
            // #236: an omitted zero candidate must not become available in a skipped repetition.
            if (optimizedGuards && !fuzzyDomains.isEmpty()) {
                var changed = before.stock instanceof VmJournalMap.Snapshot<AEKey, BigInteger> snapshot
                        ? snapshot.changedKeys() : after.stock.keySet();
                for (var key : changed) {
                    var domains = fuzzyDomains.get(Family.of(key));
                    if (domains == null || after.amount(key).equals(before.amount(key))) continue;
                    for (var domain : domains) if (domain.get(key) == 0) {
                        if (branchProfile != null) branchProfile.rejected(key, "fuzzy-domain");
                        pinReplayDependencies();
                        return ZERO;
                    }
                }
            }
            // The caller already verified the key domain; numerical guards still apply to every observed input.
            for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = after.amount(key).subtract(start);
                var min = lower.get(key);
                var max = upper.get(key);
                if (delta.signum() < 0) {
                    var bound = start.subtract(min).divide(delta.negate());
                    if (bound.signum() <= 0) {
                        if (branchProfile != null) branchProfile.rejected(key, "lower-bound");
                        // #236: no iteration is skipped; only preserve this zero-repeat decision.
                        orderGuard.read(key, start, min.subtract(delta), true);
                        return ZERO;
                    }
                    limit = limit.min(bound);
                }
                if (delta.signum() > 0 && max != null) {
                    var bound = max.subtract(start).divide(delta);
                    if (bound.signum() <= 0) {
                        if (branchProfile != null) branchProfile.rejected(key, "upper-bound");
                        orderGuard.read(key, start, max.subtract(delta).add(ONE), false);
                        return ZERO;
                    }
                    limit = limit.min(bound);
                }
            }
            // Positive replay still requires exact dependencies, including interior fuzzy transitions.
            pinReplayDependencies();
            return limit.max(ZERO);
        }
        private void pinReplayDependencies() {
            if (orderGuard.supported()) {
                lower.keySet().forEach(orderGuard::exact);
                compared.forEach(orderGuard::exact);
            }
        }
        void replay(State state, BigInteger repeats) {
            replays++;
            skipped = skipped.add(repeats);
            // Extend enclosing guards across every skipped iteration, including failed trials.
            for (var parent : traces) for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = state.amount(key).subtract(start);
                var offset = start.subtract(parent.before.amount(key));
                var min = lower.get(key).subtract(offset).subtract(delta.min(ZERO).multiply(repeats));
                parent.lower.merge(key, min, BigInteger::max);
                var max = upper.get(key);
                if (max != null) parent.upper.merge(key,
                        max.subtract(offset).subtract(delta.max(ZERO).multiply(repeats)), BigInteger::min);
            }
            Collection<AEKey> changed;
            if (before.stock instanceof VmJournalMap.Snapshot<AEKey, BigInteger> snapshot) {
                // #218: a committed catalyst can have zero net change but a nonzero reservation peak.
                var keys = new LinkedHashSet<>(snapshot.changedKeys());
                keys.addAll(minimum.keySet());
                changed = keys;
            } else changed = state.stock.keySet();
            for (var key : changed) {
                var delta = state.amount(key).subtract(before.amount(key));
                state.putStock(key, state.stock.get(key).add(delta.multiply(repeats)));
                var low = minimum.getOrDefault(key, before.amount(key));
                if (delta.signum() < 0) low = low.add(delta.multiply(repeats));
                var peak = initial.getOrDefault(key, ZERO).subtract(low);
                if (peak.signum() > 0) state.used.merge(key, peak, BigInteger::max);
                var actualLow = low;
                state.lows.values().forEach(values -> values.merge(key, actualLow, BigInteger::min));
            }
            scaleDelta(state.patterns, before.patterns, repeats);
            scaleDelta(state.emitted, before.emitted, repeats);
            scaleDelta(state.missing, before.missing, repeats);
            state.bytes = state.bytes.add(state.bytes.subtract(before.bytes).multiply(repeats));
        }
    }

    private static <K> void merge(Map<K, BigInteger> map, K key, BigInteger value) {
        if (value.signum() != 0) map.merge(key, value, BigInteger::add);
    }
    private static <K> void replace(Map<K, BigInteger> target, Map<K, BigInteger> source) {
        // VM ledgers only grow: a successful child contains every key of its parent.
        VmBranchMap.copyInto(source, target);
    }
    private static <K> void scaleDelta(Map<K, BigInteger> target, Map<K, BigInteger> before,
            BigInteger repeats) {
        if (before instanceof VmJournalMap.Snapshot<K, BigInteger> snapshot) {
            for (var key : snapshot.changedKeys())
                merge(target, key, target.get(key).subtract(before.getOrDefault(key, ZERO)).multiply(repeats));
        } else target.forEach((key, value) -> merge(target, key, value.subtract(before.getOrDefault(key, ZERO)).multiply(repeats)));
    }
    private void checkpoint() {
        control.run();
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM calculation cancelled");
    }
    private static final class Missing extends RuntimeException {
        final BigInteger funded;
        final QuotaProbe probe;
        final Shortage shortage;
        final Process constantSeedFor;
        Missing() { this(null); }
        Missing(BigInteger funded) { this(funded, null); }
        Missing(BigInteger funded, QuotaProbe probe) {
            this(funded, probe, null);
        }
        Missing(BigInteger funded, QuotaProbe probe, Shortage shortage) {
            this(funded, probe, shortage, null);
        }
        Missing(BigInteger funded, QuotaProbe probe, Shortage shortage, Process constantSeedFor) {
            super(null, null, false, false); this.funded = funded; this.probe = probe; this.shortage = shortage;
            this.constantSeedFor = constantSeedFor;
        }
    }
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
    private record Candidate(AEKey key, BigInteger unit) { }
}
