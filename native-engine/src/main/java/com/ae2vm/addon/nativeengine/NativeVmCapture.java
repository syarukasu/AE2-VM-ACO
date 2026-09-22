package com.ae2vm.addon.nativeengine;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.syaru.ae2vm.exact.ExactBranchBytecode;
import com.syaru.ae2vm.exact.ExactBranchInputRules;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import net.minecraft.world.level.Level;

/** VM-owned per-order capture. Only callServer reads live AE2/provider/recipe data. */
final class NativeVmCapture implements ExactBranchInputRules<AEKey> {
    private final IGrid grid;
    private final Level level;
    private final Thread worker = Thread.currentThread();
    private final long epoch;
    private final long recipeEpoch;
    private final VmAccounting.Stock stock;
    private final KeyIndex<AEKey> stockKeys = newIndex();
    private final Map<AEKey, List<ExactBranchBytecode<AEKey>>> programs = new LinkedHashMap<>();
    private final Map<AEKey, List<IPatternDetails>> producers = new LinkedHashMap<>();
    private final Map<AEKey, Boolean> emitters = new LinkedHashMap<>();
    private final Map<IPatternDetails, ExactBranchBytecode<AEKey>> codeByPattern = new IdentityHashMap<>();
    private final Map<String, Captured> patterns = new LinkedHashMap<>();
    private final Map<Slot, Input<AEKey>> inputs = new LinkedHashMap<>();
    private final Map<Query, Observation<AEKey>> observations = new LinkedHashMap<>();

    NativeVmCapture(IGrid grid, Level level, VmAccounting accounting) {
        this.grid = grid;
        this.level = level;
        var start = callServer(() -> {
            var counts = accounting == null
                    ? ordinaryStock(grid.getStorageService().getInventory().getAvailableStacks())
                    : accounting.exactStock(grid);
            return new Start(NativeVm.epoch(grid), NativeVm.recipeEpoch(), counts);
        }, false);
        epoch = start.epoch();
        recipeEpoch = start.recipes();
        stock = start.stock();
        stock.amounts().keySet().forEach(stockKeys::add);
    }

    private static VmAccounting.Stock ordinaryStock(KeyCounter values) {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        for (var entry : values) {
            if (entry.getLongValue() < 0) throw new IllegalArgumentException("negative native stock");
            if (entry.getLongValue() > 0) result.put(entry.getKey(), BigInteger.valueOf(entry.getLongValue()));
        }
        return new VmAccounting.Stock(result, true, result.keySet());
    }

    BigInteger amount(AEKey key) { return stock.amount(key); }

    boolean emittable(AEKey key) {
        return emitters.computeIfAbsent(key, k -> callServer(() -> grid.getCraftingService().canEmitFor(k)));
    }

    List<ExactBranchBytecode<AEKey>> candidates(AEKey key) {
        return programs.computeIfAbsent(key, k -> {
            bounded(programs.size());
            var current = callServer(() -> List.copyOf(grid.getCraftingService().getCraftingFor(k)));
            producers.put(k, current);
            List<ExactBranchBytecode<AEKey>> result = new ArrayList<>();
            for (var pattern : current) {
                var code = codeByPattern.get(pattern);
                if (code == null) {
                    var shape = callServer(() -> shape(pattern));
                    String id = "vm-" + patterns.size();
                    List<ExactBranchBytecode.InputSlot<AEKey>> slots = new ArrayList<>();
                    for (var input : shape.inputs()) {
                        List<ExactBranchBytecode.Stack<AEKey>> alternatives = new ArrayList<>();
                        for (var template : input.templates()) alternatives.add(new ExactBranchBytecode.Stack<>(
                                template.what(), BigInteger.valueOf(template.amount()).multiply(input.multiplier())));
                        slots.add(new ExactBranchBytecode.InputSlot<>(alternatives,
                                BigInteger.valueOf(input.templates().get(0).amount())));
                    }
                    Map<AEKey, BigInteger> outputs = new LinkedHashMap<>();
                    for (var output : shape.outputs()) outputs.merge(output.what(),
                            BigInteger.valueOf(output.amount()), BigInteger::add);
                    code = new ExactBranchBytecode<>(id, slots, outputs);
                    patterns.put(id, new Captured(pattern, shape));
                    codeByPattern.put(pattern, code);
                }
                result.add(code);
            }
            return List.copyOf(result);
        });
    }

    @Override public Input<AEKey> input(ExactBranchBytecode<AEKey> pattern, int index) {
        return inputs.computeIfAbsent(new Slot(pattern.id(), index), slot -> {
            bounded(inputs.size());
            Captured captured = patterns.get(slot.id());
            return callServer(() -> {
                var source = captured.pattern().getInputs()[index];
                var templates = captured.shape().inputs().get(index).templates().stream()
                        .map(t -> new Template<>(t.what(), BigInteger.valueOf(t.amount()))).toList();
                AEKey encoded = templates.get(0).key();
                var service = grid.getCraftingService();
                AEKey crafted = selectCrafted(source, templates);
                return new Input<>(templates, crafted, service.canEmitFor(encoded), key -> observe(slot, key));
            });
        });
    }

    private AEKey selectCrafted(IPatternDetails.IInput source, List<Template<AEKey>> templates) {
        var service = grid.getCraftingService();
        AEKey encoded = templates.get(0).key();
        if (!service.canEmitFor(encoded) && service.getCraftingFor(encoded).isEmpty()) {
            for (var template : templates) {
                if (!template.amount().equals(templates.get(0).amount())) continue;
                AEKey selected = service.getFuzzyCraftable(template.key(), key -> source.isValid(key, level));
                if (selected != null) return selected;
            }
        }
        return encoded;
    }

    private Observation<AEKey> observe(Slot slot, AEKey key) {
        return observations.computeIfAbsent(new Query(slot, key), query -> {
            bounded(observations.size());
            return callServer(() -> read(patterns.get(slot.id()).pattern().getInputs()[slot.index()], key));
        });
    }

    private Observation<AEKey> read(IPatternDetails.IInput input, AEKey key) {
        boolean valid = input.isValid(key, level);
        boolean primary = input.getPossibleInputs()[0].what().equals(key);
        return new Observation<>(valid, valid || primary ? input.getRemainingKey(key) : null);
    }

    void validate() {
        // Stock is intentionally NOT compared: the result describes its snapshot;
        // AE2/exact execution must reserve against current storage at submission.
        for (var entry : producers.entrySet()) callServer(() -> {
            if (!entry.getValue().equals(List.copyOf(grid.getCraftingService().getCraftingFor(entry.getKey()))))
                throw new NativeVm.Changed("provider binding changed");
            return null;
        });
        for (var captured : patterns.values()) callServer(() -> {
            if (!captured.shape().equals(shape(captured.pattern()))) throw new NativeVm.Changed("pattern shape changed");
            return null;
        });
        for (var entry : inputs.entrySet()) callServer(() -> {
            var actual = patterns.get(entry.getKey().id()).pattern().getInputs()[entry.getKey().index()];
            var expected = entry.getValue();
            if (!Objects.equals(expected.craftedKey(), selectCrafted(actual, expected.templates()))
                    || expected.emittable() != grid.getCraftingService().canEmitFor(expected.templates().get(0).key()))
                throw new NativeVm.Changed("input selection changed");
            return null;
        });
        for (var entry : observations.entrySet()) callServer(() -> {
            var slot = entry.getKey().slot();
            var actual = patterns.get(slot.id()).pattern().getInputs()[slot.index()];
            if (!entry.getValue().equals(read(actual, entry.getKey().key()))) throw new NativeVm.Changed("input observation changed");
            return null;
        });
        for (var entry : emitters.entrySet()) callServer(() -> {
            if (entry.getValue() != grid.getCraftingService().canEmitFor(entry.getKey())) throw new NativeVm.Changed("emitter changed");
            return null;
        });
    }

    Map<String, IPatternDetails> bindings() {
        Map<String, IPatternDetails> result = new LinkedHashMap<>();
        patterns.forEach((id, value) -> result.put(id, value.pattern()));
        return Map.copyOf(result);
    }

    boolean widePendingOutputs(Map<String, BigInteger> executions) {
        Map<AEKey, BigInteger> total = new LinkedHashMap<>();
        for (var entry : executions.entrySet()) {
            var code = codeByPattern.get(patterns.get(entry.getKey()).pattern());
            for (var output : code.outputs().entrySet()) {
                total.merge(output.getKey(), output.getValue().multiply(entry.getValue()), BigInteger::add);
            }
        }
        return total.values().stream().anyMatch(value -> value.bitLength() > 63);
    }

    <T> T callServer(Supplier<T> action) { return callServer(action, true); }
    private <T> T callServer(Supplier<T> action, boolean validateEpoch) {
        var server = Objects.requireNonNull(level.getServer());
        Supplier<T> checked = () -> {
            if (worker.isInterrupted() || server.isStopped()) throw new CancellationException("VM cancelled");
            if (validateEpoch && (epoch != NativeVm.epoch(grid) || recipeEpoch != NativeVm.recipeEpoch()))
                throw new NativeVm.Changed("pattern/recipe epoch changed");
            return action.get();
        };
        if (server.isSameThread()) return checked.get();
        var future = server.submit(checked);
        try { return future.get(); }
        catch (InterruptedException interrupted) {
            future.cancel(false); Thread.currentThread().interrupt(); throw new CancellationException("VM interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException(failed.getCause());
        }
    }

    private static Shape shape(IPatternDetails pattern) {
        List<SlotShape> inputs = new ArrayList<>();
        for (var input : pattern.getInputs()) {
            var templates = List.of(input.getPossibleInputs().clone());
            if (input.getMultiplier() <= 0 || templates.isEmpty() || templates.stream().anyMatch(s -> s.amount() <= 0))
                throw new IllegalArgumentException("invalid native pattern input");
            inputs.add(new SlotShape(BigInteger.valueOf(input.getMultiplier()), templates));
        }
        var outputs = List.of(pattern.getOutputs().clone());
        if (outputs.isEmpty() || outputs.stream().anyMatch(s -> s.amount() <= 0))
            throw new IllegalArgumentException("invalid native pattern output");
        return new Shape(List.copyOf(inputs), outputs);
    }

    @Override public List<AEKey> storedFuzzyKeys(AEKey key) { return stockKeys.fuzzy(key); }
    @Override public KeyIndex<AEKey> newIndex() {
        return new KeyIndex<>() {
            private final KeyCounter keys = new KeyCounter();
            public void add(AEKey key) { keys.add(key, 0); }
            public List<AEKey> fuzzy(AEKey key) {
                return keys.findFuzzy(key, FuzzyMode.IGNORE_ALL).stream().map(Map.Entry::getKey).toList();
            }
            public List<AEKey> keys() {
                List<AEKey> result = new ArrayList<>();
                for (var entry : keys) result.add(entry.getKey());
                return result;
            }
        };
    }

    private static void bounded(int count) {
        if (count >= 1_048_576) throw new IllegalStateException("VM capture resident limit exceeded");
    }
    private record Start(long epoch, long recipes, VmAccounting.Stock stock) { }
    private record SlotShape(BigInteger multiplier, List<GenericStack> templates) { }
    private record Shape(List<SlotShape> inputs, List<GenericStack> outputs) { }
    private record Captured(IPatternDetails pattern, Shape shape) { }
    private record Slot(String id, int index) { }
    private record Query(Slot slot, AEKey key) { }
}
