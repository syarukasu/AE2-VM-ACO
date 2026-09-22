package com.syaru.ae2vm.exact;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Issue #208: detached branch interpreter with transactional BigInteger stock and bytecode recipes. */
public final class ExactBranchVM<K> {
    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final int MAX_NODES = 1_048_576;
    private final Function<K, List<ExactBranchBytecode<K>>> candidates;
    private final Predicate<K> emitter;
    private final Function<K, BigInteger> inventory;
    private final ToLongFunction<K> amountPerByte;
    private final java.util.function.IntConsumer guard;
    private final ExactBranchInputRules<K> inputRules;
    private final BigInteger maximum;
    private final Node root;
    private boolean simulation;
    private boolean multiplePaths;
    private int nodes;
    private int work;
    private BigInteger skipped = ZERO;
    private long instructions;

    public ExactBranchVM(K root, Function<K, List<ExactBranchBytecode<K>>> candidates,
            Predicate<K> emitter, Function<K, BigInteger> inventory,
            ToLongFunction<K> amountPerByte, java.util.function.IntConsumer guard, BigInteger maximum) {
        this(root, candidates, emitter, inventory, amountPerByte, guard, maximum, null);
    }

    public ExactBranchVM(K root, Function<K, List<ExactBranchBytecode<K>>> candidates,
            Predicate<K> emitter, Function<K, BigInteger> inventory,
            ToLongFunction<K> amountPerByte, java.util.function.IntConsumer guard, BigInteger maximum, ExactBranchInputRules<K> inputRules) {
        this.candidates = candidates;
        this.emitter = emitter;
        this.inventory = inventory;
        this.amountPerByte = amountPerByte;
        this.guard = guard;
        this.maximum = java.util.Objects.requireNonNull(maximum);
        if (maximum.signum() <= 0) throw new IllegalArgumentException("positive limit required");
        this.inputRules = inputRules;
        this.root = new Node(root, ONE, null, null);
    }

    public Result<K> plan(BigInteger requested, boolean craftLess) {
        check(requested);
        if (requested.signum() <= 0) throw new IllegalArgumentException("positive request required");
        State result = attempt(requested, false);
        BigInteger amount = requested;
        if (result == null && craftLess) {
            // AE2 retains the already expanded tree while trying smaller orders.
            BigInteger success = ZERO;
            for (BigInteger step = ONE.shiftLeft(requested.bitLength() - 1);
                    step.signum() > 0; step = step.shiftRight(1)) {
                checkpoint();
                BigInteger test = success.add(step);
                if (test.compareTo(requested) < 0) {
                    State trial = attempt(test, false);
                    if (trial != null) {
                        result = trial;
                        amount = success = test;
                    }
                }
            }
        }
        if (result == null) result = attempt(requested, true);
        return new Result<>(root.key, amount, Map.copyOf(result.crafts), Map.copyOf(result.required),
                Map.copyOf(result.emitted), Map.copyOf(result.missing), Map.copyOf(result.stackCharges),
                result.integerCharges, work, result.bytes, multiplePaths, skipped, instructions);
    }

    private State attempt(BigInteger amount, boolean simulate) {
        simulation = simulate;
        State state = new State(key -> key.equals(root.key) ? ZERO : inventory.apply(key));
        if (inputRules != null) {
            state.cacheFuzzy(root.key);
            state.modifiedKeys.add(root.key);
        }
        try {
            request(root, state, amount, null);
        } catch (Unavailable unavailable) {
            return null;
        }
        state.charge(null, BigInteger.valueOf(nodes).multiply(BigInteger.valueOf(8)), ONE);
        return state;
    }

    private void request(Node node, State state, BigInteger amount, Map<K, BigInteger> containers) {
        checkpoint();
        state.charge(node.key, amount, node.quantum);
        BigInteger deficit = amount;
        if (node.input == null) {
            deficit = amount.subtract(state.extract(node.key, amount, node.quantum));
        } else {
            BigInteger remaining = amount.divide(node.quantum);
            for (var template : node.input.templates()) {
                for (K key : state.fuzzy(template.key())) {
                    checkpoint();
                    var observation = node.input.observe().apply(key);
                    if (!observation.valid()) continue;
                    BigInteger taken = state.extract(key, check(remaining.multiply(template.amount())),
                            template.amount()).divide(template.amount());
                    addRemainder(containers, observation.remainder(), taken);
                    remaining = remaining.subtract(taken);
                    if (remaining.signum() == 0) return;
                }
            }
            addRemainder(containers, node.input.observe().apply(node.key).remainder(), remaining);
            deficit = check(remaining.multiply(node.quantum));
        }
        if (deficit.signum() == 0) return;
        if (node.input == null ? emitter.test(node.key) : node.input.emittable()) {
            merge(state.emitted, node.key, deficit);
            return;
        }
        node.expand();
        if (node.processes.size() == 1 && !node.processes.get(0).limited) {
            Process process = node.processes.get(0);
            BigInteger times = ceilDiv(deficit,
                    process.pattern.outputAmount(node.key), "branch/times");
            process.request(state, times);
            deficit = deficit.subtract(state.extract(node.key, deficit, ONE));
        } else {
            for (Process process : node.processes) {
                deficit = repeat(process, node.key, state, deficit, node.processes.size() > 1);
                if (deficit.signum() == 0) return;
            }
        }
        if (deficit.signum() > 0) {
            if (!simulation) throw Unavailable.INSTANCE;
            merge(state.missing, node.key, deficit);
        }
    }

    private void addRemainder(Map<K, BigInteger> containers, K key, BigInteger amount) {
        if (containers != null && key != null) merge(containers, key, amount);
    }

    private BigInteger repeat(Process process, K output, State parent, BigInteger deficit,
            boolean transactional) {
        State block = new State(parent);
        BigInteger removed = ZERO;
        int iterations = 0;
        while (deficit.signum() > 0) {
            checkpoint();
            State trial = transactional ? new State(block) : block;
            try {
                process.request(trial, ONE);
            } catch (Unavailable failure) {
                if (!transactional) throw failure;
                block.inheritReads(trial, ONE);
                parent.applyBlock(block, ONE);
                return deficit;
            }
            BigInteger extracted = trial.extract(output, deficit, ONE);
            if (extracted.signum() <= 0) throw new IllegalStateException("producer supplied no output");
            if (transactional) block.apply(trial, ONE);
            deficit = deficit.subtract(extracted);
            removed = removed.add(extracted);
            iterations++;
            BigInteger extra = block.repeats(deficit.divide(removed));
            // Issue #208: even small nested repetitions use the same proven read interval.
            // ByteRepeat preserves the original floating-point addition boundaries.
            if (extra.signum() > 0 || iterations == 256 || deficit.signum() == 0) {
                parent.applyBlock(block, extra.add(ONE));
                deficit = deficit.subtract(check(removed.multiply(extra)));
                skipped = check(skipped.add(extra.multiply(BigInteger.valueOf(iterations))));
                block = new State(parent);
                removed = ZERO;
                iterations = 0;
            }
        }
        return deficit;
    }

    private final class Node {
        private final K key;
        private final BigInteger quantum;
        private final Node parent;
        private final ExactBranchInputRules.Input<K> input;
        private final int depth;
        private List<Process> processes;

        private Node(K key, BigInteger quantum, Node parent, ExactBranchInputRules.Input<K> input) {
            this.key = key;
            this.quantum = quantum;
            this.parent = parent;
            this.input = input;
            this.depth = parent == null ? 0 : parent.depth + 1;
            if (++nodes > MAX_NODES || depth > 256) {
                throw new UnsupportedOperationException("branching tree expansion limit exceeded");
            }
        }

        private void expand() {
            if (processes != null) return;
            processes = new ArrayList<>();
            for (ExactBranchBytecode<K> pattern : candidates.apply(key)) {
                checkpoint();
                boolean recursive = false;
                for (Node ancestor = parent; ancestor != null; ancestor = ancestor.parent) {
                    K ancestorKey = ancestor.key;
                    if (pattern.outputs().containsKey(ancestor.key) || pattern.inputs().stream()
                            .anyMatch(slot -> slot.alternatives().get(0).key().equals(ancestorKey))) {
                        recursive = true;
                        break;
                    }
                }
                if (!recursive) processes.add(new Process(pattern, this));
            }
            if (processes.size() > 1) multiplePaths = true;
        }
    }

    private final class Process {
        private final ExactBranchBytecode<K> pattern;
        private final List<Node> inputs = new ArrayList<>();
        private final boolean limited;
        private final boolean containers;

        private Process(ExactBranchBytecode<K> pattern, Node parent) {
            this.pattern = pattern;
            boolean selfInput = false;
            boolean remainingItems = false;
            for (int index = 0; index < pattern.inputs().size(); index++) {
                var slot = pattern.inputs().get(index);
                if (inputRules == null && slot.alternatives().size() != 1) {
                    throw new UnsupportedOperationException("branching requires captured exact inputs: " + pattern.id());
                }
                var input = slot.alternatives().get(0);
                var rules = inputRules == null ? null : inputRules.input(pattern, index);
                inputs.add(new Node(rules == null ? input.key() : rules.craftedKey(),
                        slot.templateAmount(), parent, rules));
                selfInput |= pattern.outputs().containsKey(input.key());
                remainingItems |= rules != null && rules.observe().apply(input.key()).remainder() != null;
            }
            containers = remainingItems;
            limited = selfInput || containers;
        }

        private void request(State state, BigInteger times) {
            checkpoint();
            Map<K, BigInteger> returned = containers ? new LinkedHashMap<>() : null;
            int pc = 0;
            for (;;) {
                if (instructions < Long.MAX_VALUE) instructions++;
                checkpoint();
                switch (pattern.word(pc++)) {
                    case ExactBranchBytecode.REQUEST -> {
                        int slot = pattern.word(pc++);
                        BigInteger amount = check(times.multiply(pattern.constant(pattern.word(pc++))));
                        ExactBranchVM.this.request(inputs.get(slot), state, amount, returned);
                    }
                    case ExactBranchBytecode.RETURN_CONTAINERS -> {
                        if (returned != null) {
                            var order = inputRules.newIndex();
                            returned.keySet().forEach(order::add);
                            for (K key : order.keys()) {
                                state.insert(key, returned.get(key));
                                state.charge(key, returned.get(key), ONE);
                            }
                        }
                    }
                    case ExactBranchBytecode.INSERT -> {
                        K key = pattern.outputKey(pattern.word(pc++));
                        state.insert(key, check(times.multiply(pattern.constant(pattern.word(pc++)))));
                    }
                    case ExactBranchBytecode.RECORD -> {
                        merge(state.crafts, pattern.id(), times);
                        state.charge(null, times, ONE);
                    }
                    case ExactBranchBytecode.HALT -> { return; }
                    default -> throw new IllegalStateException("unknown branch VM instruction");
                }
            }
        }
    }

    /** A child transaction also records the interval of starting stock that reproduces every read. */
    private final class State {
        private final Function<K, BigInteger> source;
        private final Function<K, List<K>> sourceFuzzy;
        private final ExactBranchInputRules.KeyIndex<K> originalKeys;
        private final ExactBranchInputRules.KeyIndex<K> modifiedKeys;
        private boolean membershipChanged;
        private final Map<K, BigInteger> initial = new LinkedHashMap<>();
        private final Map<K, BigInteger> stock = new LinkedHashMap<>();
        private final Map<K, BigInteger> required = new LinkedHashMap<>();
        private final Map<K, BigInteger> emitted = new LinkedHashMap<>();
        private final Map<K, BigInteger> missing = new LinkedHashMap<>();
        private final Map<String, BigInteger> crafts = new LinkedHashMap<>();
        private final Map<K, Interval> reads = new LinkedHashMap<>();
        private final Map<K, BigInteger> stackCharges = new LinkedHashMap<>();
        private final List<ByteOperation> byteOperations = new ArrayList<>();
        private BigInteger integerCharges = ZERO;
        private double bytes;

        private State(Function<K, BigInteger> source) {
            this(source, inputRules == null ? null : inputRules::storedFuzzyKeys);
        }

        private State(State parent) { this(parent::available, parent::fuzzy); }

        private State(Function<K, BigInteger> source, Function<K, List<K>> sourceFuzzy) {
            this.source = source;
            this.sourceFuzzy = sourceFuzzy;
            originalKeys = inputRules == null ? null : inputRules.newIndex();
            modifiedKeys = inputRules == null ? null : inputRules.newIndex();
        }

        private void cacheFuzzy(K key) {
            if (inputRules == null || !originalKeys.fuzzy(key).isEmpty()) return;
            boolean any = false;
            for (K candidate : sourceFuzzy.apply(key)) {
                BigInteger amount = check(source.apply(candidate));
                any |= amount.signum() != 0;
                initial.put(candidate, amount);
                stock.put(candidate, amount);
                originalKeys.add(candidate);
                modifiedKeys.add(candidate);
            }
            if (!any) originalKeys.add(key);
        }

        private List<K> fuzzy(K key) {
            if (inputRules == null) return List.of(key);
            cacheFuzzy(key);
            return modifiedKeys.fuzzy(key);
        }

        private BigInteger initial(K key) {
            if (inputRules != null) {
                cacheFuzzy(key);
                return initial.getOrDefault(key, ZERO);
            }
            return initial.computeIfAbsent(key, k -> check(source.apply(k)));
        }

        private BigInteger available(K key) { return stock.getOrDefault(key, initial(key)); }

        private BigInteger delta(K key) { return available(key).subtract(initial(key)); }

        private BigInteger extract(K key, BigInteger amount, BigInteger template) {
            BigInteger quantum = template;
            BigInteger before = available(key);
            BigInteger taken = before.min(amount).divide(quantum).multiply(quantum);
            BigInteger upper = taken.equals(amount) ? null : taken.add(quantum).subtract(ONE);
            constrain(key, taken.subtract(delta(key)), upper == null ? null : upper.subtract(delta(key)));
            stock.put(key, before.subtract(taken));
            BigInteger needed = initial(key).subtract(available(key));
            if (needed.signum() > 0) required.merge(key, needed, BigInteger::max);
            return taken;
        }

        private void insert(K key, BigInteger amount) {
            BigInteger before = available(key);
            if (modifiedKeys != null) {
                if (!modifiedKeys.fuzzy(key).contains(key)) membershipChanged = true;
                modifiedKeys.add(key);
            }
            stock.put(key, check(before.add(amount)));
        }

        private void charge(K key, BigInteger amount, BigInteger quantum) {
            if (key == null) {
                integerCharges = check(integerCharges.add(amount));
                addBytes(new ByteAdd(amount.doubleValue()));
            } else {
                merge(stackCharges, key, amount);
                long divisor = amountPerByte.applyAsLong(key);
                if (divisor <= 0) throw new IllegalArgumentException("invalid amountPerByte");
                addBytes(new ByteAdd(quantum.doubleValue() * amount.divide(quantum).doubleValue()
                        / divisor * 8));
            }
        }

        private void addBytes(ByteOperation operation) {
            bytes = operation.apply(bytes, guard);
            byteOperations.add(operation);
        }

        private void constrain(K key, BigInteger lower, BigInteger upper) {
            Interval old = reads.get(key);
            if (old != null) {
                lower = lower.max(old.lower);
                if (old.upper != null) upper = upper == null ? old.upper : upper.min(old.upper);
            }
            reads.put(key, new Interval(lower, upper));
        }

        private void inheritReads(State child, BigInteger times) {
            child.reads.forEach((key, interval) -> {
                BigInteger drift = child.delta(key).multiply(times.subtract(ONE));
                BigInteger offset = delta(key);
                constrain(key, interval.lower.subtract(offset).subtract(drift.min(ZERO)),
                        interval.upper == null ? null
                                : interval.upper.subtract(offset).subtract(drift.max(ZERO)));
            });
        }

        private void apply(State child, BigInteger times) {
            apply(child, times, false);
        }

        private void applyBlock(State child, BigInteger times) {
            apply(child, times, true);
        }

        private void apply(State child, BigInteger times, boolean inlineBytes) {
            inheritReads(child, times);
            child.required.forEach((key, needed) -> {
                BigInteger drift = child.delta(key).multiply(times.subtract(ONE));
                BigInteger peak = initial(key).subtract(available(key)).add(needed).subtract(drift.min(ZERO));
                if (peak.signum() > 0) required.merge(key, check(peak), BigInteger::max);
            });
            Iterable<K> changed = child.modifiedKeys == null ? child.stock.keySet() : child.modifiedKeys.keys();
            for (K key : changed) {
                BigInteger delta = child.delta(key).multiply(times);
                if (delta.signum() > 0) insert(key, check(delta));
                else if (delta.signum() < 0) stock.put(key, check(available(key).add(delta)));
            }
            membershipChanged |= child.membershipChanged;
            child.crafts.forEach((key, value) -> merge(crafts, key, check(value.multiply(times))));
            child.emitted.forEach((key, value) -> merge(emitted, key, check(value.multiply(times))));
            child.missing.forEach((key, value) -> merge(missing, key, check(value.multiply(times))));
            child.stackCharges.forEach((key, value) -> merge(stackCharges, key, check(value.multiply(times))));
            integerCharges = check(integerCharges.add(child.integerCharges.multiply(times)));
            // A repeated block is not another AE2 child inventory. Preserve every addition boundary.
            addBytes(new ByteRepeat(inlineBytes ? List.copyOf(child.byteOperations)
                    : List.of(new ByteAdd(child.bytes)), times));
        }

        private BigInteger repeats(BigInteger maximum) {
            if (membershipChanged) return ZERO;
            BigInteger result = maximum;
            for (var entry : reads.entrySet()) {
                BigInteger step = delta(entry.getKey());
                Interval interval = entry.getValue();
                BigInteger start = initial(entry.getKey());
                if (step.signum() < 0) {
                    result = result.min(start.subtract(interval.lower).divide(step.negate()));
                } else if (step.signum() > 0 && interval.upper != null) {
                    result = result.min(interval.upper.subtract(start).divide(step));
                }
            }
            return result.max(ZERO);
        }

    }

    private void checkpoint() {
        // Issue #190: cumulative work is not resident memory. Keep yielding/cancellation
        // beyond the old cutoff. Only this diagnostic counter saturates, never quantities.
        if (work < Integer.MAX_VALUE) work++;
        guard.accept(work);
    }

    private BigInteger check(BigInteger value) {
        if (value == null || value.signum() < 0 || value.compareTo(maximum) > 0) throw new IllegalArgumentException("exact branch VM count outside caller limit");
        return value;
    }

    private <T> void merge(Map<T, BigInteger> map, T key, BigInteger value) {
        if (value.signum() > 0) map.put(key, check(map.getOrDefault(key, ZERO).add(value)));
    }

    public record Result<K>(K root, BigInteger requested, Map<String, BigInteger> crafts,
            Map<K, BigInteger> used, Map<K, BigInteger> emitted, Map<K, BigInteger> missing,
            Map<K, BigInteger> stackCharges, BigInteger integerCharges, int work,
            double legacyBytes, boolean multiplePaths, BigInteger skippedIterations, long instructions) { }

    private static BigInteger ceilDiv(BigInteger value, BigInteger divisor, String context) {
        if (value.signum() < 0 || divisor.signum() <= 0) throw new IllegalArgumentException(context);
        var qr = value.divideAndRemainder(divisor);
        return qr[1].signum() == 0 ? qr[0] : qr[0].add(ONE);
    }

    private record Interval(BigInteger lower, BigInteger upper) { }

    private interface ByteOperation {
        double apply(double start, java.util.function.IntConsumer guard);
    }

    private record ByteAdd(double amount) implements ByteOperation {
        @Override
        public double apply(double start, java.util.function.IntConsumer guard) { return start + amount; }
    }

    /** Repeated IEEE-754 additions, not multiplication of an already rounded byte total. */
    private record ByteRepeat(List<ByteOperation> operations, BigInteger count) implements ByteOperation {
        @Override
        public double apply(double start, java.util.function.IntConsumer guard) {
            BigInteger remaining = count;
            double value = start;
            while (remaining.signum() > 0) {
                guard.accept(0);
                double next = cycle(value, guard);
                remaining = remaining.subtract(ONE);
                if (next == value || !Double.isFinite(next)) return next;
                double previous = value;
                value = next;
                if (remaining.signum() == 0 || Math.getExponent(previous) != Math.getExponent(value)) continue;
                double after = cycle(value, guard);
                double step = value - previous;
                if (Math.getExponent(after) != Math.getExponent(value) || after - value != step) continue;
                // Inside one binade, all additions are integer ULP steps. Halfway ties
                // have a stable parity after the first cycle. Stop before crossing its boundary.
                double ulp = Math.ulp(value);
                long position = (long) (value / ulp);
                long increment = (long) (step / ulp);
                if (increment <= 0) continue;
                long top = Math.getExponent(value) == Double.MIN_EXPONENT - 1
                        ? (1L << 52) - 1 : (1L << 53) - 1;
                long possible = (top - position) / increment;
                long repeats = remaining.min(BigInteger.valueOf(possible)).longValueExact();
                if (repeats > 0) {
                    value = (position + increment * repeats) * ulp;
                    remaining = remaining.subtract(BigInteger.valueOf(repeats));
                }
            }
            return value;
        }

        private double cycle(double start, java.util.function.IntConsumer guard) {
            for (ByteOperation operation : operations) start = operation.apply(start, guard);
            return start;
        }
    }

    static double repeatedBytes(double start, double[] amounts, BigInteger times) {
        List<ByteOperation> operations = new ArrayList<>();
        for (double amount : amounts) operations.add(new ByteAdd(amount));
        return new ByteRepeat(operations, times).apply(start, ignored -> {});
    }

    private static final class Unavailable extends RuntimeException {
        private static final Unavailable INSTANCE = new Unavailable();
        private Unavailable() { super(null, null, false, false); }
    }
}
