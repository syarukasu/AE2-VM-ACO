package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;

/** #236: exact conservation certificates only; an unsuccessful price search proves nothing. */
final class VmSupplyPotential {
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
    private record Price(BigInteger n, BigInteger d) implements Comparable<Price> {
        static final Price Z = new Price(ZERO, ONE);
        Price {
            if (n.signum() < 0 || d.signum() <= 0) throw new IllegalArgumentException();
            if (n.signum() == 0) d = ONE;
            else if (!d.equals(ONE)) { var g = n.gcd(d); n = n.divide(g); d = d.divide(g); }
        }
        Price add(Price b) {
            if (n.signum() == 0) return b;
            if (b.n.signum() == 0) return this;
            if (d.equals(b.d)) return new Price(n.add(b.n), d);
            var g = d.gcd(b.d); var aScale = b.d.divide(g); var bScale = d.divide(g);
            return new Price(n.multiply(aScale).add(b.n.multiply(bScale)), d.multiply(aScale));
        }
        Price times(BigInteger q) { return q.signum() == 0 || n.signum() == 0 ? Z : new Price(n.multiply(q), d); }
        Price over(BigInteger q) { return n.signum() == 0 ? Z : new Price(n, d.multiply(q)); }
        public int compareTo(Price b) { return d.equals(b.d) ? n.compareTo(b.n) : n.multiply(b.d).compareTo(b.n.multiply(d)); }
    }
    private record Term(int family, BigInteger amount) { }
    private record Input(List<Term> choices, BigInteger multiplier) { }
    private record Rule(List<Input> inputs, List<Term> outputs) { }
    private final Map<Family, Integer> indexes = new HashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<List<Rule>> producers = new ArrayList<>();
    private final Set<Integer> returned = new HashSet<>();
    private final Runnable checkpoint;
    private int index(AEKey key) {
        var family = Family.of(key);
        var existing = indexes.get(family);
        if (existing != null) return existing;
        int id = indexes.size(); indexes.put(family, id); producers.add(new ArrayList<>()); return id;
    }
    private VmSupplyPotential(Runnable checkpoint) { this.checkpoint = checkpoint; }

    static boolean proves(Map<AEKey, List<IPatternDetails>> catalog, Map<AEKey, BigInteger> stock,
            AEKey root, BigInteger amount, Map<IPatternDetails, BigInteger> projected,
            Map<AEKey, BigInteger> missing, Runnable checkpoint) {
        var graph = new VmSupplyPotential(checkpoint);
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        for (var sources : catalog.values()) for (var pattern : sources) {
            checkpoint.run();
            if (!seen.add(pattern)) continue;
            var inputs = new ArrayList<Input>();
            for (var slot : pattern.getInputs()) {
                if (!(slot instanceof PatternCompiler.DetachedInput input)) return false;
                if (input.mayReturnItems()) {
                    var bound = input.returnFamilyUpperBound();
                    if (bound == null) return false;
                    for (var key : bound) { if (key == null) return false; graph.returned.add(graph.index(key)); }
                    continue;
                }
                if (input.emittable()) continue;
                var choices = new ArrayList<Term>();
                var templates = slot.getPossibleInputs();
                if (templates.length == 0) return false;
                for (var template : templates) {
                    if (template.amount() <= 0) return false;
                    choices.add(new Term(graph.index(template.what()), BigInteger.valueOf(template.amount())));
                }
                if (input.craftedKey() == null) return false;
                choices.add(new Term(graph.index(input.craftedKey()), BigInteger.valueOf(templates[0].amount())));
                var multiplier = PatternCompiler.inputMultiplier(slot);
                if (multiplier.signum() <= 0) return false;
                inputs.add(new Input(List.copyOf(choices), multiplier));
            }
            var outputs = new ArrayList<Term>();
            for (var output : pattern.getOutputs()) {
                var q = PatternCompiler.outputAmount(pattern, output);
                if (q.signum() <= 0) return false;
                outputs.add(new Term(graph.index(output.what()), q));
            }
            var rule = new Rule(List.copyOf(inputs), List.copyOf(outputs)); graph.rules.add(rule);
            for (var output : outputs) graph.producers.get(output.family).add(rule);
        }
        var supply = new HashMap<Integer, BigInteger>();
        stock.forEach((key, q) -> { if (q.signum() > 0) supply.merge(graph.index(key), q, BigInteger::add); });
        int target = graph.index(root);
        var demand = new HashMap<Integer, BigInteger>(); demand.put(target, amount);
        for (var entry : projected.entrySet()) {
            for (var slot : entry.getKey().getInputs()) {
                var first = slot.getPossibleInputs()[0];
                var q = PatternCompiler.inputMultiplier(slot).multiply(BigInteger.valueOf(first.amount())).multiply(entry.getValue());
                demand.merge(graph.index(first.what()), q, BigInteger::add);
            }
            for (var output : entry.getKey().getOutputs())
                demand.merge(graph.index(output.what()), PatternCompiler.outputAmount(entry.getKey(), output).multiply(entry.getValue()), BigInteger::add);
        }
        var shortages = new LinkedHashSet<Integer>();
        missing.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> { if (e.getValue().signum() > 0) shortages.add(graph.index(e.getKey())); });
        var all = new BitSet();
        for (int i = 0; i < graph.producers.size(); i++) if (graph.producers.get(i).isEmpty() && !graph.returned.contains(i)) all.set(i);
        var seeds = new ArrayList<BitSet>(); seeds.add(all);
        var scarce = graph.terminalClosure(shortages); scarce.and(all);
        if (!scarce.isEmpty() && !scarce.equals(all)) seeds.add(scarce);
        int count = 0;
        for (var key : shortages) {
            if (count++ >= 4) break;
            var cut = graph.terminalClosure(Set.of(key)); cut.and(all);
            if (!cut.isEmpty()) seeds.add(cut);
        }
        int maximum = amount.bitLength();
        for (var q : demand.values()) maximum = Math.max(maximum, q.bitLength());
        for (var seed : seeds) {
            var prices = new Price[graph.producers.size()]; Arrays.fill(prices, Price.Z);
            for (int i = seed.nextSetBit(0); i >= 0; i = seed.nextSetBit(i + 1)) {
                var q = demand.getOrDefault(i, supply.getOrDefault(i, amount)).max(ONE);
                prices[i] = new Price(ONE.shiftLeft(Math.max(0, maximum - q.bitLength())), ONE);
            }
            var eligible = graph.eligible(seed);
            for (int iteration = 0; iteration < 32; iteration++) {
                checkpoint.run();
                var next = prices.clone(); var proposed = new Price[prices.length];
                for (var rule : graph.rules) {
                    var cost = graph.cost(rule, prices);
                    var size = ZERO;
                    for (var out : rule.outputs) if (eligible.get(out.family)) size = size.add(out.amount);
                    if (size.signum() == 0) continue;
                    var value = cost.over(size);
                    for (var out : rule.outputs) if (eligible.get(out.family)) {
                        var old = proposed[out.family];
                        if (old == null || value.compareTo(old) < 0) proposed[out.family] = value;
                    }
                }
                boolean changed = false;
                for (int i = 0; i < next.length; i++) if (proposed[i] != null && !graph.returned.contains(i)) {
                    if (proposed[i].compareTo(prices[i]) > 0) { next[i] = proposed[i]; changed = true; }
                }
                prices = next;
                if (prices[target].n.signum() > 0 && graph.verify(prices, supply, target, amount)) return true;
                if (!changed) break;
            }
        }
        return false;
    }

    private BitSet terminalClosure(Collection<Integer> roots) {
        var visited = new BitSet(); var pending = new ArrayDeque<Integer>(roots);
        while (!pending.isEmpty()) {
            checkpoint.run(); int key = pending.removeFirst();
            if (visited.get(key)) continue; visited.set(key);
            for (var rule : producers.get(key)) for (var input : rule.inputs)
                for (var term : input.choices) pending.addLast(term.family);
        }
        return visited;
    }
    private BitSet eligible(BitSet seed) {
        var result = (BitSet) seed.clone();
        boolean changed;
        do {
            checkpoint.run(); changed = false;
            for (int family = 0; family < producers.size(); family++) {
                if (result.get(family) || returned.contains(family) || producers.get(family).isEmpty()) continue;
                boolean all = true;
                for (var rule : producers.get(family)) {
                    boolean positive = false;
                    for (var input : rule.inputs) if (input.choices.stream().allMatch(t -> result.get(t.family))) { positive = true; break; }
                    if (!positive) { all = false; break; }
                }
                if (all) { result.set(family); changed = true; }
            }
        } while (changed);
        return result;
    }
    private Price cost(Rule rule, Price[] prices) {
        var total = Price.Z;
        for (var input : rule.inputs) {
            Price cheapest = null;
            for (var term : input.choices) {
                var value = prices[term.family].times(term.amount);
                if (cheapest == null || value.compareTo(cheapest) < 0) cheapest = value;
            }
            total = total.add(cheapest.times(input.multiplier));
        }
        return total;
    }
    private boolean verify(Price[] prices, Map<Integer, BigInteger> supply, int root, BigInteger amount) {
        var available = Price.Z;
        for (var entry : supply.entrySet()) available = available.add(prices[entry.getKey()].times(entry.getValue()));
        if (available.compareTo(prices[root].times(amount)) >= 0) return false;
        for (var key : returned) if (prices[key].n.signum() != 0) return false;
        for (var rule : rules) {
            checkpoint.run(); var outputs = Price.Z;
            for (var out : rule.outputs) outputs = outputs.add(prices[out.family].times(out.amount));
            if (outputs.compareTo(cost(rule, prices)) > 0) return false;
        }
        return true;
    }
}
