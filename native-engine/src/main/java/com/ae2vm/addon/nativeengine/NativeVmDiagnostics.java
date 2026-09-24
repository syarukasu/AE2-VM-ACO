package com.ae2vm.addon.nativeengine;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/** Issue #208: bounded diagnostics, independent of calculation and inventory ownership. */
final class NativeVmDiagnostics {
    static final long SLOW_MILLIS = 5_000;
    private static final long SUMMARY_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private static final long DETAIL_INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private final Logger log;
    private final LongSupplier clock;
    private final boolean verbose;
    private long lastSummary;
    private long lastRunning;
    private long lastSlow;
    private long started, completed, missing, cancelled, failed, recaptures, slow;
    private long totalMillis, maxMillis;
    private final java.util.ArrayDeque<NativeVm.CalculationSample> recent = new java.util.ArrayDeque<>();

    NativeVmDiagnostics(Logger log, LongSupplier clock, boolean verbose) {
        this.log = log;
        this.clock = clock;
        this.verbose = verbose;
        lastSummary = clock.getAsLong();
        lastRunning = lastSlow = lastSummary - DETAIL_INTERVAL;
    }

    boolean verbose() { return verbose; }

    synchronized void record(NativeVm.CalculationSample sample) {
        if (recent.size() == 64) recent.removeFirst();
        recent.addLast(sample);
    }

    synchronized java.util.List<NativeVm.CalculationSample> recent() { return java.util.List.copyOf(recent); }

    synchronized void started() { started++; summarize(); }
    synchronized void cancelled() { cancelled++; summarize(); }
    synchronized void recaptured() { recaptures++; summarize(); }

    synchronized void completed(long order, Object output, Object requested, long elapsedMillis,
            boolean simulation) {
        completed++;
        if (simulation) missing++;
        totalMillis += elapsedMillis;
        maxMillis = Math.max(maxMillis, elapsedMillis);
        if (elapsedMillis >= SLOW_MILLIS) {
            slow++;
            long now = clock.getAsLong();
            if (now - lastSlow >= DETAIL_INTERVAL) {
                lastSlow = now;
                log.warn("AE2-VM event=slow_calculation route=vm-native order={} output={} requested={} simulation={} elapsedMs={}",
                        order, output, requested, simulation, elapsedMillis);
            }
        }
        summarize();
    }

    synchronized void running(long order, Object output, long work, long elapsedMillis) {
        long now = clock.getAsLong();
        if (elapsedMillis >= SLOW_MILLIS && now - lastRunning >= DETAIL_INTERVAL) {
            lastRunning = now;
            log.info("AE2-VM event=running order={} output={} work={} elapsedMs={}",
                    order, output, work, elapsedMillis);
        }
        summarize();
    }

    synchronized void failed(long order, Object output, Object requested, RuntimeException failure) {
        failed++;
        // Do not turn accounting failures into routine, suppressed log events.
        log.error("AE2-VM event=failed order={} output={} requested={}", order, output, requested, failure);
        summarize();
    }

    private void summarize() {
        long now = clock.getAsLong();
        if (now - lastSummary < SUMMARY_INTERVAL) return;
        log.info("AE2-VM event=summary route=vm-native windowMs={} started={} completed={} missing={} cancelled={} failed={} recaptures={} slow={} meanMs={} maxMs={}",
                TimeUnit.NANOSECONDS.toMillis(now - lastSummary), started, completed, missing, cancelled,
                failed, recaptures, slow, completed == 0 ? 0 : totalMillis / completed, maxMillis);
        lastSummary = now;
        started = completed = missing = cancelled = failed = recaptures = slow = totalMillis = maxMillis = 0;
    }
}
