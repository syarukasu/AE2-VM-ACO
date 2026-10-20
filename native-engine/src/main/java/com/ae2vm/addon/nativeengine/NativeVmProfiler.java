package com.ae2vm.addon.nativeengine;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Issue #221: read-only, process-local observation; history never retains a plan or world. */
public final class NativeVmProfiler {
    private record Configuration(boolean enabled, boolean detailed) { }
    private static volatile Configuration configuration = new Configuration(false, false);
    private static final ArrayDeque<Profile> HISTORY = new ArrayDeque<>();

    private NativeVmProfiler() { }

    /** Changes apply to new orders, not to the middle of an existing measurement. */
    public static void configure(boolean enabled, boolean detailed) {
        configuration = new Configuration(enabled, enabled && detailed);
    }

    public static boolean detailed() { return configuration.detailed(); }

    /** Oldest to newest, immutable, at most 64 terminal profiles. Unknown measurements are -1. */
    public static List<Profile> recent() {
        synchronized (HISTORY) { return List.copyOf(HISTORY); }
    }

    public record Profile(long order, String output, String requested, String outcome, String reason,
            String route, Map<String, Long> nanos, Map<String, Long> counters) {
        public Profile {
            output = bounded(output, 256);
            requested = bounded(requested, 2048);
            outcome = bounded(outcome, 64);
            reason = bounded(reason, 256);
            route = bounded(route, 64);
            nanos = Map.copyOf(nanos);
            counters = Map.copyOf(counters);
        }

        /** The native route has no optional compiled-graph eligibility gate or planner fallback. */
        public String optimizationEligibility() { return "not_applicable"; }
        public String fallbackReason() { return "not_applicable"; }
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }

    static Session start(long order, String output, String requested, NativeVmTiming timing) {
        return new Session(configuration.enabled(), order, output, requested, timing);
    }

    static Session start(long order, java.util.function.Supplier<String> output, BigInteger requested) {
        var selected = configuration;
        var timing = new NativeVmTiming(System::nanoTime, selected.detailed());
        if (!selected.enabled()) return new Session(false, order, "", "", timing);
        String[] labels = {"UNKNOWN", "UNKNOWN"};
        observe(() -> {
            labels[0] = bounded(output.get(), 256);
            labels[1] = requested.bitLength() > 4096 ? "bits=" + requested.bitLength() : requested.toString();
        });
        return new Session(true, order, labels[0], labels[1], timing);
    }

    static String failureOutcome(Throwable failure) {
        return failureReason(failure).equals("planning_cancelled") ? "cancelled" : "failed";
    }

    static String failureReason(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof java.util.concurrent.CancellationException
                    || failure.getClass().getName().equals("com.syaru.ae2craftingoptimizer.engine.PlanningCancelledException"))
                return "planning_cancelled";
            // No dependency from the native library back to ACO, and no exception-message heuristics.
            if (failure.getClass().getName().equals("com.syaru.ae2craftingoptimizer.engine.StalePlanningSnapshotException"))
                return "generation_changed";
        }
        return "exception";
    }

    // Observation must never become a planner failure, including in FutureTask.done().
    static void observe(Runnable observation) {
        try { observation.run(); } catch (Throwable ignored) { }
    }

    static final class Session {
        private final boolean enabled;
        private final long order;
        private final String output, requested;
        private final NativeVmTiming timing;
        private final AtomicBoolean terminal = new AtomicBoolean();

        Session(boolean enabled, long order, String output, String requested, NativeVmTiming timing) {
            this.enabled = enabled;
            this.order = order;
            this.output = bounded(output, 256);
            this.requested = bounded(requested, 2048);
            this.timing = timing;
        }

        NativeVmTiming timing() { return timing; }
        boolean enabled() { return enabled; }

        void finish(String outcome, String reason) {
            if (!enabled || !terminal.compareAndSet(false, true)) return;
            observe(() -> {
                timing.terminal(outcome.equals("cancelled"));
                var snapshot = timing.snapshot();
                var profile = new Profile(order, output, requested, outcome, reason, "vm-native",
                        snapshot.nanos(), snapshot.counters());
                synchronized (HISTORY) {
                    if (HISTORY.size() == 64) HISTORY.removeFirst();
                    HISTORY.addLast(profile);
                }
            });
        }
    }
}
