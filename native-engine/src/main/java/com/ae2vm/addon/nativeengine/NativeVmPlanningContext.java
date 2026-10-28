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
    private static final int RETURN_LOOKAHEAD_KEYS = 16;
    private static final int RETURN_LOOKAHEAD_PROBES = 64;
    private static final int REUSE_ENTRIES = 16, REUSE_OBSERVATIONS = 8192;
    private static final long REUSE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final java.util.ArrayDeque<ReusablePlan> REUSABLE = new java.util.ArrayDeque<>();
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
    private final Map<AEItemKey, NativeToolRule> toolRules = new HashMap<>();
    private final Map<Object, java.util.Set<AEKey>> candidateFamilies = new HashMap<>();
    private final Map<Object, List<LiveInput>> inputsByFamily = new HashMap<>();
    private final Map<ICraftingService, Map<AEKey, Boolean>> emitterAnswers = new IdentityHashMap<>();
    private boolean producerServiceResolved;
    private ICraftingService producerService;
    private boolean emitterServiceResolved;
    private ICraftingService emitterService;
    private boolean catalogPrepared;
    private boolean orderAllocation = true;

    void setOrderAllocation(boolean enabled) { orderAllocation = enabled; }
    private boolean candidateDomainClosed;
    private com.ae2vm.addon.vm.VmStockGuard stockGuard;
    private boolean recordingReads;
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

    NativeVmBenchmark.Snapshot benchmarkSnapshot(AEKey root, BigInteger requested, boolean craftLess) {
        // #221: only already-captured values; never resolve a new service or callback for diagnostics.
        var emitters = emitterAnswers.get(emitterService);
        return NativeVmBenchmark.capture(root, requested, craftLess, stock.amounts(), stock.complete(),
                programsByKey, ids, bindings, emitters == null ? Map.of() : emitters, !requiresLiveAccess());
    }

    VmQuantities plan(AEKey root, BigInteger requested, boolean craftLess, LongConsumer progress) {
        var stamp = reuseStamp(root);
        var previous = reusable(root, requested, craftLess, stamp);
        ReusablePlan validated = null;
        if (previous != null) {
            timing.stockGuardAvailable(previous.guard() != null);
            boolean valid;
            try (var span = timing.phase(NativeVmTiming.Phase.VALIDATE)) {
                valid = validateObservations(previous.observations());
            }
            if (valid) timing.planReplayPrepared();
            if (valid) validated = previous;
            boolean sameStock = previous.stamp().stock().equals(stamp.stock());
            var rejection = valid && previous.guard() != null
                    ? previous.guard().rejection(stock.amounts()) : null;
            if (rejection != null) timing.stockGuardRejected(rejection);
            if (valid && (previous.guard() != null ? rejection == null : sameStock)) {
                checkpoint.run();
                timing.planReused();
                if (!previous.stamp().stock().equals(stamp.stock())) timing.stockGuardReused();
                if (Boolean.getBoolean("ae2vm.replay.verify")) {
                    var checked = executePlan(root, requested, craftLess, progress);
                    if (!sameAccounting(previous.result(), checked))
                        throw new IllegalStateException("Validated VM replay accounting mismatch");
                    timing.planReuseVerified();
                } else {
                    timing.execution(0, 0, 0, 0);
                    timing.vmWork(0, 0);
                }
                return previous.result();
            }
        }
        validatedRoutes = validated == null ? null : validated.routes();
        inheritedObservations = validated == null ? List.of() : validated.observations();
        recordRoutes = stamp != null && !Boolean.getBoolean("ae2vm.replay.disabled");
        var result = executePlan(root, requested, craftLess, progress);
        if (validatedRoutes != null && Boolean.getBoolean("ae2vm.replay.verify")) {
            var retained = routes;
            var guard = stockGuard;
            validatedRoutes = null;
            recordRoutes = false;
            var checked = executePlan(root, requested, craftLess, progress);
            if (!sameAccounting(result, checked))
                throw new IllegalStateException("Validated VM route accounting mismatch");
            timing.routeReuseVerified();
            routes = retained;
            stockGuard = guard;
        }
        remember(root, requested, craftLess, stamp, result);
        return result;
    }

    private com.ae2vm.addon.vm.VmRouteCache validatedRoutes, routes;
    private List<Observation> inheritedObservations = List.of();
    private boolean recordRoutes;

    private VmQuantities executePlan(AEKey root, BigInteger requested, boolean craftLess, LongConsumer progress) {
        for (var pattern : programs.values()) if (pattern.inputs != null) for (var slot : pattern.inputs) {
            var input = (LiveInput) slot;
            input.validReads.clear(); input.remainderReads.clear();
        }
        // #236: reused branches still depend on their freshly validated opaque answers.
        for (var observation : inheritedObservations) {
            var input = (LiveInput) programs.get(bindings.get(observation.pattern())).inputs[observation.slot()];
            if (observation.answer().valid() != null) input.validReads.add(observation.key());
            if (observation.answer().remainderKnown()) input.remainderReads.add(observation.key());
        }
        recordingReads = true;
        try (var scope = PatternCompiler.openScope(timing.detailed())) {
            try {
                var vm = new CraftingVM(this, key -> {
                    var choices = candidates(key);
                    return choices.isEmpty() ? null : choices.get(0);
                });
                vm.setAllPatternsResolver(this::candidates);
                // #236: order allocation inspects only a prepared, error-free catalog, never unused live callbacks.
                vm.setOrderAllocation(orderAllocation && catalogPrepared && preparationFailures.isEmpty());
                vm.setEmitterResolver(key -> canEmit(emitterService(), key));
                vm.setProgressListener(work -> {
                    timing.planningPath(vm.planningStatus());
                    timing.executionProgress(vm.stateCopies(), vm.branchTrials(), vm.branchEntries(), vm.aggregatedBlocks());
                    timing.vmWork(vm.instructions(), work);
                    progress.accept(work);
                });
                vm.setCheckpoint(checkpoint);
                if (recordRoutes) vm.setValidatedRoutes(validatedRoutes, ids);
                com.ae2vm.addon.vm.VmCraftingPlan result;
                try (var span = timing.phase(NativeVmTiming.Phase.VM)) {
                    try {
                        result = vm.execute(PatternCompiler.compileRequest(root, requested),
                                new VmSimulationState(stock.amounts(), stock.complete()), craftLess);
                        stockGuard = vm.stockGuard();
                        routes = vm.routes();
                        timing.stockGuardAvailable(stockGuard != null);
                    } finally {
                        timing.planningPath(vm.planningStatus());
                        timing.execution(vm.stateCopies(), vm.branchTrials(), vm.branchEntries(), vm.aggregatedBlocks());
                        timing.routes(vm.reusedRoutes(), vm.routes() == null ? 0 : vm.routes().size());
                        timing.vmWork(vm.instructions(), vm.work());
                    }
                }
                Map<String, BigInteger> crafts = new LinkedHashMap<>();
                result.patternTimes().forEach((pattern, count) -> crafts.merge(
                        Objects.requireNonNull(ids.get(pattern)), count, BigInteger::add));
                return new VmQuantities(root, result.finalOutput().amount(), crafts,
                        result.usedItems().quantities(), result.emittedItems().quantities(),
                        result.missingItems().quantities(), result.bytes(), Map.of(), result.multiplePaths(),
                        vm.instructions(), vm.work(), vm.skippedIterations());
            } finally {
                recordingReads = false;
                NativeVmProfiler.observe(() -> {
                    var stats = PatternCompiler.statistics();
                    timing.compiler(stats.compileHits(), stats.compileMisses(), stats.fuzzyHits(), stats.fuzzyMisses());
                });
            }
        }
    }

    // #236: only pure arithmetic is retained. Every order recaptures world-owned inputs.
    private record InputStamp(List<GenericStack> templates, long multiplier, BigInteger scale,
            AEKey crafted, boolean emitted, AEKey exact, boolean nativeExact,
            Map<AEKey, ObservedAnswer> initialAnswers, java.util.Set<AEKey> returnFamilies) { }
    private record PatternStamp(AEItemKey definition, List<GenericStack> outputs,
            BigInteger scale, List<InputStamp> inputs) { }
    private record StockEntry(AEKey key, BigInteger amount) { }
    private record ReuseStamp(List<PatternStamp> patterns, Map<AEKey, List<String>> choices,
            Map<AEKey, Boolean> emitters, List<StockEntry> stock, boolean closed, boolean orderAllocation) { }
    private record ObservedAnswer(Boolean valid, AEKey remainder, boolean remainderKnown) { }
    private record Observation(String pattern, int slot, AEKey key, ObservedAnswer answer) { }
    private record ReusablePlan(Object grid, Object level, Object producer, AEKey root, BigInteger requested,
            boolean craftLess, long created, ReuseStamp stamp, Map<String, IPatternDetails> sources,
            List<Observation> observations, VmQuantities result, com.ae2vm.addon.vm.VmStockGuard guard,
            com.ae2vm.addon.vm.VmRouteCache routes) { }

    static void clearReusablePlans() { synchronized (REUSABLE) { REUSABLE.clear(); } }

    private ReuseStamp reuseStamp(AEKey root) {
        if (!catalogPrepared || !stock.complete() || !preparationFailures.isEmpty()) {
            timing.planReplayIneligible();
            return null;
        }
        var patterns = new ArrayList<PatternStamp>();
        for (var source : bindings.values()) {
            var pattern = programs.get(source);
            var inputs = new ArrayList<InputStamp>();
            for (var slot : pattern.inputs) {
                var input = (LiveInput) slot;
                if (!input.failures.isEmpty()) { timing.planReplayIneligible(); return null; }
                var bound = input.returnFamilyUpperBound();
                inputs.add(new InputStamp(List.of(input.templates), input.multiplier, input.scale,
                        input.crafted, input.emitted, input.exact, input.metadata.nativeExact(), answers(input),
                        bound == null ? null : java.util.Set.copyOf(List.of(bound))));
            }
            patterns.add(new PatternStamp(pattern.skeleton.definition(), List.of(pattern.skeleton.outputs()),
                    pattern.skeleton.scale(), List.copyOf(inputs)));
        }
        var choices = new HashMap<AEKey, List<String>>();
        programsByKey.forEach((key, values) -> choices.put(key, values.stream().map(ids::get).toList()));
        var inventory = new ArrayList<StockEntry>();
        // Uncertified plans require the complete normalized stock, including collision ordering.
        Map.copyOf(stock.amounts()).forEach((key, amount) -> inventory.add(new StockEntry(key, amount)));
        return new ReuseStamp(List.copyOf(patterns), Map.copyOf(choices),
                Map.copyOf(emitterAnswers.getOrDefault(emitterService, Map.of())),
                List.copyOf(inventory), candidateDomainClosed, orderAllocation);
    }

    private static Map<AEKey, ObservedAnswer> answers(LiveInput input) {
        var result = new HashMap<AEKey, ObservedAnswer>();
        input.valid.forEach((key, value) -> result.put(key, answer(input, key)));
        input.remainders.forEach((key, value) -> result.put(key, answer(input, key)));
        return Map.copyOf(result);
    }

    private static ObservedAnswer answer(LiveInput input, AEKey key) {
        return new ObservedAnswer(input.valid.get(key), input.remainders.get(key), input.remainders.containsKey(key));
    }

    private ReusablePlan reusable(AEKey root, BigInteger requested, boolean craftLess, ReuseStamp stamp) {
        if (stamp == null || Boolean.getBoolean("ae2vm.replay.disabled")) return null;
        List<ReusablePlan> candidates;
        synchronized (REUSABLE) {
            long now = System.nanoTime();
            REUSABLE.removeIf(entry -> now - entry.created() >= REUSE_NANOS);
            candidates = List.copyOf(REUSABLE);
        }
        ReusablePlan warmup = null;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            checkpoint.run();
            var entry = candidates.get(i);
            if (entry.grid() != grid || entry.level() != level || entry.producer() != producerService
                    || !entry.root().equals(root) || !entry.requested().equals(requested)
                    || entry.craftLess() != craftLess) continue;
            var old = entry.stamp();
            if (!old.patterns().equals(stamp.patterns()) || !old.choices().equals(stamp.choices())
                    || !old.emitters().equals(stamp.emitters()) || old.closed() != stamp.closed()
                    || old.orderAllocation() != stamp.orderAllocation()) {
                timing.planReplayMetadataChanged();
                continue;
            }
            boolean same = entry.sources().size() == bindings.size();
            for (var binding : bindings.entrySet()) same &= entry.sources().get(binding.getKey()) == binding.getValue();
            if (same) {
                if (warmup == null) warmup = entry;
                boolean sameStock = old.stock().equals(stamp.stock());
                if (entry.guard() != null ? entry.guard().accepts(stock.amounts()) : sameStock) {
                    if (!sameStock) timing.planReplayStockChanged();
                    return entry;
                }
                continue;
            }
            timing.planReplayMetadataChanged();
        }
        // A rejected certificate can still supply a freshly validated observation schedule.
        if (warmup != null && !warmup.stamp().stock().equals(stamp.stock())) timing.planReplayStockChanged();
        return warmup;
    }

    private boolean validateObservations(List<Observation> observations) {
        access.releaseForArithmetic();
        try {
            var pending = new ArrayList<Observation>();
            // #236: pure rules must not spend the live callback window's deadline.
            for (var observation : observations) {
                checkpoint.run();
                int local = validateObservation(observation, false);
                if (local < 0) return false;
                if (local == 0) pending.add(observation);
            }
            int cursor = 0;
            while (cursor < pending.size()) {
                int start = cursor;
                cursor = access.read(ReadKind.INPUT_VALIDITY, () -> {
                    int next = start;
                    do {
                        checkpoint.run();
                        if (validateObservation(pending.get(next), true) < 0) return -1;
                        next++;
                    } while (next < pending.size() && next - start < 256 && !access.readWindowExpired());
                    return next;
                });
                if (cursor < 0) return false;
            }
            return true;
        } finally {
            access.releaseForArithmetic();
        }
    }

    private int validateObservation(Observation observation, boolean liveAllowed) {
        var input = (LiveInput) programs.get(bindings.get(observation.pattern())).inputs[observation.slot()];
        var key = observation.key();
        var expected = observation.answer();
        if (expected.valid() != null && !input.valid.containsKey(key) && !input.failures.containsKey(key)) {
            if (input.exact != null) input.valid.put(key, input.exact.equals(key));
            else if (input.rejectsUnmatchedKeyLocally(key)) input.valid.put(key, false);
            else if (!input.observeRule(key)) {
                if (!liveAllowed) return 0;
                input.observeLive(key);
            }
        }
        if (expected.remainderKnown() && !input.remainders.containsKey(key) && !input.failures.containsKey(key)) {
            if (!input.observeRule(key)) {
                if (!liveAllowed) return 0;
                try { input.getRemainingKey(key); }
                catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
                catch (RuntimeException failure) { input.failures.put(key, failure); }
            }
        }
        var actual = answer(input, key);
        return input.failures.containsKey(key)
                || expected.valid() != null && !expected.valid().equals(actual.valid())
                || expected.remainderKnown() && (!actual.remainderKnown()
                        || !Objects.equals(expected.remainder(), actual.remainder())) ? -1 : 1;
    }

    private void remember(AEKey root, BigInteger requested, boolean craftLess, ReuseStamp stamp, VmQuantities result) {
        if (stamp == null || Boolean.getBoolean("ae2vm.replay.disabled")) return;
        var observations = new ArrayList<Observation>();
        for (var binding : bindings.entrySet()) {
            var inputs = programs.get(binding.getValue()).inputs;
            for (int i = 0; i < inputs.length; i++) {
                var input = (LiveInput) inputs[i];
                if (!input.failures.isEmpty()) return;
                var readKeys = new java.util.LinkedHashSet<>(input.validReads);
                readKeys.addAll(input.remainderReads);
                for (var key : readKeys) {
                    if (observations.size() == REUSE_OBSERVATIONS) { timing.planReplayIneligible(); return; }
                    observations.add(new Observation(binding.getKey(), i, key,
                            new ObservedAnswer(input.validReads.contains(key) ? input.valid.get(key) : null,
                                    input.remainderReads.contains(key) ? input.remainders.get(key) : null,
                                    input.remainderReads.contains(key))));
                }
            }
        }
        var entry = new ReusablePlan(grid, level, producerService, root, requested, craftLess, System.nanoTime(),
                stamp, Map.copyOf(bindings), List.copyOf(observations), result, stockGuard, routes);
        timing.planReplayObservations(observations.size());
        synchronized (REUSABLE) {
            REUSABLE.removeIf(old -> old.grid() == grid && old.level() == level && old.root().equals(root)
                    && old.requested().equals(requested) && old.craftLess() == craftLess
                    && old.stamp().stock().equals(stamp.stock()));
            if (REUSABLE.size() == REUSE_ENTRIES) REUSABLE.removeFirst();
            REUSABLE.addLast(entry);
        }
    }

    private static boolean sameAccounting(VmQuantities first, VmQuantities second) {
        return first.root().equals(second.root()) && first.requested().equals(second.requested())
                && first.crafts().equals(second.crafts()) && first.used().equals(second.used())
                && first.emitted().equals(second.emitted()) && first.missing().equals(second.missing())
                && first.integerCharges().equals(second.integerCharges())
                && first.stackCharges().equals(second.stackCharges()) && first.multiplePaths() == second.multiplePaths();
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
        timing.cache(NativeVmTiming.Cache.EMITTER, known != null);
        if (known != null) return known;
        if (catalogPrepared) {
            var failure = new IllegalStateException("Emitter key outside the order snapshot: " + key);
            timing.failureReason("snapshot_emitter_missing", failure);
            throw failure;
        }
        boolean answer = access.read(ReadKind.EMITTER, () -> service.canEmitFor(key));
        answers.put(key, answer);
        return answer;
    }

    private List<IPatternDetails> choicesFor(ICraftingService service, AEKey key) {
        var known = producerChoices.get(key);
        timing.cache(NativeVmTiming.Cache.PRODUCER, known != null);
        if (known != null) return known;
        if (catalogPrepared) {
            var failure = new IllegalStateException("Producer key outside the order snapshot: " + key);
            timing.failureReason("snapshot_producer_missing", failure);
            throw failure;
        }
        var choices = access.read(ReadKind.PRODUCER, () -> List.copyOf(service.getCraftingFor(key)));
        producerChoices.put(key, choices);
        return choices;
    }

    private List<IPatternDetails> candidates(AEKey key) {
        checkpoint.run();
        var known = programsByKey.get(key);
        timing.cache(NativeVmTiming.Cache.PROGRAM, known != null);
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

    private BigInteger inputAmount(AEKey key) {
        try { return stock.amount(key); }
        catch (IllegalStateException failure) {
            timing.failureReason("snapshot_input_missing", failure);
            throw failure;
        }
    }

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
        private final java.util.Set<AEKey> validReads = new java.util.LinkedHashSet<>();
        private final java.util.Set<AEKey> remainderReads = new java.util.LinkedHashSet<>();
        private boolean returnBoundResolved;
        private AEKey[] immutableReturnBound;

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
            inputAmount(primary.what());
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
            inputAmount(crafted);
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
            if (!catalogPrepared) return true;
            for (var entry : remainders.entrySet()) if (entry.getValue() != null) {
                if (recordingReads) remainderReads.add(entry.getKey());
                return true;
            }
            if (recordingReads) remainderReads.addAll(remainders.keySet());
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
                if (recordingReads && valid.containsKey(key)) validReads.add(key);
                return !valid.containsKey(key) || Boolean.TRUE.equals(valid.get(key));
            }
            return false;
        }
        public AEKey[] returnFamilyUpperBound() {
            prepare();
            if (exact != null) return new AEKey[0];
            // #236: incomplete/live candidate domains cannot certify future returned families.
            if (!catalogPrepared || !preparationFailures.isEmpty() || !failures.isEmpty()) return null;
            if (!returnBoundResolved) {
                immutableReturnBound = immutableReturnFamilyBound();
                returnBoundResolved = true;
            }
            if (immutableReturnBound != null) return immutableReturnBound;
            // #236: an opaque future state can change item family; observations alone cannot scope it.
            if (!candidateDomainClosed) return null;
            if (recordingReads) remainderReads.addAll(remainders.keySet());
            return remainders.values().stream().filter(Objects::nonNull).toArray(AEKey[]::new);
        }
        private AEKey[] immutableReturnFamilyBound() {
            if (metadata.rule() != null) {
                var bound = new java.util.LinkedHashSet<AEKey>();
                var keys = new java.util.LinkedHashSet<AEKey>(); keys.add(crafted);
                for (var template : templates) keys.add(template.what());
                boolean certified = true;
                for (var key : keys) {
                    var returned = metadata.rule().returnFamilyUpperBound(key);
                    if (returned == null) { certified = false; break; }
                    java.util.Collections.addAll(bound, returned);
                }
                if (certified) return bound.toArray(AEKey[]::new);
            }
            return null;
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
            inputAmount(key);
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
            if (recordingReads && valid.containsKey(key)) validReads.add(key);
            return Boolean.TRUE.equals(valid.get(key));
        }

        private boolean rejectsUnmatchedKeyLocally(AEKey key) {
            // #218: specialize AE2's fixed predicate before entering the live-read dispatcher.
            return nativeCrafting && metadata.nativeExact() && !templates[0].what().equals(key);
        }

        private void observe(AEKey key) {
            checkpoint.run();
            boolean known = valid.containsKey(key) || failures.containsKey(key);
            timing.cache(NativeVmTiming.Cache.INPUT, known);
            if (known) return;
            if (rejectsUnmatchedKeyLocally(key)) {
                valid.put(key, false);
                return;
            }
            if (observeRule(key)) return;
            if (catalogPrepared) {
                // #235: bounded actual return observations, never a speculative inventory or unbounded closure.
                access.read(ReadKind.INPUT_VALIDITY, () -> {
                    observeLive(key);
                    observeReturnedBatch(key);
                    return null;
                });
                return;
            }
            observeLive(key);
        }

        private void observeReturnedBatch(AEKey first) {
            var pending = new java.util.ArrayDeque<AEKey>();
            var visited = new java.util.HashSet<AEKey>();
            pending.add(first);
            visited.add(first);
            int probes = 0;
            // #236: advance the demanded input before unused peers can exhaust the shared budget.
            var demanded = first;
            while (true) {
                checkpoint.run();
                if (access.readWindowExpired() || probes++ >= RETURN_LOOKAHEAD_PROBES) return;
                if (!isCandidatePrepared(demanded)) observeLive(demanded);
                var returned = remainders.get(demanded);
                if (failures.containsKey(demanded) || !Boolean.TRUE.equals(valid.get(demanded))
                        || returned == null || !returned.getPrimaryKey().equals(first.getPrimaryKey())
                        || visited.size() >= RETURN_LOOKAHEAD_KEYS || !visited.add(returned)) break;
                pending.add(returned);
                demanded = returned;
            }
            while (!pending.isEmpty()) {
                var key = pending.remove();
                for (var peer : inputsByFamily.getOrDefault(first.getPrimaryKey(), List.of())) {
                    checkpoint.run();
                    if (access.readWindowExpired() || probes++ >= RETURN_LOOKAHEAD_PROBES) return;
                    if (!peer.isCandidatePrepared(key)) peer.observeLive(key);
                    var returned = peer.remainders.get(key);
                    if (!peer.failures.containsKey(key) && Boolean.TRUE.equals(peer.valid.get(key))
                            && returned != null && returned.getPrimaryKey().equals(first.getPrimaryKey())
                            && visited.size() < RETURN_LOOKAHEAD_KEYS && visited.add(returned)) {
                        pending.add(returned);
                    }
                }
            }
        }

        public PatternCompiler.FiniteUse finiteUse(AEKey key) {
            prepare();
            if (!nativeCrafting || metadata.rule() == null || !(key instanceof AEItemKey item)
                    || metadata.rule().evaluate(key) != null) return null;
            var rule = toolRules.get(NativeToolRule.base(item));
            if (rule != null) return rule.finiteUse(item);
            // The first real callback may normalize NBT before the certified wear law begins.
            var first = getRemainingKey(key);
            if (!(first instanceof AEItemKey next) || !key.getPrimaryKey().equals(next.getPrimaryKey())
                    || metadata.rule().evaluate(next) != null) return null;
            getRemainingKey(first);
            rule = toolRules.get(NativeToolRule.base(next));
            var tail = rule == null ? null : rule.finiteUse(next);
            if (tail == null) return null;
            return new PatternCompiler.FiniteUse() {
                public BigInteger uses() { return tail.uses().add(BigInteger.ONE); }
                public AEKey after(BigInteger count) {
                    if (count.equals(BigInteger.ONE)) return first;
                    return tail.after(count.subtract(BigInteger.ONE));
                }
            };
        }

        private boolean observeRule(AEKey key) {
            var rule = metadata.rule();
            if (rule == null) return false;
            var decision = rule.evaluate(key);
            if (decision == null && key instanceof AEItemKey item) {
                var tool = toolRules.get(NativeToolRule.base(item));
                var returned = tool == null ? null : tool.evaluate(item);
                if (returned != null) {
                    decision = new NativeCraftingRule.Decision(true, returned);
                    timing.toolRuleAnswer();
                }
            }
            if (decision == null) return false;
            if (Boolean.getBoolean("ae2vm.rules.verify")) {
                var expected = decision;
                access.read(ReadKind.INPUT_VALIDITY, () -> {
                    boolean accepted = templates[0].what().equals(key) || source.isValid(key, level);
                    AEKey returned = accepted ? source.getRemainingKey(key) : null;
                    if (accepted != expected.accepted() || !Objects.equals(returned, expected.remainder()))
                        throw new IllegalStateException("#236 crafting rule mismatch: " + key.getId());
                    return null;
                });
            }
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
            boolean known = remainders.containsKey(key);
            timing.cache(NativeVmTiming.Cache.REMAINDER, known);
            if (!known && !observeRule(key))
                remainders.put(key, access.read(ReadKind.INPUT_REMAINDER, () -> readRemainder(key)));
            if (recordingReads) remainderReads.add(key);
            return remainders.get(key);
        }

        private AEKey readRemainder(AEKey key) {
            var remainder = source.getRemainingKey(key);
            if (nativeCrafting && metadata.rule() != null && toolRules.size() < 256
                    && key instanceof AEItemKey item
                    && NativeToolRule.auditedItem(item)
                    && !toolRules.containsKey(NativeToolRule.base(item))) {
                var rule = NativeToolRule.capture(key, remainder);
                if (rule != null && toolRules.putIfAbsent(rule.base(), rule) == null) timing.toolRuleCaptured();
            }
            timing.remainderRead(timing.detailed() ? source.getClass().getName() : "",
                    timing.detailed() ? key.getId() + (remainder == null ? "" : "@"
                            + NativeCraftingRule.recipeKind(pattern) + (metadata.rule() == null ? ":opaque" : ":snapshot")) : "",
                    remainder == null);
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
