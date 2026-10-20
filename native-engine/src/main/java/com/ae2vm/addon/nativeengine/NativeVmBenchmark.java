package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Issue #221: explicitly armed, detached replay. Never a calculation or submission route. */
public final class NativeVmBenchmark {
    static final int HISTORY_LIMIT = 8;
    static final int ENTRY_LIMIT = 8192;
    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicReference<FutureTask<Result>> ACTIVE = new AtomicReference<>();
    private static final Map<Long, Snapshot> CAPTURES = new LinkedHashMap<>();
    private static final Map<Long, Result> RESULTS = new LinkedHashMap<>();

    private NativeVmBenchmark() { }

    public enum Status {
        MATCH, DIFFERENT, UNSUPPORTED, UNAVAILABLE, BUSY, PROFILER_ENABLED, FAILED, CANCELLED, TIMEOUT
    }

    public record Plan(BigInteger output, BigInteger bytes, boolean simulation,
            Map<AEKey, BigInteger> used, Map<AEKey, BigInteger> missing, Map<AEKey, BigInteger> emitted,
            Map<String, BigInteger> patterns) {
        public Plan {
            used = Map.copyOf(used);
            missing = Map.copyOf(missing);
            emitted = Map.copyOf(emitted);
            patterns = Map.copyOf(patterns);
        }
    }

    public record Difference(String field, String key, String vm, String ae2) { }

    public record Result(long order, Status status, String reason, long vmNanos, long ae2Nanos,
            Plan vm, Plan ae2, List<Difference> differences) {
        public Result { differences = List.copyOf(differences); }
    }

    public record CaptureSummary(long order, String output, BigInteger requested, boolean supported, String reason,
            String fingerprint, String generation) { }

    /** Immutable coefficients and native item/fluid keys only; no source pattern survives capture. */
    public static final class Snapshot {
        final AEKey root;
        final BigInteger requested;
        final boolean craftLess;
        final Map<AEKey, BigInteger> stock;
        final Map<AEKey, List<NativeVmReplay.Pattern>> producers;
        final Set<AEKey> emitters;
        final String reason;
        private final String fingerprint;
        private final String output;

        private Snapshot(AEKey root, BigInteger requested, boolean craftLess, Map<AEKey, BigInteger> stock,
                Map<AEKey, List<NativeVmReplay.Pattern>> producers, Set<AEKey> emitters, String reason, String output) {
            this.root = root;
            this.requested = requested;
            this.craftLess = craftLess;
            this.stock = Map.copyOf(stock);
            this.producers = Map.copyOf(producers);
            this.emitters = Set.copyOf(emitters);
            this.reason = reason;
            this.output = output;
            this.fingerprint = reason.isEmpty() ? NativeVmBenchmark.fingerprint(this) : "UNKNOWN";
        }

        public boolean supported() { return reason.isEmpty(); }
        public String reason() { return reason; }
        public String fingerprint() { return fingerprint; }
        public String generation() { return "UNKNOWN"; }
        public String output() { return output; }
    }

    public static boolean armNextCapture() { return ARMED.compareAndSet(false, true); }
    public static boolean captureArmed() { return ARMED.get(); }
    public static boolean claimCapture() { return ARMED.getAndSet(false); }

    /** Call only after claimCapture(), on the prepared context's owner thread. No live getters are used. */
    public static Snapshot capture(AEKey root, BigInteger requested, boolean craftLess,
            Map<AEKey, BigInteger> stock, boolean complete, Map<AEKey, List<IPatternDetails>> programsByKey,
            Map<IPatternDetails, String> ids, Map<String, IPatternDetails> bindings,
            Map<AEKey, Boolean> emitterAnswers, boolean detached) {
        try {
            require(detached, "OPEN_OR_UNAVAILABLE_GENERATION: context is not fully detached");
            require(complete, "INCOMPLETE_STOCK: finite stock is not authoritative");
            require(requested != null && requested.signum() > 0, "INVALID_REQUEST");
            require(stock.size() <= ENTRY_LIMIT && programsByKey.size() <= ENTRY_LIMIT
                    && ids.size() <= ENTRY_LIMIT && bindings.size() <= ENTRY_LIMIT
                    && emitterAnswers.size() <= ENTRY_LIMIT, "CAPTURE_LIMIT: too many entries");
            var keys = new KeyCopies();
            var frozenRoot = freezeKey(root, keys);
            var frozenStock = new LinkedHashMap<AEKey, BigInteger>();
            for (var entry : stock.entrySet()) {
                require(entry.getValue().signum() >= 0, "NEGATIVE_STOCK");
                if (entry.getValue().signum() > 0)
                    frozenStock.put(freezeKey(entry.getKey(), keys), entry.getValue());
            }
            var copied = new IdentityHashMap<IPatternDetails, NativeVmReplay.Pattern>();
            var producers = new LinkedHashMap<AEKey, List<NativeVmReplay.Pattern>>();
            int entries = 0;
            for (var entry : programsByKey.entrySet()) {
                entries += entry.getValue().size();
                require(entries <= ENTRY_LIMIT, "CAPTURE_LIMIT: producer references");
                var choices = new ArrayList<NativeVmReplay.Pattern>();
                for (var program : entry.getValue()) {
                    var pattern = copied.get(program);
                    if (pattern == null) {
                        String id = ids.get(program);
                        var source = bindings.get(id);
                        require(source != null && source.getClass() == AEProcessingPattern.class,
                                "UNSUPPORTED_PATTERN: only exact native AEProcessingPattern; dynamic/NBT callbacks excluded");
                        require(program instanceof PatternCompiler.DetachedPattern,
                                "UNDETACHED_PATTERN: source callbacks cannot be replayed");
                        var detachedPattern = (PatternCompiler.DetachedPattern) program;
                        require(BigInteger.ONE.equals(detachedPattern.outputScale()), "WIDE_COEFFICIENT: scaled pattern");
                        var inputs = new ArrayList<NativeVmReplay.Input>();
                        for (var slot : program.getInputs()) {
                            require(slot instanceof PatternCompiler.DetachedInput, "UNDETACHED_INPUT");
                            var input = (PatternCompiler.DetachedInput) slot;
                            var templates = input.getPossibleInputs();
                            require(templates.length == 1 && input.exactKey() != null && !input.mayReturnItems()
                                    && input.exactKey().equals(templates[0].what())
                                    && input.craftedKey().equals(input.exactKey()),
                                    "DYNAMIC_INPUT: substitutions, returns and dynamic NBT are unsupported");
                            require(BigInteger.valueOf(input.getMultiplier()).equals(input.exactMultiplier()),
                                    "WIDE_COEFFICIENT: scaled input");
                            require(templates[0].amount() > 0 && input.getMultiplier() > 0, "INVALID_INPUT");
                            inputs.add(new NativeVmReplay.Input(freezeKey(templates[0].what(), keys),
                                    templates[0].amount(), input.getMultiplier(), input.emittable()));
                        }
                        var outputs = new ArrayList<appeng.api.stacks.GenericStack>();
                        for (var output : program.getOutputs()) {
                            require(output.amount() > 0, "INVALID_OUTPUT");
                            outputs.add(new appeng.api.stacks.GenericStack(freezeKey(output.what(), keys), output.amount()));
                        }
                        require(!outputs.isEmpty(), "EMPTY_OUTPUT");
                        entries += inputs.size() + outputs.size();
                        require(entries <= ENTRY_LIMIT, "CAPTURE_LIMIT: coefficients");
                        pattern = new NativeVmReplay.Pattern(id, (AEItemKey) freezeKey(program.getDefinition(), keys),
                                inputs, outputs);
                        copied.put(program, pattern);
                    }
                    choices.add(pattern);
                }
                producers.put(freezeKey(entry.getKey(), keys), List.copyOf(choices));
            }
            var emitters = new LinkedHashSet<AEKey>();
            emitterAnswers.forEach((key, value) -> { if (value) emitters.add(freezeKey(key, keys)); });
            require(producers.containsKey(frozenRoot) && emitterAnswers.containsKey(root), "UNAVAILABLE_ROOT");
            for (var pattern : copied.values()) for (var input : pattern.inputs) {
                require(producers.containsKey(input.key) && emitterAnswers.containsKey(input.key),
                        "OPEN_INPUT_DOMAIN: producer or emitter answer unavailable");
                require(input.emitted == emitters.contains(input.key), "INCONSISTENT_EMITTER_SNAPSHOT");
            }
            var snapshot = new Snapshot(frozenRoot, requested, craftLess, frozenStock, producers, emitters, "", root.getId().toString());
            NativeVmReplay.checkFinite(snapshot);
            return snapshot;
        } catch (RuntimeException | LinkageError unsupported) {
            return unavailable(root, requested, craftLess, unsupported.getMessage() == null
                    ? unsupported.getClass().getSimpleName() : unsupported.getMessage());
        }
    }

    public static Snapshot unavailable(AEKey root, BigInteger requested, boolean craftLess, String reason) {
        // A rejected snapshot retains no graph, callback, arbitrary key implementation or live owner.
        String label = root != null && (root.getClass() == AEItemKey.class || root.getClass() == AEFluidKey.class)
                ? root.getId().toString() : "unavailable";
        return new Snapshot(null, requested, craftLess, Map.of(), Map.of(), Set.of(),
                reason == null || reason.isEmpty() ? "UNAVAILABLE_SNAPSHOT" : reason, label);
    }

    private static final class KeyCopies extends LinkedHashMap<AEKey, AEKey> {
        long bytes;
    }

    private static AEKey freezeKey(AEKey key, KeyCopies keys) {
        require(key != null && (key.getClass() == AEItemKey.class || key.getClass() == AEFluidKey.class),
                "UNSUPPORTED_KEY: native item/fluid only");
        var known = keys.get(key);
        if (known != null) return known;
        require(keys.size() < ENTRY_LIMIT, "CAPTURE_LIMIT: keys");
        // Copy the NBT as well: callers must not be able to change a captured key through a stack tag.
        var tag = key.toTagGeneric();
        long bytes = tag.sizeInBytes();
        require(bytes <= 65536, "CAPTURE_LIMIT: key NBT");
        keys.bytes += bytes;
        require(keys.bytes <= 8L * 1024 * 1024, "CAPTURE_LIMIT: aggregate NBT exceeds 8 MiB");
        var copy = AEKey.fromTagGeneric(tag.copy());
        require(copy != null, "UNAVAILABLE_KEY_TYPE");
        keys.put(key, copy);
        return copy;
    }

    static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    public static void attach(long order, Snapshot snapshot) {
        if (snapshot == null) return;
        synchronized (CAPTURES) { putBounded(CAPTURES, order, snapshot); }
    }

    public static List<CaptureSummary> captures() {
        synchronized (CAPTURES) {
            return CAPTURES.entrySet().stream().map(entry -> {
                var snapshot = entry.getValue();
                return new CaptureSummary(entry.getKey(), snapshot.output(), snapshot.requested, snapshot.supported(), snapshot.reason,
                        snapshot.fingerprint(), snapshot.generation());
            }).toList();
        }
    }

    public static List<Result> recent() {
        synchronized (RESULTS) { return List.copyOf(RESULTS.values()); }
    }

    public static Optional<Result> report(long order) {
        synchronized (RESULTS) { return Optional.ofNullable(RESULTS.get(order)); }
    }

    /** No queue: one daemon worker at most, and cancellation keeps ownership until the worker exits. */
    public static Future<Result> benchmark(long order) {
        return benchmark(order, 10_000);
    }

    /** Manual-only wall deadline, including both calculators; no change to ordinary order budgets. */
    public static Future<Result> benchmark(long order, long timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 60_000) throw new IllegalArgumentException("replay timeout must be 1..60000 ms");
        Snapshot snapshot;
        synchronized (CAPTURES) { snapshot = CAPTURES.get(order); }
        if (snapshot == null) return immediate(order, Status.UNAVAILABLE, "No captured order (not armed, expired or evicted)");
        if (!snapshot.supported()) return immediate(order, Status.UNSUPPORTED, snapshot.reason);
        if (NativeVmProfiler.detailed()) return immediate(order, Status.PROFILER_ENABLED, "Disable detailed profiling before timing");
        if (!RUNNING.compareAndSet(false, true)) return immediate(order, Status.BUSY, "One manual replay is already running");
        var frozen = snapshot;
        var task = new FutureTask<Result>(() -> {
            var result = replay(order, frozen, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
            synchronized (RESULTS) { putBounded(RESULTS, order, result); }
            return result;
        });
        var worker = new Thread(() -> {
            try { task.run(); }
            finally {
                if (task.isCancelled()) synchronized (RESULTS) {
                    putBounded(RESULTS, order, result(order, Status.CANCELLED, "user_cancelled", -1, -1, null, null));
                }
                ACTIVE.compareAndSet(task, null);
                RUNNING.set(false);
            }
        }, "AE2-VM-manual-replay");
        worker.setDaemon(true);
        ACTIVE.set(task);
        try { worker.start(); }
        catch (RuntimeException | Error failure) { ACTIVE.compareAndSet(task, null); RUNNING.set(false); throw failure; }
        return task;
    }

    /** Cancellation never releases the one-job gate; the actual worker's finally owns that release. */
    public static boolean cancel() {
        var task = ACTIVE.get();
        return task != null && task.cancel(true);
    }

    public static boolean running() { return RUNNING.get(); }

    private static Future<Result> immediate(long order, Status status, String reason) {
        return java.util.concurrent.CompletableFuture.completedFuture(result(order, status, reason, -1, -1, null, null));
    }

    private static <T> void putBounded(Map<Long, T> history, long order, T value) {
        history.remove(order);
        history.put(order, value);
        while (history.size() > HISTORY_LIMIT) history.remove(history.keySet().iterator().next());
    }

    static Result replay(long order, Snapshot snapshot) {
        return replay(order, snapshot, java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
    }

    static Result replay(long order, Snapshot snapshot, long timeoutNanos) {
        Plan vm = null, ae2 = null;
        long vmNanos = -1, ae2Nanos = -1;
        try (var deadline = NativeVmReplay.deadline(timeoutNanos)) {
            if (NativeVmProfiler.detailed()) return result(order, Status.PROFILER_ENABLED, "Detailed profiling enabled", -1, -1, null, null);
            String unsafe = NativeVmReplay.checkLongInputs(snapshot);
            if (!unsafe.isEmpty()) return result(order, Status.UNSUPPORTED, unsafe, -1, -1, null, null);
            NativeVmReplay.checkpoint();
            var vmRun = NativeVmReplay.vm(snapshot);
            vm = vmRun.plan();
            vmNanos = vmRun.nanos();
            unsafe = NativeVmReplay.checkLongResult(snapshot, vm);
            if (!unsafe.isEmpty()) return result(order, Status.UNSUPPORTED, unsafe, vmNanos, -1, vm, null);
            var ae2Run = NativeVmReplay.ae2(snapshot);
            ae2 = ae2Run.plan();
            ae2Nanos = ae2Run.nanos();
            NativeVmReplay.checkpoint();
            if (NativeVmProfiler.detailed()) return result(order, Status.PROFILER_ENABLED,
                    "Detailed profiling changed during replay; timings discarded", -1, -1, vm, ae2);
            var differences = differences(vm, ae2);
            return new Result(order, differences.isEmpty() ? Status.MATCH : Status.DIFFERENT,
                    differences.isEmpty() ? "Exact parity; detached processing subset; single-pair frozen-adapter timings, not production latency or robust speedup proof"
                            : "Exact result differences; no speedup claim", vmNanos, ae2Nanos, vm, ae2, differences);
        } catch (NativeVmReplay.ReplayTimeout timeout) {
            return result(order, Status.TIMEOUT, "replay_timeout", vmNanos, ae2Nanos, vm, ae2);
        } catch (CancellationException cancelled) {
            return result(order, Status.CANCELLED, "user_cancelled", vmNanos, ae2Nanos, vm, ae2);
        } catch (Exception | LinkageError failure) {
            return result(order, Status.FAILED, failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    vmNanos, ae2Nanos, vm, ae2);
        }
    }

    private static Result result(long order, Status status, String reason, long vmNanos, long ae2Nanos, Plan vm, Plan ae2) {
        return new Result(order, status, reason, vmNanos, ae2Nanos, vm, ae2, List.of());
    }

    static List<Difference> differences(Plan vm, Plan ae2) {
        var result = new ArrayList<Difference>();
        difference(result, "output", "", vm.output(), ae2.output());
        difference(result, "bytes", "", vm.bytes(), ae2.bytes());
        difference(result, "simulation", "", vm.simulation(), ae2.simulation());
        mapDifferences(result, "used", vm.used(), ae2.used());
        mapDifferences(result, "missing", vm.missing(), ae2.missing());
        mapDifferences(result, "emitted", vm.emitted(), ae2.emitted());
        mapDifferences(result, "patterns", vm.patterns(), ae2.patterns());
        return List.copyOf(result);
    }

    private static <K> void mapDifferences(List<Difference> result, String field,
            Map<K, BigInteger> vm, Map<K, BigInteger> ae2) {
        var keys = new LinkedHashSet<K>(vm.keySet());
        keys.addAll(ae2.keySet());
        for (var key : keys) difference(result, field, key instanceof AEKey aeKey
                ? aeKey.toTagGeneric().toString() : key.toString(),
                vm.getOrDefault(key, BigInteger.ZERO), ae2.getOrDefault(key, BigInteger.ZERO));
    }

    private static void difference(List<Difference> result, String field, String key, Object vm, Object ae2) {
        if (!vm.equals(ae2)) result.add(new Difference(field, key, vm.toString(), ae2.toString()));
    }

    private static String fingerprint(Snapshot snapshot) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                token(out, "issue221-frozen-processing-v1");
                token(out, snapshot.root.toTagGeneric().toString());
                token(out, snapshot.requested.toString());
                out.writeBoolean(snapshot.craftLess);
                var stock = snapshot.stock.entrySet().stream()
                        .sorted(java.util.Comparator.comparing(e -> e.getKey().toTagGeneric().toString())).toList();
                out.writeInt(stock.size());
                for (var entry : stock) {
                    token(out, entry.getKey().toTagGeneric().toString());
                    token(out, entry.getValue().toString());
                }
                var keys = snapshot.producers.keySet().stream()
                        .sorted(java.util.Comparator.comparing(key -> key.toTagGeneric().toString())).toList();
                out.writeInt(keys.size());
                for (var key : keys) {
                    token(out, key.toTagGeneric().toString());
                    var choices = snapshot.producers.get(key);
                    out.writeInt(choices.size());
                    for (var pattern : choices) {
                        token(out, pattern.id);
                        token(out, pattern.definition.toTagGeneric().toString());
                        out.writeInt(pattern.inputs.size());
                        for (var input : pattern.inputs) {
                            token(out, input.key.toTagGeneric().toString());
                            out.writeLong(input.amount); out.writeLong(input.multiplier); out.writeBoolean(input.emitted);
                        }
                        out.writeInt(pattern.outputs.size());
                        for (var output : pattern.outputs) {
                            token(out, output.what().toTagGeneric().toString()); out.writeLong(output.amount());
                        }
                    }
                }
                var emitters = snapshot.emitters.stream().map(key -> key.toTagGeneric().toString()).sorted().toList();
                out.writeInt(emitters.size());
                for (var key : emitters) token(out, key);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Frozen fingerprint unavailable", failure);
        }
    }

    private static void token(DataOutputStream out, String value) throws java.io.IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
