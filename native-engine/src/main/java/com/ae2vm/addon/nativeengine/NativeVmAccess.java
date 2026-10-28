package com.ae2vm.addon.nativeengine;

import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** #218: only live callbacks need an exclusive AE2 interval, not VM arithmetic. */
final class NativeVmAccess implements NativeVmTiming.AccessObservation {
    enum ReadKind { GRID, PRODUCER, EMITTER, PATTERN, INPUT_METADATA, FUZZY, INPUT_VALIDITY, INPUT_REMAINDER, OTHER }
    private final Object monitor = new Object();
    private final Thread serverThread = Thread.currentThread();
    private final LongSupplier clock;
    private final java.util.function.BooleanSupplier cancelled;
    private volatile boolean granted;
    private volatile long deadline;
    private boolean waiting;
    private boolean finished;
    private int depth;
    private long waitNanos, exclusiveNanos, intervalStart, intervals, reads;
    private final long[] readKinds = new long[ReadKind.values().length];

    NativeVmAccess() { this(System::nanoTime); }
    NativeVmAccess(LongSupplier clock) { this(clock, () -> false); }
    NativeVmAccess(LongSupplier clock, java.util.function.BooleanSupplier cancelled) {
        this.clock = clock; this.cancelled = cancelled;
    }

    void checkpoint() {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("VM calculation cancelled");
        if (depth == 0 && granted && clock.getAsLong() - deadline >= 0) {
            synchronized (monitor) { release(); }
        }
    }

    boolean readWindowExpired() {
        return granted && clock.getAsLong() - deadline >= 0;
    }

    void releaseForArithmetic() {
        if (depth != 0) throw new IllegalStateException("Cannot release an active world callback");
        synchronized (monitor) { release(); }
    }

    <T> T read(Supplier<T> callback) {
        return read(ReadKind.OTHER, callback);
    }

    <T> T read(ReadKind kind, Supplier<T> callback) {
        checkpoint();
        // Construction and synchronous test/oracle callers already own the world.
        if (Thread.currentThread() == serverThread) return callback.get();
        if (depth > 0) return callback.get();
        synchronized (monitor) {
            if (finished) throw new CancellationException("VM calculation finished");
            if (!granted) {
                long started = clock.getAsLong();
                waiting = true;
                try {
                    while (!granted && !finished) monitor.wait();
                    if (finished) throw new CancellationException("VM calculation finished");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("VM calculation cancelled");
                } finally {
                    waiting = false;
                    waitNanos += clock.getAsLong() - started;
                }
            }
        }
        depth++;
        reads++;
        readKinds[kind.ordinal()]++;
        try { return callback.get(); }
        finally {
            depth--;
            checkpoint();
        }
    }

    boolean simulateFor(int micros) {
        synchronized (monitor) {
            if (finished) return false;
            if (!waiting) return true;
            intervalStart = clock.getAsLong();
            deadline = intervalStart + Math.max(0L, micros) * 1_000L;
            granted = true;
            intervals++;
            monitor.notifyAll();
            // Never return while a callback is using live world state.
            while (granted && !finished) {
                try { monitor.wait(); }
                catch (InterruptedException ignored) { }
            }
            return !finished;
        }
    }

    void finish() {
        synchronized (monitor) {
            finished = true;
            release();
            monitor.notifyAll();
        }
    }

    private void release() {
        if (granted) {
            exclusiveNanos += clock.getAsLong() - intervalStart;
            granted = false;
            monitor.notifyAll();
        }
    }

    public Metrics metrics() {
        synchronized (monitor) {
            return new Metrics(waitNanos, exclusiveNanos + (granted ? clock.getAsLong() - intervalStart : 0),
                    intervals, reads);
        }
    }

    public String readKindSummary() {
        var result = new java.util.StringJoiner(",", "{", "}");
        for (var kind : ReadKind.values()) {
            long count = readKinds[kind.ordinal()];
            if (count != 0) result.add(kind.name() + ":" + count);
        }
        return result.toString();
    }

    record Metrics(long waitNanos, long exclusiveNanos, long intervals, long reads) implements NativeVmTiming.ReadMetrics { }
}
