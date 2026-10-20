package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingVM;
import com.ae2vm.addon.vm.VmSimulationState;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.Level;

/** The only reflective boundary is AE2's private, calculation-only oracle entry and its local stock. */
final class NativeVmReplay {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MAX_WORK = BigInteger.valueOf(100_000);
    private record Deadline(long started, long duration) { }
    private static final ThreadLocal<Deadline> DEADLINE = new ThreadLocal<>();

    static final class ReplayTimeout extends RuntimeException { }
    interface Scope extends AutoCloseable { @Override void close(); }

    static Scope deadline(long nanos) {
        var previous = DEADLINE.get();
        DEADLINE.set(new Deadline(System.nanoTime(), nanos));
        return () -> { if (previous == null) DEADLINE.remove(); else DEADLINE.set(previous); };
    }

    private NativeVmReplay() { }

    static final class Input implements PatternCompiler.DetachedInput {
        final AEKey key;
        final long amount, multiplier;
        final boolean emitted;
        private final GenericStack[] templates;

        Input(AEKey key, long amount, long multiplier, boolean emitted) {
            this.key = key; this.amount = amount; this.multiplier = multiplier; this.emitted = emitted;
            templates = new GenericStack[] { new GenericStack(key, amount) };
        }

        public GenericStack[] getPossibleInputs() { return templates; }
        public long getMultiplier() { return multiplier; }
        public AEKey craftedKey() { return key; }
        public AEKey exactKey() { return key; }
        public boolean emittable() { return emitted; }
        public boolean isCandidatePrepared(AEKey candidate) { return true; }
        public boolean isValid(AEKey candidate, Level ignored) { checkpoint(); return key.equals(candidate); }
        public AEKey getRemainingKey(AEKey ignored) { checkpoint(); return null; }
    }

    static final class Pattern implements PatternCompiler.DetachedPattern {
        final String id;
        final AEItemKey definition;
        final List<Input> inputs;
        final List<GenericStack> outputs;
        private final IPatternDetails.IInput[] inputArray;
        private final GenericStack[] outputArray;

        Pattern(String id, AEItemKey definition, List<Input> inputs, List<GenericStack> outputs) {
            this.id = id; this.definition = definition;
            this.inputs = List.copyOf(inputs); this.outputs = List.copyOf(outputs);
            inputArray = this.inputs.toArray(IPatternDetails.IInput[]::new);
            outputArray = this.outputs.toArray(GenericStack[]::new);
        }

        public AEItemKey getDefinition() { return definition; }
        public IPatternDetails.IInput[] getInputs() { checkpoint(); return inputArray; }
        public GenericStack[] getOutputs() { checkpoint(); return outputArray; }
    }

    record Timed(NativeVmBenchmark.Plan plan, long nanos, long work) { }

    static Timed vm(NativeVmBenchmark.Snapshot snapshot) {
        try (var scope = PatternCompiler.openScope()) {
            long start = System.nanoTime();
            var vm = new CraftingVM(snapshot, key -> {
                var choices = snapshot.producers.getOrDefault(key, List.of());
                return choices.isEmpty() ? null : choices.get(0);
            });
            vm.setAllPatternsResolver(key -> List.copyOf(snapshot.producers.getOrDefault(key, List.of())));
            vm.setEmitterResolver(snapshot.emitters::contains);
            vm.setCheckpoint(NativeVmReplay::checkpoint);
            var plan = vm.execute(PatternCompiler.compileRequest(snapshot.root, snapshot.requested),
                    new VmSimulationState(snapshot.stock, true), snapshot.craftLess);
            long elapsed = System.nanoTime() - start;
            var patterns = new LinkedHashMap<String, BigInteger>();
            plan.patternTimes().forEach((pattern, count) -> patterns.put(((Pattern) pattern).id, count));
            return new Timed(new NativeVmBenchmark.Plan(plan.finalOutput().amount(), plan.bytes(), plan.simulation(),
                    plan.usedItems().quantities(), plan.missingItems().quantities(), plan.emittedItems().quantities(), patterns), elapsed, vm.work());
        }
    }

    static Timed ae2(NativeVmBenchmark.Snapshot snapshot) throws Exception {
        // Never call run(): it registers with TickHandler and requires a real world.
        var service = proxy(ICraftingService.class, (method, args) -> switch (method) {
            case "getCraftingFor" -> snapshot.producers.getOrDefault((AEKey) args[0], List.of());
            case "canEmitFor" -> snapshot.emitters.contains((AEKey) args[0]);
            case "getFuzzyCraftable" -> null; // Exact inputs have no alternate key.
            default -> throw new IllegalStateException("Forbidden replay service callback: " + method);
        });
        var grid = proxy(IGrid.class, (method, args) -> switch (method) {
            case "getCraftingService" -> service;
            case "getStorageService" -> null;
            default -> throw new IllegalStateException("Forbidden replay grid callback: " + method);
        });
        var node = proxy(IGridNode.class, (method, args) -> {
            if (method.equals("getGrid")) return grid;
            throw new IllegalStateException("Forbidden replay node callback: " + method);
        });
        var requester = proxy(ICraftingSimulationRequester.class, (method, args) -> switch (method) {
            case "getGridNode" -> node;
            case "getActionSource" -> null;
            default -> throw new IllegalStateException("Forbidden replay requester callback: " + method);
        });
        var bridge = new OracleAccess();
        try (var scope = PatternCompiler.openScope()) {
            long start = System.nanoTime();
            var job = new CraftingCalculation(null, grid, requester,
                    new GenericStack(snapshot.root, snapshot.requested.longValueExact()),
                    snapshot.craftLess ? CalculationStrategy.CRAFT_LESS : CalculationStrategy.REPORT_MISSING_ITEMS);
            bridge.running.setBoolean(job, true);
            bridge.time.setInt(job, Integer.MAX_VALUE);
            Object network = bridge.network.get(job);
            var inventory = (KeyCounter) bridge.list.get(network);
            snapshot.stock.forEach((key, amount) -> inventory.set(key, amount.longValueExact()));
            ICraftingPlan plan;
            try { plan = (ICraftingPlan) bridge.compute.invoke(job); }
            catch (InvocationTargetException wrapped) {
                if (wrapped.getCause() instanceof InterruptedException) throw new CancellationException("AE2 interrupted");
                if (wrapped.getCause() instanceof Exception failure) throw failure;
                if (wrapped.getCause() instanceof Error failure) throw failure;
                throw wrapped;
            }
            long elapsed = System.nanoTime() - start;
            var patterns = new LinkedHashMap<String, BigInteger>();
            plan.patternTimes().forEach((pattern, count) -> {
                NativeVmBenchmark.require(count >= 0, "AE2 negative pattern count");
                patterns.put(((Pattern) pattern).id, BigInteger.valueOf(count));
            });
            NativeVmBenchmark.require(plan.bytes() >= 0 && plan.finalOutput().amount() >= 0, "AE2 negative scalar result");
            return new Timed(new NativeVmBenchmark.Plan(BigInteger.valueOf(plan.finalOutput().amount()),
                    BigInteger.valueOf(plan.bytes()), plan.simulation(), counts(plan.usedItems()),
                    counts(plan.missingItems()), counts(plan.emittedItems()), patterns), elapsed, -1);
        }
    }

    private static final class OracleAccess {
        final Field running = field(CraftingCalculation.class, "running");
        final Field time = field(CraftingCalculation.class, "time");
        final Field network = field(CraftingCalculation.class, "networkInv");
        final Field list = field(network.getType(), "list");
        final Method compute;

        OracleAccess() throws ReflectiveOperationException {
            compute = CraftingCalculation.class.getDeclaredMethod("computePlan");
            compute.setAccessible(true);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("AE2 oracle unavailable: " + name, failure); }
    }

    private static Map<AEKey, BigInteger> counts(KeyCounter counter) {
        var result = new LinkedHashMap<AEKey, BigInteger>();
        for (var entry : counter) {
            NativeVmBenchmark.require(entry.getLongValue() >= 0, "AE2 negative inventory result");
            if (entry.getLongValue() > 0) result.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
        }
        return result;
    }

    private interface Handler { Object invoke(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            checkpoint();
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "FrozenReplay" + type.getSimpleName();
                default -> handler.invoke(method.getName(), args);
            };
        });
    }

    static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Manual replay interrupted");
        var deadline = DEADLINE.get();
        if (deadline != null && System.nanoTime() - deadline.started() >= deadline.duration()) throw new ReplayTimeout();
    }

    /** Bound constructor recursion and the expanded AE2 tree, not just the number of unique VM keys. */
    static void checkFinite(NativeVmBenchmark.Snapshot snapshot) {
        treeSize(snapshot.root, snapshot, new HashMap<>(), new HashSet<>(), 0);
    }

    private static long treeSize(AEKey key, NativeVmBenchmark.Snapshot snapshot, Map<AEKey, Long> sizes,
            Set<AEKey> path, int depth) {
        NativeVmBenchmark.require(depth < 128, "BASELINE_DEPTH_LIMIT: 128");
        NativeVmBenchmark.require(!path.contains(key), "CYCLIC_BASELINE: recursive producer graph");
        var known = sizes.get(key);
        if (known != null) return known;
        path.add(key);
        long size = 1;
        for (var pattern : snapshot.producers.getOrDefault(key, List.of())) for (var input : pattern.inputs) {
            size += treeSize(input.key, snapshot, sizes, path, depth + 1);
            NativeVmBenchmark.require(size <= 100_000, "BASELINE_TREE_LIMIT: expanded tree exceeds 100000");
        }
        path.remove(key);
        sizes.put(key, size);
        return size;
    }

    static String checkLongInputs(NativeVmBenchmark.Snapshot snapshot) {
        if (snapshot.requested.compareTo(LONG_MAX) > 0) return "WIDE_BASELINE_REQUEST: AE2 requires signed long, never clamped";
        if (snapshot.stock.values().stream().anyMatch(value -> value.compareTo(LONG_MAX) > 0))
            return "WIDE_BASELINE_STOCK: AE2 requires signed long, never clamped";
        for (var patterns : snapshot.producers.values()) for (var pattern : patterns) for (var input : pattern.inputs)
            if (BigInteger.valueOf(input.amount).multiply(BigInteger.valueOf(input.multiplier)).compareTo(LONG_MAX) > 0)
                return "WIDE_BASELINE_COEFFICIENT: input amount times multiplier exceeds long";
        return checkAllBranches(snapshot);
    }

    private static String checkAllBranches(NativeVmBenchmark.Snapshot snapshot) {
        var order = new java.util.ArrayList<AEKey>();
        visit(snapshot.root, snapshot, new HashSet<>(), order);
        java.util.Collections.reverse(order);
        var demands = new LinkedHashMap<AEKey, BigInteger>();
        demands.put(snapshot.root, snapshot.requested);
        var supplies = new LinkedHashMap<AEKey, BigInteger>(snapshot.stock);
        var limit = BigInteger.ONE.shiftLeft(50);
        // A deliberately conservative domain guard, not a reference result or a time estimate.
        // Include unused alternatives: AE2 can overflow while trying a branch the VM did not select.
        for (var key : order) {
            checkpoint();
            var demand = demands.getOrDefault(key, BigInteger.ZERO);
            if (demand.compareTo(limit) > 0) return "WIDE_BASELINE_INTERMEDIATE: all-branch safety bound exceeded";
            for (var pattern : snapshot.producers.getOrDefault(key, List.of())) {
                var output = pattern.outputs.stream().filter(stack -> stack.what().equals(key)).findFirst();
                if (output.isEmpty()) return "UNAVAILABLE_PRODUCER_OUTPUT";
                var unit = BigInteger.valueOf(output.get().amount());
                var times = demand.add(unit).subtract(BigInteger.ONE).divide(unit);
                for (var input : pattern.inputs) demands.merge(input.key, times.multiply(BigInteger.valueOf(input.amount))
                        .multiply(BigInteger.valueOf(input.multiplier)), BigInteger::add);
                for (var stack : pattern.outputs)
                    supplies.merge(stack.what(), times.multiply(BigInteger.valueOf(stack.amount())), BigInteger::add);
            }
        }
        demands.forEach((key, value) -> supplies.merge(key, value, BigInteger::add));
        return supplies.values().stream().anyMatch(value -> value.compareTo(limit) > 0)
                ? "WIDE_BASELINE_INTERMEDIATE: all-branch stock/surplus safety bound exceeded" : "";
    }

    private static void visit(AEKey key, NativeVmBenchmark.Snapshot snapshot, Set<AEKey> visited, List<AEKey> order) {
        if (!visited.add(key)) return;
        for (var pattern : snapshot.producers.getOrDefault(key, List.of()))
            for (var input : pattern.inputs) visit(input.key, snapshot, visited, order);
        order.add(key);
    }

    static String checkLongResult(NativeVmBenchmark.Snapshot snapshot, NativeVmBenchmark.Plan plan) {
        var bound = new LinkedHashMap<AEKey, BigInteger>(snapshot.stock);
        bound.merge(snapshot.root, snapshot.requested, BigInteger::add);
        for (var amounts : List.of(plan.used(), plan.missing(), plan.emitted()))
            amounts.forEach((key, count) -> bound.merge(key, count, BigInteger::add));
        var unique = new HashSet<Pattern>();
        BigInteger work = BigInteger.ZERO;
        for (var patterns : snapshot.producers.values()) for (var pattern : patterns) if (unique.add(pattern)) {
            var count = plan.patterns().getOrDefault(pattern.id, BigInteger.ZERO);
            work = work.add(count.multiply(BigInteger.valueOf(pattern.inputs.size() + 1L)));
            for (var output : pattern.outputs)
                bound.merge(output.what(), count.multiply(BigInteger.valueOf(output.amount())), BigInteger::add);
            for (var input : pattern.inputs) bound.merge(input.key,
                    count.multiply(BigInteger.valueOf(input.amount)).multiply(BigInteger.valueOf(input.multiplier)), BigInteger::add);
        }
        // AE2 charges bytes with doubles. Reject precision-risk inputs as well as long overflow.
        var precise = BigInteger.ONE.shiftLeft(50);
        if (plan.bytes().compareTo(precise) > 0 || bound.values().stream().anyMatch(value -> value.compareTo(precise) > 0))
            return "WIDE_BASELINE_INTERMEDIATE: conservative long/double safety bound exceeded; no saturation";
        if (work.compareTo(MAX_WORK) > 0) return "BASELINE_WORK_LIMIT: more than 100000 frozen pattern/input operations";
        return "";
    }
}
