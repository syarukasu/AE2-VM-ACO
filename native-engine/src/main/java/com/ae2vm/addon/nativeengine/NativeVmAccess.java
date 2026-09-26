package com.ae2vm.addon.nativeengine;

import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** #218: only live callbacks need an exclusive AE2 interval, not VM arithmetic. */
final class NativeVmAccess {
    private final Object monitor = new Object();
    private final Thread serverThread = Thread.currentThread();
    private final LongSupplier clock;
    private volatile boolean granted;
    private volatile long deadline;
    private boolean waiting;
    private boolean finished;
    private int depth;
    private long waitNanos, exclusiveNanos, intervalStart, intervals, reads;

    NativeVmAccess() { this(System::nanoTime); }
    NativeVmAccess(LongSupplier clock) { this.clock = clock; }

    void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM calculation cancelled");
        if (depth == 0 && granted && clock.getAsLong() - deadline >= 0) {
            synchronized (monitor) { release(); }
        }
    }

    <T> T read(Supplier<T> callback) {
        checkpoint();
        // Construction and synchronous test/oracle callers already own the world.
        if (Thread.currentThread() == serverThread) return callback.get();
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

    Metrics metrics() {
        synchronized (monitor) {
            return new Metrics(waitNanos, exclusiveNanos + (granted ? clock.getAsLong() - intervalStart : 0),
                    intervals, reads);
        }
    }

    record Metrics(long waitNanos, long exclusiveNanos, long intervals, long reads) { }
}
