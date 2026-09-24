package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * #208: ordered execution of compiled VM blocks. A captured one-craft effect is
 * not a recipe: stock alternatives, returns and failed branch trials are stateful.
 * This executor keeps those operations in bytecode order, inside the VM fork.
 */
final class VmInstructionExecution {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private final Function<AEKey, List<IPatternDetails>> resolver;
    private final Predicate<AEKey> emitters;
    private final Map<AEKey, BigInteger> initial;
    private final KeyCounter initialKeys = new KeyCounter();
    private final Node root;
    private BigInteger nodes = ZERO;
    private boolean multiple;
    private boolean simulate;
    private long instructions;
    private long work;
    private BigInteger skipped = ZERO;
    private final java.util.function.LongConsumer progress;
    private final List<Trace> traces = new ArrayList<>();

    VmInstructionExecution(CraftingBytecode request, VmSimulationState inventory,
            Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters,
            java.util.function.LongConsumer progress) {
        this.resolver = resolver;
        this.emitters = emitters;
        this.progress = progress;
        initial = new LinkedHashMap<>(inventory.initialStock());
        initial.put(request.getOutput(), ZERO);
        initial.keySet().forEach(key -> initialKeys.add(key, 1));
        root = new Node(request.getOutput(), ONE, null, null);
    }
    long instructions() { return instructions; }
    long work() { return work; }
    BigInteger skippedIterations() { return skipped; }

    VmCraftingPlan execute(BigInteger amount, boolean craftLess) {
        var result = attempt(amount, false);
        if (result != null) return result;
        if (craftLess) {
            BigInteger success = ZERO;
            for (var step = ONE.shiftLeft(amount.bitLength() - 1); step.signum() > 0; step = step.shiftRight(1)) {
                var candidate = success.add(step);
                if (candidate.compareTo(amount) >= 0) continue;
                var trial = attempt(candidate, false);
                if (trial != null) { success = candidate; result = trial; }
            }
            if (result != null) return result;
        }
        return Objects.requireNonNull(attempt(amount, true));
    }

    private VmCraftingPlan attempt(BigInteger amount, boolean simulation) {
        simulate = simulation;
        var state = new State();
        try { request(root, amount, state, null); }
        catch (Missing absent) { return null; }
        state.bytes = state.bytes.add(new VmByteCost(nodes.multiply(BigInteger.valueOf(8)), ONE));
        return new VmCraftingPlan(new VmStack(root.key, amount), state.bytes.ceiling(), simulation,
                multiple, new VmKeyCounter(state.used), new VmKeyCounter(state.emitted),
                new VmKeyCounter(state.missing), state.patterns);
    }

    private final class Node {
        final AEKey key;
        final BigInteger unit;
        final IPatternDetails.IInput input;
        final Node ancestor;
        final boolean emit;
        List<Process> processes;

        Node(AEKey encoded, BigInteger unit, IPatternDetails.IInput input, Node ancestor) {
            this.input = input;
            this.ancestor = ancestor;
            this.unit = unit;
            key = input instanceof PatternCompiler.DetachedInput detached ? detached.craftedKey() : encoded;
            emit = input instanceof PatternCompiler.DetachedInput detached ? detached.emittable() : emitters.test(encoded);
            nodes = nodes.add(ONE);
        }

        List<Process> processes() {
            if (processes != null) return processes;
            processes = new ArrayList<>();
            for (var pattern : resolver.apply(key)) {
                if (ancestor != null && ancestor.conflicts(pattern)) continue;
                processes.add(new Process(pattern, this));
            }
            multiple |= processes.size() > 1;
            return processes;
        }

        boolean conflicts(IPatternDetails pattern) {
            for (Node cursor = this; cursor != null; cursor = cursor.ancestor) {
                for (var output : pattern.getOutputs()) if (cursor.key.equals(output.what())) return true;
                for (var slot : pattern.getInputs())
                    if (cursor.key.equals(slot.getPossibleInputs()[0].what())) return true;
            }
            return false;
        }
    }

    private final class Process {
        final IPatternDetails pattern;
        final CraftingBytecode code;
        final List<Node> inputs = new ArrayList<>();
        final boolean single;
        final boolean containers;
        Process(IPatternDetails pattern, Node parent) {
            this.pattern = pattern;
            PatternCompiler.compileIfAbsent(pattern);
            code = Objects.requireNonNull(PatternCompiler.getCompiled(pattern));
            boolean limited = false, returned = false;
            for (var slot : pattern.getInputs()) {
                var primary = slot.getPossibleInputs()[0];
                inputs.add(new Node(primary.what(), BigInteger.valueOf(primary.amount()), slot, parent));
                if (slot.getRemainingKey(primary.what()) != null) { limited = true; returned = true; }
                for (var output : pattern.getOutputs()) if (output.what().equals(primary.what())) limited = true;
            }
            single = limited;
            containers = returned;
        }
        BigInteger output(AEKey key) {
            BigInteger amount = ZERO;
            for (var output : pattern.getOutputs()) if (output.what().equals(key))
                amount = amount.add(BigInteger.valueOf(output.amount()));
            if (amount.signum() <= 0) throw new IllegalStateException("VM producer does not produce requested key");
            return amount;
        }
    }

    private void request(Node node, BigInteger count, State state, Map<AEKey, BigInteger> returns) {
        checkpoint();
        work++;
        if ((work & 1023) == 0) progress.accept(work);
        state.charge(node.key, node.unit.multiply(count));
        var remaining = count;
        if (node.input == null) {
            remaining = remaining.subtract(state.extract(node.key, remaining));
        } else {
            // Use AE2's key index for ordering only; quantities are never stored as long.
            for (var template : node.input.getPossibleInputs()) {
                if (remaining.signum() == 0) break;
                var candidates = state.fuzzy(template.what()).iterator();
                var unit = BigInteger.valueOf(template.amount());
                while (remaining.signum() > 0 && candidates.hasNext()) {
                    // #215: do not allocate or observe a whole fuzzy-family cross product.
                    var batch = new ArrayList<AEKey>(128);
                    while (batch.size() < 128 && candidates.hasNext()) batch.add(candidates.next().getKey());
                    if (node.input instanceof PatternCompiler.DetachedInput detached) detached.prepareCandidates(batch);
                    for (var key : batch) {
                        if (remaining.signum() == 0) break;
                        if (!node.input.isValid(key, null)) continue;
                        var wanted = remaining.multiply(unit);
                        var available = state.read(key, wanted);
                        var taken = available.min(wanted).divide(unit);
                        if (taken.signum() == 0) continue;
                        state.extract(key, taken.multiply(unit));
                        remaining = remaining.subtract(taken);
                        addReturn(node, key, taken, returns);
                    }
                }
            }
        }
        if (remaining.signum() == 0) return;
        addReturn(node, node.key, remaining, returns);
        var demand = remaining.multiply(node.unit);
        if (node.emit) { merge(state.emitted, node.key, demand); return; }
        var choices = node.processes();
        for (var process : choices) {
            var output = process.output(node.key);
            var window = new Trace(state);
            traces.add(window);
            var windowOutput = ZERO;
            int iterations = 0, windowSize = 64;
            try {
            while (demand.signum() > 0) {
                checkpoint();
                boolean transaction = choices.size() > 1;
                var target = transaction ? state.copy() : state;
                var times = transaction || process.single ? ONE : PatternCompiler.ceilDiv(demand, output);
                BigInteger taken;
                try {
                    run(process, times, target);
                    taken = target.extract(node.key, demand);
                    if (taken.signum() == 0) throw new IllegalStateException("VM produced no requested output");
                } catch (Missing failure) {
                    if (!transaction) throw failure;
                    break;
                }
                if (transaction) state.adopt(target);
                demand = demand.subtract(taken);
                windowOutput = windowOutput.add(taken);
                // A multi-instruction period also covers changing tool damage and returned variants.
                var repeats = ZERO;
                traces.remove(traces.size() - 1);
                try {
                    window.minimum = new LinkedHashMap<>(state.lows.get(window));
                    if (demand.compareTo(windowOutput) >= 0) {
                        repeats = window.safeRepeats(state, demand.divide(windowOutput));
                        if (repeats.signum() > 0) window.replay(state, repeats);
                    }
                } finally { traces.add(window); }
                demand = demand.subtract(windowOutput.multiply(repeats));
                if (repeats.signum() > 0 || !window.before.stock.keySet().equals(state.stock.keySet())
                        || ++iterations >= windowSize) {
                    window.finish(state); traces.remove(traces.size() - 1);
                    window = new Trace(state); traces.add(window);
                    windowOutput = ZERO; iterations = 0;
                    windowSize = Math.min(1_048_576, windowSize * 2);
                }
            }
            } finally { window.finish(state); traces.remove(traces.size() - 1); }
            if (demand.signum() == 0) return;
        }
        if (!simulate) throw new Missing();
        merge(state.missing, node.key, demand);
    }

    private void addReturn(Node node, AEKey consumed, BigInteger count, Map<AEKey, BigInteger> returns) {
        if (returns == null || node.input == null) return;
        var key = node.input.getRemainingKey(consumed);
        if (key != null) merge(returns, key, count);
    }

    /** Execute original stack arithmetic/output opcodes plus slot-aware input/return instructions. */
    private void run(Process process, BigInteger times, State state) {
        var code = process.code;
        var buffer = ByteBuffer.wrap(code.getCode());
        var stack = new ArrayDeque<BigInteger>();
        stack.push(times);
        var returns = process.containers ? new LinkedHashMap<AEKey, BigInteger>() : null;
        while (buffer.hasRemaining()) {
            checkpoint(); instructions++;
            switch (Opcode.fromCode(buffer.get())) {
                case PUSH_LONG -> stack.push(BigInteger.valueOf(buffer.getLong()));
                case PUSH_BIG_INTEGER -> stack.push(code.getQuantity(Short.toUnsignedInt(buffer.getShort())));
                case DUP -> stack.push(stack.element());
                case POP -> stack.pop();
                case MUL -> stack.push(stack.pop().multiply(stack.pop()));
                case ADD -> stack.push(stack.pop().add(stack.pop()));
                case SUB -> { var right = stack.pop(); stack.push(stack.pop().subtract(right)); }
                case DIV_ROUNDUP -> { var right = stack.pop(); stack.push(PatternCompiler.ceilDiv(stack.pop(), right)); }
                case REQUEST_INPUT -> {
                    var input = process.inputs.get(Short.toUnsignedInt(buffer.getShort()));
                    var raw = stack.pop().divideAndRemainder(input.unit);
                    if (raw[1].signum() != 0) throw new IllegalStateException("non-integral VM input template");
                    request(input, raw[0], state, returns);
                }
                case RETURN_CONTAINERS -> {
                    if (returns != null) returns.forEach((key, amount) -> { state.insert(key, amount); state.charge(key, amount); });
                }
                case INSERT_OUTPUT -> state.insert(code.getConstantPool()[Short.toUnsignedInt(buffer.getShort())], stack.pop());
                case RECORD_PATTERN -> {
                    var pattern = code.getPatternPool()[Short.toUnsignedInt(buffer.getShort())];
                    var count = stack.pop();
                    merge(state.patterns, pattern, count);
                    state.bytes = state.bytes.add(new VmByteCost(count, ONE));
                }
                case RETURN, HALT -> { return; }
                default -> throw new IllegalStateException("Unexpected opcode in ordered VM pattern");
            }
        }
        throw new IllegalStateException("Unterminated VM program");
    }

    private final class State {
        final Map<AEKey, BigInteger> stock = new LinkedHashMap<>(), used = new LinkedHashMap<>(),
                emitted = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        final Map<IPatternDetails, BigInteger> patterns = new LinkedHashMap<>();
        final Map<Trace, Map<AEKey, BigInteger>> lows = new IdentityHashMap<>();
        final Map<Family, KeyCounter> keys = new HashMap<>();
        VmByteCost bytes = VmByteCost.ZERO;
        State copy() { var copy = new State(); copy.adopt(this); return copy; }
        void adopt(State other) {
            replace(stock, other.stock); replace(used, other.used); replace(emitted, other.emitted);
            replace(missing, other.missing); replace(patterns, other.patterns);
            lows.clear(); other.lows.forEach((trace, low) -> lows.put(trace, new LinkedHashMap<>(low)));
            keys.clear(); keys.putAll(other.keys); bytes = other.bytes;
        }
        BigInteger amount(AEKey key) { return stock.getOrDefault(key, initial.getOrDefault(key, ZERO)); }
        Collection<? extends Map.Entry<AEKey, ?>> fuzzy(AEKey key) {
            return keys.getOrDefault(Family.of(key), initialKeys).findFuzzy(key, FuzzyMode.IGNORE_ALL);
        }
        void index(AEKey key) {
            var family = Family.of(key);
            var source = keys.getOrDefault(family, initialKeys);
            if (source.get(key) > 0) return;
            // Copy only this fuzzy family on insertion, not the network-wide key index.
            var copy = new KeyCounter();
            for (var entry : source.findFuzzy(key, FuzzyMode.IGNORE_ALL)) copy.add(entry.getKey(), 1);
            copy.add(key, 1); keys.put(family, copy);
        }
        BigInteger read(AEKey key, BigInteger requested) {
            var available = amount(key);
            stock.putIfAbsent(key, available);
            index(key);
            for (var trace : traces) trace.read(key, available, requested, this);
            return available;
        }
        BigInteger extract(AEKey key, BigInteger requested) {
            var taken = read(key, requested).min(requested);
            var left = amount(key).subtract(taken);
            stock.put(key, left); index(key);
            lows.values().forEach(low -> low.merge(key, left, BigInteger::min));
            var required = initial.getOrDefault(key, ZERO).subtract(left);
            if (required.signum() > 0) used.merge(key, required, BigInteger::max);
            return taken;
        }
        void insert(AEKey key, BigInteger quantity) { stock.put(key, amount(key).add(quantity)); index(key); }
        void charge(AEKey key, BigInteger amount) {
            bytes = bytes.add(new VmByteCost(amount.multiply(BigInteger.valueOf(8)), BigInteger.valueOf(key.getType().getAmountPerByte())));
        }
    }

    /** Per-block comparison domain; partial reads deliberately require exact equality. */
    private final class Trace {
        final State before;
        final Map<AEKey, BigInteger> lower = new HashMap<>(), upper = new HashMap<>();
        Map<AEKey, BigInteger> minimum;
        Trace(State state) {
            before = state.copy();
            state.lows.put(this, new LinkedHashMap<>(state.stock));
        }
        void finish(State state) { minimum = state.lows.remove(this); }
        void read(AEKey key, BigInteger available, BigInteger requested, State current) {
            var start = before.amount(key);
            var offset = available.subtract(start);
            if (available.compareTo(requested) >= 0) {
                lower.merge(key, requested.subtract(offset), BigInteger::max);
            } else {
                lower.merge(key, start, BigInteger::max);
                upper.merge(key, start, BigInteger::min);
            }
        }
        BigInteger safeRepeats(State after, BigInteger limit) {
            // New fuzzy members can change iteration order. Re-observe once before replay.
            if (!before.stock.keySet().equals(after.stock.keySet())) return ZERO;
            for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = after.amount(key).subtract(start);
                var min = lower.get(key);
                var max = upper.get(key);
                if (delta.signum() < 0) limit = limit.min(start.subtract(min).divide(delta.negate()));
                if (delta.signum() > 0 && max != null) limit = limit.min(max.subtract(start).divide(delta));
            }
            return limit.max(ZERO);
        }
        void replay(State state, BigInteger repeats) {
            skipped = skipped.add(repeats);
            var after = state.copy();
            // Extend enclosing guards across every skipped iteration, including failed trials.
            for (var parent : traces) for (var key : lower.keySet()) {
                var start = before.amount(key);
                var delta = after.amount(key).subtract(start);
                var offset = start.subtract(parent.before.amount(key));
                var min = lower.get(key).subtract(offset).subtract(delta.min(ZERO).multiply(repeats));
                parent.lower.merge(key, min, BigInteger::max);
                var max = upper.get(key);
                if (max != null) parent.upper.merge(key,
                        max.subtract(offset).subtract(delta.max(ZERO).multiply(repeats)), BigInteger::min);
            }
            for (var key : after.stock.keySet()) {
                var delta = after.amount(key).subtract(before.amount(key));
                state.stock.put(key, after.stock.get(key).add(delta.multiply(repeats)));
                var low = minimum.getOrDefault(key, before.amount(key));
                if (delta.signum() < 0) low = low.add(delta.multiply(repeats));
                var peak = initial.getOrDefault(key, ZERO).subtract(low);
                if (peak.signum() > 0) state.used.merge(key, peak, BigInteger::max);
                var actualLow = low;
                state.lows.values().forEach(values -> values.merge(key, actualLow, BigInteger::min));
            }
            scaleDelta(state.patterns, before.patterns, after.patterns, repeats);
            scaleDelta(state.emitted, before.emitted, after.emitted, repeats);
            scaleDelta(state.missing, before.missing, after.missing, repeats);
            state.bytes = after.bytes.add(after.bytes.subtract(before.bytes).multiply(repeats));
        }
    }

    private static <K> void merge(Map<K, BigInteger> map, K key, BigInteger value) {
        if (value.signum() != 0) map.merge(key, value, BigInteger::add);
    }
    private static <K> void replace(Map<K, BigInteger> target, Map<K, BigInteger> source) { target.clear(); target.putAll(source); }
    private static <K> void scaleDelta(Map<K, BigInteger> target, Map<K, BigInteger> before,
            Map<K, BigInteger> after, BigInteger repeats) {
        after.forEach((key, value) -> merge(target, key, value.subtract(before.getOrDefault(key, ZERO)).multiply(repeats)));
    }
    private static void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM calculation cancelled");
    }
    private static final class Missing extends RuntimeException {
        Missing() { super(null, null, false, false); }
    }
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
}
