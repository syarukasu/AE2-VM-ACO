package com.ae2vm.addon.nativeengine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** #217: per-order timings only; no world references or per-instruction clocks. */
final class NativeVmTiming {
    enum Phase { STOCK, PREPARE, VM, VALIDATE, FINALIZE }
    enum ServerCall { STOCK, CATALOG, PRODUCERS, INPUTS, EMITTERS, VALIDATE, FINALIZE, OTHER }
    private final LongSupplier clock;
    private final long[] phases = new long[Phase.values().length];
    private final long[] calls = new long[ServerCall.values().length];
    private final long[] queueByKind = new long[ServerCall.values().length];
    private final java.util.Map<String, Long> patternTypes = new java.util.LinkedHashMap<>();
    private long otherPatternTypes;
    private long queued, server, blocked, handoffs, vmActive;
    private long copies, branchTrials, branchEntries, aggregatedBlocks;

    NativeVmTiming() { this(System::nanoTime); }
    NativeVmTiming(LongSupplier clock) { this.clock = clock; }
    long now() { return clock.getAsLong(); }

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
        String bounded = name.substring(0, Math.min(120, name.length()));
        if (patternTypes.containsKey(bounded) || patternTypes.size() < 16)
            patternTypes.merge(bounded, 1L, Long::sum);
        else otherPatternTypes++;
    }
    synchronized void unblocked(long started) { blocked += now() - started; }
    synchronized void execution(long copies, long branchTrials, long branchEntries) {
        execution(copies, branchTrials, branchEntries, 0);
    }
    synchronized void execution(long copies, long branchTrials, long branchEntries, long aggregatedBlocks) {
        this.copies += copies; this.branchTrials += branchTrials; this.branchEntries += branchEntries;
        this.aggregatedBlocks += aggregatedBlocks;
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
        var types = new java.util.StringJoiner(",", "{", "}");
        patternTypes.forEach((name, count) -> types.add(name + ":" + count));
        return "stockMs=" + ms(phases[Phase.STOCK.ordinal()])
                + " prepareMs=" + ms(phases[Phase.PREPARE.ordinal()])
                + " vmMs=" + ms(phases[Phase.VM.ordinal()])
                + " vmActiveMs=" + ms(vmActive)
                + " validateMs=" + ms(phases[Phase.VALIDATE.ordinal()])
                + " finalizeMs=" + ms(phases[Phase.FINALIZE.ordinal()])
                + " serverQueueMs=" + ms(queued) + " serverWorkMs=" + ms(server)
                + " handoffBlockedMs=" + ms(blocked) + " handoffs=" + handoffs
                + " serverCalls=" + categories(calls, false)
                + " serverQueueByKindMs=" + categories(queueByKind, true)
                + " patternTypes=" + types + " otherPatternTypes=" + otherPatternTypes
                + " stateCopies=" + copies + " branchTrials=" + branchTrials
                + " branchEntries=" + branchEntries + " aggregatedBlocks=" + aggregatedBlocks;
    }
}
