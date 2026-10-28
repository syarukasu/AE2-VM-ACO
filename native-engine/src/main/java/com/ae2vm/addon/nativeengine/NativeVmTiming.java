package com.ae2vm.addon.nativeengine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** #217: per-order timings only; no world references or per-instruction clocks. */
final class NativeVmTiming {
    interface ReadMetrics {
        long waitNanos(); long exclusiveNanos(); long intervals(); long reads();
    }
    interface AccessObservation {
        ReadMetrics metrics(); String readKindSummary();
    }
    private record EmptyMetrics(long waitNanos, long exclusiveNanos, long intervals, long reads) implements ReadMetrics { }
    enum Phase { STOCK, PREPARE, DIAGNOSTIC_CAPTURE, VM, VALIDATE, FINALIZE }
    enum ServerCall { STOCK, CATALOG, PRODUCERS, INPUTS, EMITTERS, VALIDATE, FINALIZE, OTHER }
    enum Cache { PRODUCER, PROGRAM, EMITTER, INPUT, REMAINDER }
    private final LongSupplier clock;
    private final LongSupplier cpuClock;
    private final boolean detailed;
    private final long created;
    private final boolean[] measuredPhases = new boolean[Phase.values().length];
    private final long[] phases = new long[Phase.values().length];
    private final long[] calls = new long[ServerCall.values().length];
    private final long[] queueByKind = new long[ServerCall.values().length];
    private final java.util.Map<String, Long> patternTypes;
    private long otherPatternTypes;
    private final java.util.Map<String, Long> remainderKeys;
    private final java.util.Map<String, Long> returningKeys;
    private long otherReturningCalls;
    private final long[] cacheHits, cacheMisses;
    private long patternCount;
    private long planReused, planReuseVerified;
    private long toolRules, toolRuleAnswers;
    private long stockGuardAvailable, stockGuardReused;
    private String stockGuardRejection;
    synchronized void stockGuardRejected(com.ae2vm.addon.vm.VmStockGuard.Rejection rejection) {
        stockGuardRejection = rejection.kind() + ":unavailable";
        NativeVmProfiler.observe(() -> stockGuardRejection = rejection.kind() + ":" + rejection.key().getId());
    }
    synchronized void stockGuardAvailable(boolean available) { stockGuardAvailable = available ? 1 : 0; }
    synchronized void stockGuardReused() { stockGuardReused = 1; }
    synchronized void toolRuleCaptured() { toolRules++; }
    synchronized void toolRuleAnswer() { toolRuleAnswers++; }
    private long planReplayPrepared, planReplayIneligible, planReplayMetadataChanged, planReplayStockChanged, planReplayObservations;
    synchronized void planReused() { planReused = 1; }
    synchronized void planReuseVerified() { planReuseVerified = 1; }
    synchronized void planReplayPrepared() { planReplayPrepared = 1; }
    synchronized void planReplayIneligible() { planReplayIneligible = 1; }
    synchronized void planReplayMetadataChanged() { planReplayMetadataChanged = 1; }
    synchronized void planReplayStockChanged() { planReplayStockChanged = 1; }
    synchronized void planReplayObservations(long count) { planReplayObservations = count; }
    private long initQueue = -1, workerQueue = -1, workerSubmitted;
    private long initializationSubmitted;
    private boolean initializationQueued;
    private boolean submitted, workerStarted, workerFinished, executionMeasured;
    private long workerThread, cpuFinished = -1;
    private long instructions = -1, work = -1;
    private long compileHits = -1, compileMisses = -1, fuzzyHits = -1, fuzzyMisses = -1;
    private long liveAccessRequired = -1;
    private long terminalElapsed;
    private boolean terminal;
    private boolean terminalIncomplete;
    private boolean materialMissing;
    private String failureReason;
    private Throwable observedFailure;
    private long remainderCalls, remainderNull, otherRemainderCalls;
    private long queued, server, blocked, handoffs, vmActive;
    private long copies, branchTrials, branchEntries, aggregatedBlocks;
    private long liveCopies, liveTrials, liveEntries, liveBlocks;
    private boolean progressMeasured;
    private String planningPath = "not-started";
    private long routeReuses, retainedRoutes, routeReuseVerified;

    synchronized void routes(long reused, long retained) { routeReuses += reused; retainedRoutes = retained; }
    synchronized void routeReuseVerified() { routeReuseVerified++; }
    private boolean ae2Scheduled;
    private boolean graphScheduled;
    private long cpuStarted = -1;
    private java.util.function.Supplier<ReadMetrics> accessMetrics;
    private java.util.function.Supplier<String> accessReadKinds;

    synchronized void ae2Scheduled(AccessObservation access) {
        accessMetrics = access::metrics;
        accessReadKinds = access::readKindSummary;
        ae2Scheduled();
    }

    synchronized void ae2Scheduled() {
        ae2Scheduled = true;
        if (!workerStarted) workerStarted();
    }

    synchronized void graphScheduled() { ae2Scheduled(); graphScheduled = true; }

    NativeVmTiming() { this(System::nanoTime); }
    NativeVmTiming(LongSupplier clock) { this(clock, NativeVmProfiler.detailed()); }
    NativeVmTiming(LongSupplier clock, boolean detailed) { this(clock, detailed, NativeVmTiming::cpuTime); }
    NativeVmTiming(LongSupplier clock, boolean detailed, LongSupplier cpuClock) {
        this.clock = clock;
        this.cpuClock = cpuClock;
        this.detailed = detailed;
        created = now();
        patternTypes = detailed ? new java.util.LinkedHashMap<>() : null;
        remainderKeys = detailed ? new java.util.LinkedHashMap<>() : null;
        returningKeys = detailed ? new java.util.LinkedHashMap<>() : null;
        cacheHits = detailed ? new long[Cache.values().length] : null;
        cacheMisses = detailed ? new long[Cache.values().length] : null;
    }
    long now() { return clock.getAsLong(); }
    boolean detailed() { return detailed; }

    private static long cpuTime() {
        try {
            var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                    ? bean.getCurrentThreadCpuTime() : -1;
        } catch (Throwable unavailable) { return -1; }
    }

    private long cpuNow() {
        try { return cpuClock.getAsLong(); } catch (Throwable unavailable) { return -1; }
    }

    synchronized void initializationSubmitted() {
        initializationSubmitted = now();
        initializationQueued = true;
    }
    synchronized void initializationStarted() {
        if (initializationQueued) initQueue = Math.max(0, now() - initializationSubmitted);
    }
    synchronized void workerSubmitted(boolean liveAccess) {
        workerSubmitted = now();
        submitted = true;
        liveAccessRequired = liveAccess ? 1 : 0;
    }
    synchronized void workerStarted() {
        if (workerStarted) return;
        workerStarted = true;
        if (submitted) workerQueue = Math.max(0, now() - workerSubmitted);
        workerThread = Thread.currentThread().getId();
        cpuStarted = cpuNow();
    }
    synchronized void workerFinished() {
        cpuFinished = workerCpu();
        workerFinished = true;
    }
    synchronized void planResult(boolean missing) { materialMissing = missing; }
    synchronized boolean materialMissing() { return materialMissing; }
    synchronized void terminal() { terminal(false); }
    synchronized void terminal(boolean cancelled) {
        if (terminal) return;
        terminalElapsed = Math.max(0, now() - created);
        terminalIncomplete = cancelled && workerStarted && !workerFinished;
        terminal = true;
    }
    synchronized String cancellationReason() {
        return workerStarted ? "cancelled_during_worker" : initQueue >= 0
                ? "cancelled_before_worker" : "cancelled_before_initialization";
    }
    synchronized void failureReason(String reason, Throwable failure) {
        failureReason = reason;
        observedFailure = failure;
    }
    synchronized String failureReason(Throwable failure) {
        // An unused alternative's deferred failure must not label a different terminal exception.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
            if (failure == observedFailure) return failureReason;
        return null;
    }
    private long workerCpu() {
        if (terminalIncomplete) return -1;
        if (cpuFinished >= 0) return cpuFinished;
        if (!workerStarted || workerThread != Thread.currentThread().getId() || cpuStarted < 0) return -1;
        long end = cpuNow();
        return end < 0 ? -1 : Math.max(0, end - cpuStarted);
    }

    Span phase(Phase phase) { return new Span(phase); }
    final class Span implements AutoCloseable {
        private final Phase phase;
        private final long start = now();
        private final long initialBlocked;
        private boolean closed;
        Span(Phase phase) {
            this.phase = phase;
            synchronized (NativeVmTiming.this) { initialBlocked = blocked; }
        }
        public void close() {
            synchronized (NativeVmTiming.this) {
                if (closed) return;
                closed = true;
                long elapsed = now() - start;
                phases[phase.ordinal()] += elapsed;
                measuredPhases[phase.ordinal()] = true;
                // Wall time excluding synchronous server handoffs, NOT worker CPU time.
                if (phase == Phase.VM) vmActive += Math.max(0, elapsed - (blocked - initialBlocked));
            }
        }
    }

    synchronized void serverFinished(long submitted, long started, long finished) {
        serverFinished(ServerCall.OTHER, submitted, started, finished);
    }
    synchronized void serverFinished(ServerCall kind, long submitted, long started, long finished) {
        handoffs++;
        queued += started - submitted;
        server += finished - started;
        calls[kind.ordinal()]++;
        queueByKind[kind.ordinal()] += started - submitted;
    }
    synchronized void patternType(String name) {
        patternCount++;
        if (!detailed) return;
        String bounded = name.substring(0, Math.min(120, name.length()));
        if (patternTypes.containsKey(bounded) || patternTypes.size() < 16)
            patternTypes.merge(bounded, 1L, Long::sum);
        else otherPatternTypes++;
    }
    synchronized void remainderRead(String inputClass, String itemId, boolean noReturn) {
        remainderCalls++;
        if (noReturn) remainderNull++;
        if (!detailed) return;
        if (!noReturn) {
            String returnedLabel = itemId.substring(0, Math.min(120, itemId.length()));
            if (returningKeys.containsKey(returnedLabel) || returningKeys.size() < 32)
                returningKeys.merge(returnedLabel, 1L, Long::sum);
            else otherReturningCalls++;
        }
        String label = inputClass + "/" + itemId;
        label = label.substring(0, Math.min(120, label.length()));
        if (remainderKeys.containsKey(label) || remainderKeys.size() < 12)
            remainderKeys.merge(label, 1L, Long::sum);
        else otherRemainderCalls++;
    }
    synchronized void unblocked(long started) { blocked += now() - started; }
    synchronized void execution(long copies, long branchTrials, long branchEntries) {
        execution(copies, branchTrials, branchEntries, 0);
    }
    synchronized void execution(long copies, long branchTrials, long branchEntries, long aggregatedBlocks) {
        executionMeasured = true;
        this.copies += copies; this.branchTrials += branchTrials; this.branchEntries += branchEntries;
        this.aggregatedBlocks += aggregatedBlocks;
        liveCopies = liveTrials = liveEntries = liveBlocks = 0;
        progressMeasured = false;
    }
    synchronized void executionProgress(long copies, long trials, long entries, long blocks) {
        // #236: absolute current-pass samples; final execution() adds the pass once only.
        liveCopies = copies; liveTrials = trials; liveEntries = entries; liveBlocks = blocks;
        progressMeasured = true;
    }
    synchronized void planningPath(String path) {
        planningPath = path.replace('\n', '_').replace('\r', '_').replace(' ', '_');
        if (planningPath.length() > 240) planningPath = planningPath.substring(0, 240);
    }
    synchronized void vmWork(long instructions, long work) {
        this.instructions = instructions;
        this.work = work;
    }
    synchronized void compiler(long compileHits, long compileMisses, long fuzzyHits, long fuzzyMisses) {
        if (!detailed) return;
        this.compileHits = compileHits;
        this.compileMisses = compileMisses;
        this.fuzzyHits = fuzzyHits;
        this.fuzzyMisses = fuzzyMisses;
    }
    void cache(Cache cache, boolean hit) {
        if (detailed) {
            synchronized (this) {
                if (hit) cacheHits[cache.ordinal()]++;
                else cacheMisses[cache.ordinal()]++;
            }
        }
    }

    record Snapshot(java.util.Map<String, Long> nanos, java.util.Map<String, Long> counters) {
        Snapshot { nanos = java.util.Map.copyOf(nanos); counters = java.util.Map.copyOf(counters); }
    }

    synchronized Snapshot snapshot() {
        var nanos = new java.util.LinkedHashMap<String, Long>();
        var counters = new java.util.LinkedHashMap<String, Long>();
        nanos.put("elapsed", terminal ? terminalElapsed : Math.max(0, now() - created));
        nanos.put("initializationQueue", initQueue);
        nanos.put("workerQueue", workerQueue);
        for (var phase : Phase.values()) nanos.put(phase.name().toLowerCase(java.util.Locale.ROOT),
                measuredPhases[phase.ordinal()] ? phases[phase.ordinal()] : -1L);
        nanos.put("vmActive", measuredPhases[Phase.VM.ordinal()] && (!ae2Scheduled || graphScheduled) ? vmActive : -1L);
        nanos.put("workerCpu", workerCpu());
        ReadMetrics access = new EmptyMetrics(-1, -1, -1, -1);
        if (accessMetrics != null && !terminalIncomplete) {
            try {
                var observed = accessMetrics.get();
                access = new EmptyMetrics(observed.waitNanos(), observed.exclusiveNanos(), observed.intervals(), observed.reads());
            } catch (Throwable unavailable) { }
        }
        nanos.put("worldWait", access.waitNanos());
        nanos.put("exclusive", access.exclusiveNanos());
        nanos.put("serverQueue", queued);
        nanos.put("serverWork", server);
        nanos.put("handoffBlocked", blocked);
        for (var kind : ServerCall.values()) {
            nanos.put("serverQueue:" + kind.name(), queueByKind[kind.ordinal()]);
            counters.put("serverCalls:" + kind.name(), calls[kind.ordinal()]);
        }
        counters.put("intervals", access.intervals());
        counters.put("reads", access.reads());
        counters.put("handoffs", handoffs);
        counters.put("liveAccessRequired", liveAccessRequired);
        counters.put("instructions", instructions);
        counters.put("work", work);
        counters.put("nodes", -1L); // No node counter exists in the VM; instructions are not nodes.
        counters.put("patterns", patternCount);
        counters.put("planReused", planReused);
        counters.put("routeReuses", routeReuses);
        counters.put("retainedRoutes", retainedRoutes);
        counters.put("routeReuseVerified", routeReuseVerified);
        counters.put("toolRules", toolRules);
        counters.put("toolRuleAnswers", toolRuleAnswers);
        counters.put("stockGuardAvailable", stockGuardAvailable);
        counters.put("stockGuardReused", stockGuardReused);
        if (stockGuardRejection != null) counters.put("stockGuardRejected:" + stockGuardRejection, 1L);
        counters.put("planReuseVerified", planReuseVerified);
        counters.put("planReplayPrepared", planReplayPrepared);
        counters.put("planReplayIneligible", planReplayIneligible);
        counters.put("planReplayMetadataChanged", planReplayMetadataChanged);
        counters.put("planReplayStockChanged", planReplayStockChanged);
        counters.put("planReplayObservations", planReplayObservations);
        counters.put("plannerPath:" + planningPath, 1L);
        counters.put("stateCopies", executionMeasured || progressMeasured ? copies + liveCopies : -1L);
        counters.put("branchTrials", executionMeasured || progressMeasured ? branchTrials + liveTrials : -1L);
        counters.put("branchEntries", executionMeasured || progressMeasured ? branchEntries + liveEntries : -1L);
        counters.put("aggregatedBlocks", executionMeasured || progressMeasured ? aggregatedBlocks + liveBlocks : -1L);
        counters.put("remainderCalls", remainderCalls);
        counters.put("remainderNull", remainderNull);
        counters.put("compileHits", compileHits);
        counters.put("compileMisses", compileMisses);
        counters.put("fuzzyHits", fuzzyHits);
        counters.put("fuzzyMisses", fuzzyMisses);
        for (var cache : Cache.values()) {
            String key = cache.name().toLowerCase(java.util.Locale.ROOT) + "Cache";
            counters.put(key + "Hits", detailed ? cacheHits[cache.ordinal()] : -1L);
            counters.put(key + "Misses", detailed ? cacheMisses[cache.ordinal()] : -1L);
        }
        if (detailed) {
            patternTypes.forEach((key, value) -> counters.put("patternType:" + key, value));
            counters.put("otherPatternTypes", otherPatternTypes);
            remainderKeys.forEach((key, value) -> counters.put("remainder:" + key, value));
            counters.put("otherRemainderCalls", otherRemainderCalls);
            returningKeys.forEach((key, value) -> counters.put("returning:" + key, value));
            counters.put("otherReturningCalls", otherReturningCalls);
        }
        nanos.replaceAll((key, value) -> value < 0 ? -1L : value);
        counters.replaceAll((key, value) -> value < 0 ? -1L : value);
        return new Snapshot(nanos, counters);
    }
    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    private String categories(long[] values, boolean milliseconds) {
        var result = new java.util.StringJoiner(",", "{", "}");
        for (var kind : ServerCall.values()) if (calls[kind.ordinal()] > 0) {
            long value = values[kind.ordinal()];
            result.add(kind.name() + ":" + (milliseconds ? ms(value) : value));
        }
        return result.toString();
    }
    synchronized String summary() {
        try { return summaryAvailable(); }
        catch (Throwable unavailable) { return "timingUnavailable=true"; }
    }
    private String summaryAvailable() {
        var access = accessMetrics == null ? new EmptyMetrics(0, 0, 0, 0) : accessMetrics.get();
        long cpu = workerCpu();
        var types = new java.util.StringJoiner(",", "{", "}");
        if (detailed) patternTypes.forEach((name, count) -> types.add(name + ":" + count));
        var remainders = new java.util.StringJoiner(",", "{", "}");
        if (detailed) remainderKeys.forEach((name, count) -> remainders.add(name + ":" + count));
        return "stockMs=" + ms(phases[Phase.STOCK.ordinal()])
                + " plannerPath=" + planningPath
                + " planReused=" + planReused + " planReuseVerified=" + planReuseVerified
                + " toolRules=" + toolRules + " toolRuleAnswers=" + toolRuleAnswers
                + " stockGuardAvailable=" + stockGuardAvailable + " stockGuardReused=" + stockGuardReused
                + " stockGuardRejection=" + (stockGuardRejection == null ? "none" : stockGuardRejection)
                + " planReplayPrepared=" + planReplayPrepared + " planReplayIneligible=" + planReplayIneligible
                + " planReplayMetadataChanged=" + planReplayMetadataChanged + " planReplayStockChanged=" + planReplayStockChanged
                + " planReplayObservations=" + planReplayObservations
                + " prepareMs=" + ms(phases[Phase.PREPARE.ordinal()])
                + " vmMs=" + ms(phases[Phase.VM.ordinal()])
                + " vmActiveMs=" + (ae2Scheduled && !graphScheduled ? -1 : ms(vmActive))
                + " vmCpuMs=" + (cpu < 0 ? -1 : ms(cpu))
                + " scheduler=" + (graphScheduled ? "ae2-pool-snapshot" : accessMetrics != null ? "ae2-boundary" : ae2Scheduled ? "ae2" : "detached")
                + " ae2WorldWaitMs=" + ms(access.waitNanos())
                + " ae2ExclusiveMs=" + ms(access.exclusiveNanos())
                + " ae2Intervals=" + access.intervals() + " ae2Reads=" + access.reads()
                + " ae2ReadKinds=" + (accessReadKinds == null ? "{}" : accessReadKinds.get())
                + " validateMs=" + ms(phases[Phase.VALIDATE.ordinal()])
                + " finalizeMs=" + ms(phases[Phase.FINALIZE.ordinal()])
                + " serverQueueMs=" + ms(queued) + " serverWorkMs=" + ms(server)
                + " handoffBlockedMs=" + ms(blocked) + " handoffs=" + handoffs
                + " serverCalls=" + categories(calls, false)
                + " serverQueueByKindMs=" + categories(queueByKind, true)
                + " patternTypes=" + types + " otherPatternTypes=" + otherPatternTypes
                + " remainderCalls=" + remainderCalls + " remainderNull=" + remainderNull
                + " remainderKeys=" + remainders + " otherRemainderCalls=" + otherRemainderCalls
                + " routeReuses=" + routeReuses + " retainedRoutes=" + retainedRoutes + " routeReuseVerified=" + routeReuseVerified
                + " stateCopies=" + (executionMeasured || progressMeasured ? copies + liveCopies : -1)
                + " branchTrials=" + (executionMeasured || progressMeasured ? branchTrials + liveTrials : -1)
                + " branchEntries=" + (executionMeasured || progressMeasured ? branchEntries + liveEntries : -1)
                + " aggregatedBlocks=" + (executionMeasured || progressMeasured ? aggregatedBlocks + liveBlocks : -1);
    }
}
