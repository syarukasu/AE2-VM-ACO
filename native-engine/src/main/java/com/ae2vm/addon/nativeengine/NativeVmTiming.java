package com.ae2vm.addon.nativeengine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** #217: per-order timings only; no world references or per-instruction clocks. */
final class NativeVmTiming {
    enum Phase { STOCK, PREPARE, VM, VALIDATE, FINALIZE }
    private final LongSupplier clock;
    private final long[] phases = new long[Phase.values().length];
    private long queued, server, blocked, handoffs, vmActive;
    private long copies, branchTrials, branchEntries;

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
        handoffs++;
        queued += started - submitted;
        server += finished - started;
    }
    synchronized void unblocked(long started) { blocked += now() - started; }
    synchronized void execution(long copies, long branchTrials, long branchEntries) {
        this.copies += copies; this.branchTrials += branchTrials; this.branchEntries += branchEntries;
    }
    private static long ms(long nanos) { return TimeUnit.NANOSECONDS.toMillis(nanos); }
    synchronized String summary() {
        return "stockMs=" + ms(phases[Phase.STOCK.ordinal()])
                + " prepareMs=" + ms(phases[Phase.PREPARE.ordinal()])
                + " vmMs=" + ms(phases[Phase.VM.ordinal()])
                + " vmActiveMs=" + ms(vmActive)
                + " validateMs=" + ms(phases[Phase.VALIDATE.ordinal()])
                + " finalizeMs=" + ms(phases[Phase.FINALIZE.ordinal()])
                + " serverQueueMs=" + ms(queued) + " serverWorkMs=" + ms(server)
                + " handoffBlockedMs=" + ms(blocked) + " handoffs=" + handoffs
                + " stateCopies=" + copies + " branchTrials=" + branchTrials
                + " branchEntries=" + branchEntries;
    }
}
