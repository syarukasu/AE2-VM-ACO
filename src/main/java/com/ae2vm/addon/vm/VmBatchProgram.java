package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** #236: a compiled consume/produce transfer, not a multiplied inventory trial. */
final class VmBatchProgram {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    /** Canonical max(stock - consumed, 0) + produced; consumed is the prefix reserve, not gross use. */
    record Transfer(BigInteger consumed, BigInteger produced, boolean emit) {
        Transfer {
            if (consumed.signum() < 0 || produced.signum() < 0)
                throw new IllegalArgumentException("negative inventory transfer bound");
        }
        BigInteger required(BigInteger repetitions) {
            if (repetitions.signum() <= 0) throw new IllegalArgumentException("nonpositive repetitions");
            return consumed.add(consumed.subtract(produced).max(ZERO).multiply(repetitions.subtract(ONE)));
        }
        BigInteger funded(BigInteger available, BigInteger limit) {
            if (available.compareTo(consumed) < 0) return ZERO;
            var loss = consumed.subtract(produced);
            return loss.signum() <= 0 ? limit : limit.min(available.subtract(consumed).divide(loss).add(ONE));
        }
        BigInteger shortage(BigInteger available, BigInteger repetitions) {
            return required(repetitions).subtract(available).max(ZERO);
        }
        BigInteger after(BigInteger available, BigInteger repetitions) {
            return available.add(produced.subtract(consumed).multiply(repetitions))
                    .add(shortage(available, repetitions));
        }
    }
    final Map<AEKey, Transfer> transfers;
    final Map<AEKey, BigInteger> units;
    final BigInteger output;
    final VmByteCost bytes;
    final Map<IPatternDetails, BigInteger> crafts;
    final Set<AEKey> intermediates;
    final Shape shape;
    final boolean repeatCompatible;
    record Shape(IPatternDetails pattern, List<Shape> children) {
        Shape { children = Collections.unmodifiableList(new ArrayList<>(children)); }
    }

    private VmBatchProgram(Map<AEKey, Transfer> transfers, Map<AEKey, BigInteger> units, BigInteger output, VmByteCost bytes,
            Map<IPatternDetails, BigInteger> crafts, Set<AEKey> intermediates, Shape shape,
            boolean repeatCompatible) {
        this.transfers = Collections.unmodifiableMap(new LinkedHashMap<>(transfers));
        this.units = Map.copyOf(units);
        this.output = output;
        this.bytes = bytes;
        this.crafts = Collections.unmodifiableMap(new LinkedHashMap<>(crafts));
        this.intermediates = Set.copyOf(intermediates);
        this.shape = shape;
        this.repeatCompatible = repeatCompatible;
    }

    BigInteger usableStock(AEKey key, BigInteger stock) {
        var unit = units.getOrDefault(key, ONE);
        return unit.equals(ONE) ? stock : stock.subtract(stock.remainder(unit));
    }

    /** Order-local shape cache: stock amounts and completed results are never retained. */
    static final class Cache {
        private record Entry(AEKey requested, Map<AEKey, Boolean> boundaries, VmBatchProgram program) { }
        private final Map<IPatternDetails, List<Entry>> entries = new IdentityHashMap<>();
        private int count, members;
        VmBatchProgram compile(IPatternDetails pattern, AEKey requested,
                Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> stocked, Runnable checkpoint) {
            for (var entry : entries.getOrDefault(pattern, List.of())) {
                checkpoint.run();
                if (!entry.requested().equals(requested)) continue;
                boolean matches = true;
                for (var boundary : entry.boundaries().entrySet()) {
                    checkpoint.run();
                    if (stocked.test(boundary.getKey()) != boundary.getValue()) { matches = false; break; }
                }
                if (matches) return entry.program();
            }
            var boundaries = new LinkedHashMap<AEKey, Boolean>();
            var compiler = new Compiler(resolver, key -> boundaries.computeIfAbsent(key, stocked::test), checkpoint);
            var program = compiler.compile(pattern, requested);
            long weight = boundaries.size();
            for (var region : compiler.compiled.values()) if (region != null)
                weight += region.transfers.size() + region.units.size() + region.crafts.size() + region.intermediates.size() + region.shape.children().size();
            if (count < 512 && weight <= 65536 - members) {
                entries.computeIfAbsent(pattern, ignored -> new ArrayList<>())
                        .add(new Entry(requested, Map.copyOf(boundaries), program));
                count++;
                members += (int) weight;
            }
            return program;
        }
    }

    static final class Compiler {
        private final Function<AEKey, List<IPatternDetails>> resolver;
        private final Predicate<AEKey> stocked;
        private final Runnable checkpoint;
        private final Map<IPatternDetails, VmBatchProgram> compiled = new IdentityHashMap<>();
        private final Set<IPatternDetails> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Compiler(Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> stocked, Runnable checkpoint) {
            this.resolver = resolver; this.stocked = stocked; this.checkpoint = checkpoint;
        }
        VmBatchProgram compile(IPatternDetails pattern, AEKey requested) {
            checkpoint.run();
            if (compiled.containsKey(pattern)) {
                var result = compiled.get(pattern);
                return result != null && pattern.getPrimaryOutput().what().equals(requested) ? result : null;
            }
            if (!visiting.add(pattern)) return null;
            VmBatchProgram result;
            try { result = build(pattern, requested); }
            finally { visiting.remove(pattern); }
            compiled.put(pattern, result);
            return result;
        }
        private VmBatchProgram build(IPatternDetails pattern, AEKey requested) {
            for (var input : pattern.getInputs())
                if (!(input instanceof PatternCompiler.DetachedInput)) return null;
            var effects = new LinkedHashMap<AEKey, Transfer>();
            var units = new HashMap<AEKey, BigInteger>();
            var granularities = new HashMap<AEKey, BigInteger>();
            var crafts = new LinkedHashMap<IPatternDetails, BigInteger>();
            var intermediates = new HashSet<AEKey>();
            var consumed = new HashSet<AEKey>();
            var produced = new HashSet<AEKey>();
            var children = new ArrayList<Shape>();
            var outputs = VmPatternOutputs.inspect(pattern);
            boolean directFeedback = false;
            var bytes = new VmByteCost(ONE, ONE);
            for (var input : pattern.getInputs()) {
                checkpoint.run();
                if (!(input instanceof PatternCompiler.DetachedInput fixed)) return null;
                var key = fixed.exactKey();
                var templates = input.getPossibleInputs();
                // Exact-key certification excludes fuzzy candidates and container callbacks.
                if (key == null || templates.length != 1 || templates[0].amount() <= 0
                        || !key.equals(templates[0].what()) || !key.equals(fixed.craftedKey())
                        || !fixed.isCandidatePrepared(key) || fixed.mayReturnItems()) return null;
                var unit = BigInteger.valueOf(templates[0].amount());
                var amount = PatternCompiler.inputMultiplier(input).multiply(unit);
                if (amount.signum() <= 0) return null;
                bytes = bytes.add(VmByteCost.forStack(amount, key.getType().getAmountPerByte()));
                boolean feedback = outputs.amounts().containsKey(key);
                directFeedback |= feedback;
                var choices = fixed.emittable() || feedback || stocked.test(key) ? List.<IPatternDetails>of() : resolver.apply(key);
                if (choices.size() > 1) return null;
                if (choices.isEmpty()) {
                    var oldUnit = units.putIfAbsent(key, unit);
                    if (oldUnit != null && !oldUnit.equals(unit)) return null;
                    var previous = effects.get(key);
                    if (previous != null && previous.consumed().signum() > 0 && previous.emit() != fixed.emittable()) return null;
                    compose(effects, granularities, key, new Transfer(amount, ZERO, fixed.emittable()), ONE);
                    consumed.add(key);
                    children.add(null);
                    continue;
                }
                var childPattern = choices.get(0);
                if (!childPattern.getPrimaryOutput().what().equals(key)) return null;
                var child = compile(childPattern, key);
                if (child == null) return null;
                if (child.transfers.containsKey(requested) || child.intermediates.contains(requested)) return null;
                var division = amount.divideAndRemainder(child.output);
                if (division[1].signum() != 0 || !division[0].equals(ONE) && !child.repeatCompatible) return null;
                var times = division[0];
                for (var entry : child.units.entrySet()) {
                    var oldUnit = units.putIfAbsent(entry.getKey(), entry.getValue());
                    if (oldUnit != null && !oldUnit.equals(entry.getValue())) return null;
                }
                for (var entry : child.transfers.entrySet()) {
                    var transfer = entry.getValue();
                    var previous = effects.get(entry.getKey());
                    if (transfer.consumed().signum() > 0 && previous != null && previous.consumed().signum() > 0
                            && previous.emit() != transfer.emit()) return null;
                    compose(effects, granularities, entry.getKey(), transfer, times);
                    if (transfer.consumed().signum() > 0) consumed.add(entry.getKey());
                    if (transfer.produced().signum() > 0) produced.add(entry.getKey());
                }
                child.crafts.forEach((p, count) -> crafts.merge(p, count.multiply(times), BigInteger::add));
                bytes = bytes.add(child.bytes.multiply(times));
                intermediates.add(key);
                intermediates.addAll(child.intermediates);
                children.add(child.shape);
            }
            var output = outputs.amount(requested);
            if (output.signum() <= 0) return null;
            outputs.amounts().forEach((key, amount) -> {
                if (!key.equals(requested)) {
                    compose(effects, granularities, key, new Transfer(ZERO, amount, false), ONE);
                    produced.add(key);
                }
            });
            if (!Collections.disjoint(intermediates, consumed) || !Collections.disjoint(intermediates, produced)) return null;
            // #236: every constituent effect must preserve the leaf's extraction residue.
            for (var entry : units.entrySet())
                if (granularities.getOrDefault(entry.getKey(), ZERO).remainder(entry.getValue()).signum() != 0) return null;
            crafts.merge(pattern, ONE, BigInteger::add);
            return new VmBatchProgram(effects, units, output, bytes, crafts, intermediates, new Shape(pattern, children),
                    directFeedback || Collections.disjoint(consumed, produced));
        }
    }

    private static void compose(Map<AEKey, Transfer> effects, Map<AEKey, BigInteger> granularities,
            AEKey key, Transfer next, BigInteger times) {
        granularities.merge(key, next.consumed().gcd(next.produced()), BigInteger::gcd);
        var before = effects.getOrDefault(key, new Transfer(ZERO, ZERO, next.emit()));
        var delta = before.produced().subtract(before.consumed());
        var required = before.consumed().max(next.required(times).subtract(delta));
        var combined = delta.add(next.produced().subtract(next.consumed()).multiply(times));
        effects.put(key, new Transfer(required, required.add(combined),
                before.consumed().signum() > 0 ? before.emit() : next.emit()));
    }
}
