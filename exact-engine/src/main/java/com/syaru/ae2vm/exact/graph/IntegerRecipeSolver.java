package com.syaru.ae2vm.exact.graph;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static com.syaru.ae2vm.exact.graph.PlanSchedule.add;
import static com.syaru.ae2vm.exact.graph.PlanSchedule.checkpoint;
import de.uni_freiburg.informatik.ultimate.logic.ConstantTerm;
import de.uni_freiburg.informatik.ultimate.logic.Logics;
import de.uni_freiburg.informatik.ultimate.logic.Rational;
import de.uni_freiburg.informatik.ultimate.logic.Script;
import de.uni_freiburg.informatik.ultimate.logic.Sort;
import de.uni_freiburg.informatik.ultimate.logic.Term;
import de.uni_freiburg.informatik.ultimate.smtinterpol.DefaultLogger;
import de.uni_freiburg.informatik.ultimate.smtinterpol.smtlib2.SMTInterpol;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact QF_LIA for competing recipes and slot assignments, with no 64-bit solver boundary. */
final class IntegerRecipeSolver implements AutoCloseable {
    private final RecipeGraph graph;
    private final int root;
    private final BigInteger requested;
    private final Map<Integer, BigInteger> stock;
    private final Set<Integer> emitters;
    private final Script script;
    private final Term[] counts, missing, emitted;
    private final Term[][][] slots;
    private final List<List<Term>> production, consumption;
    private final Map<Integer, BigInteger> startupHint = new LinkedHashMap<>();
    private int fresh;

    IntegerRecipeSolver(RecipeGraph graph, int root, BigInteger requested,
            Map<Integer, BigInteger> stock, Set<Integer> emitters) {
        this.graph = graph; this.root = root; this.requested = requested;
        this.stock = stock; this.emitters = emitters;
        var logger = new DefaultLogger();
        logger.setLoglevel(0);
        script = new SMTInterpol(logger, () -> Thread.currentThread().isInterrupted());
        script.setOption(":produce-models", true);
        script.setLogic(Logics.QF_LIA);
        counts = new Term[graph.recipes().size()];
        slots = new Term[counts.length][][];
        missing = new Term[graph.keyCount()]; emitted = new Term[graph.keyCount()];
        production = lists(graph.keyCount()); consumption = lists(graph.keyCount());
    }

    GraphPlan solve() {
        try (this) {
            build();
            script.push(1);
            for (var value : missing) script.assertTerm(eq(value, number(ZERO)));
            var status = check();
            if (status == Script.LBool.UNSAT) {
                script.pop(1);
                // Prefer a reusable startup supply. This is a soft preference, never a quantity cap.
                script.push(1);
                startupHint.forEach((key, amount) -> script.assertTerm(script.term("<=", missing[key], number(amount))));
                status = check();
                if (status == Script.LBool.UNSAT) { script.pop(1); status = check(); }
                if (status == Script.LBool.SAT) for (int key : startupHint.keySet()) {
                    script.push(1);
                    script.assertTerm(eq(missing[key], number(ZERO)));
                    if (check() == Script.LBool.UNSAT) { script.pop(1); check(); }
                }
            }
            if (status == Script.LBool.UNSAT)
                throw new IllegalStateException("No executable input supply satisfies the captured graph");
            preferEarlierProducers();
            var values = new ArrayList<Term>();
            values.addAll(Arrays.asList(counts)); values.addAll(Arrays.asList(missing)); values.addAll(Arrays.asList(emitted));
            for (var pattern : slots) for (var slot : pattern) values.addAll(Arrays.asList(slot));
            var model = script.getValue(values.toArray(Term[]::new));
            var selected = batches(model);
            var shortages = quantities(missing, model);
            var supplies = quantities(emitted, model);
            return ExactGraphPlanner.finish(root, requested, stock, shortages, supplies,
                    selected, values.size(), "integer-graph");
        }
    }

    private void preferEarlierProducers() {
        var considered = new HashSet<Integer>();
        for (int key=0;key<graph.keyCount();key++) {
            var producers=graph.producers(key);
            for (int i=producers.size()-1;i>0;i--) {
                int recipe=producers.get(i);
                if (!considered.add(recipe)) continue;
                script.push(1);
                script.assertTerm(eq(counts[recipe],number(ZERO)));
                if (check()==Script.LBool.UNSAT) { script.pop(1); check(); }
            }
        }
    }

    private void build() {
        var fixed = ExactGraphPlanner.fixedPrefix(graph,root,requested,stock,emitters);
        var components = components();
        var startup = new HashSet<Integer>();
        for (var component : components) {
            var internalSupply = new HashSet<Integer>();
            for (int r : component) {
                internalSupply.addAll(graph.recipes().get(r).outputs().keySet());
                for (var slot : graph.recipes().get(r).inputs()) for (var option : slot.options())
                    if (option.returnedKey() >= 0) internalSupply.add(option.returnedKey());
            }
            for (int r : component) for (var slot : graph.recipes().get(r).inputs())
                for (var option : slot.options()) if (internalSupply.contains(option.key())) {
                    startup.add(option.key());
                    startupHint.merge(option.key(), option.amount().multiply(slot.multiplier()), BigInteger::add);
                }
        }

        for (int key = 0; key < graph.keyCount(); key++) {
            missing[key] = graph.producers(key).isEmpty() || startup.contains(key) ? nonnegative() : number(ZERO);
            emitted[key] = emitters.contains(key) ? nonnegative() : number(ZERO);
        }
        for (int r = 0; r < counts.length; r++) {
            checkpoint();
            var recipe = graph.recipes().get(r);
            counts[r] = fixed.containsKey(r) ? number(fixed.get(r)) : nonnegative();
            slots[r] = new Term[recipe.inputs().size()][];
            for (var out : recipe.outputs().entrySet()) production.get(out.getKey()).add(mul(out.getValue(), counts[r]));
            for (int s = 0; s < recipe.inputs().size(); s++) {
                var slot = recipe.inputs().get(s);
                slots[r][s] = new Term[slot.options().size()];
                for (int o = 0; o < slot.options().size(); o++) {
                    var option = slot.options().get(o);
                    var quantity = slots[r][s][o] = slot.options().size() == 1
                            ? mul(slot.multiplier(),counts[r]) : nonnegative();
                    consumption.get(option.key()).add(mul(option.amount(), quantity));
                    if (option.returnedKey() >= 0) production.get(option.returnedKey()).add(quantity);
                }
                if (slot.options().size() > 1)
                    script.assertTerm(eq(sum(Arrays.asList(slots[r][s])), mul(slot.multiplier(), counts[r])));
            }
        }
        for (int key = 0; key < graph.keyCount(); key++) {
            var available = new ArrayList<>(production.get(key));
            available.add(number(stock.getOrDefault(key, ZERO))); available.add(missing[key]); available.add(emitted[key]);
            var needed = new ArrayList<>(consumption.get(key));
            if (key == root) needed.add(number(requested.add(stock.getOrDefault(root, ZERO))));
            script.assertTerm(script.term(">=", sum(available), sum(needed)));
        }
        if (!graph.producers(root).isEmpty() && !emitters.contains(root)) {
            var gross = new ArrayList<Term>();
            for (int r : graph.producers(root)) gross.add(mul(graph.recipes().get(r).outputs().get(root), counts[r]));
            script.assertTerm(script.term(">=", sum(gross), number(requested)));
        }
        for (var component : components) constrainStartup(component);
        for (int r = 0; r < counts.length; r++) constrainFixedReturns(r);
    }

    private void constrainFixedReturns(int r) {
        var recipe = graph.recipes().get(r);
        if (recipe.inputs().stream().anyMatch(slot -> slot.options().size() != 1)) return;
        var inputs = new LinkedHashMap<Integer, BigInteger>();
        var outputs = new LinkedHashMap<>(recipe.outputs());
        for (var slot : recipe.inputs()) {
            var option = slot.options().get(0);
            add(inputs,option.key(),option.amount().multiply(slot.multiplier()));
            if (option.returnedKey() >= 0) add(outputs,option.returnedKey(),slot.multiplier());
        }
        inputs.forEach((key, consumed) -> {
            var produced = outputs.getOrDefault(key,ZERO);
            if (produced.signum() == 0) return;
            var other = new ArrayList<>(production.get(key));
            other.add(script.term("-",mul(produced,counts[r])));
            other.add(number(stock.getOrDefault(key,ZERO))); other.add(missing[key]); other.add(emitted[key]);
            var loss = consumed.subtract(produced);
            var prefix = loss.signum() > 0 ? sum(List.of(mul(loss,counts[r]),number(produced))) : number(consumed);
            // #218: a returned resource is not available before the operation that returns it.
            script.assertTerm(script.term("or",eq(counts[r],number(ZERO)),script.term(">=",sum(other),prefix)));
        });
    }

    /** The state equation alone would manufacture an unseeded catalyst cycle. */
    private void constrainStartup(Set<Integer> component) {
        var choices = new ArrayList<Term>();
        var unused = new ArrayList<Term>();
        for (int r : component) unused.add(eq(counts[r], number(ZERO)));
        choices.add(and(unused));
        var external = lists(graph.keyCount());
        for (int r = 0; r < counts.length; r++) if (!component.contains(r)) {
            var recipe = graph.recipes().get(r);
            for (var output : recipe.outputs().entrySet()) external.get(output.getKey()).add(mul(output.getValue(), counts[r]));
            for (int s = 0; s < recipe.inputs().size(); s++) for (int o = 0; o < slots[r][s].length; o++) {
                int returned = recipe.inputs().get(s).options().get(o).returnedKey();
                if (returned >= 0) external.get(returned).add(slots[r][s][o]);
            }
        }
        for (int r : component) {
            var enabled = new ArrayList<Term>();
            enabled.add(script.term(">", counts[r], number(ZERO)));
            var needs = new LinkedHashMap<Integer, List<Term>>();
            var recipe = graph.recipes().get(r);
            for (int s = 0; s < recipe.inputs().size(); s++) {
                var slot = recipe.inputs().get(s);
                var assigned = new ArrayList<Term>();
                for (int o = 0; o < slot.options().size(); o++) {
                    var first = nonnegative(); assigned.add(first);
                    enabled.add(script.term("<=", first, slots[r][s][o]));
                    var option = slot.options().get(o);
                    needs.computeIfAbsent(option.key(), ignored -> new ArrayList<>()).add(mul(option.amount(), first));
                }
                enabled.add(eq(sum(assigned), number(slot.multiplier())));
            }
            needs.forEach((key, terms) -> {
                var supply = new ArrayList<>(external.get(key));
                supply.add(number(stock.getOrDefault(key, ZERO))); supply.add(missing[key]); supply.add(emitted[key]);
                enabled.add(script.term("<=", sum(terms), sum(supply)));
            });
            choices.add(and(enabled));
        }
        script.assertTerm(script.term("or", choices.toArray(Term[]::new)));
    }

    private List<PlanSchedule.Batch> batches(Map<Term, Term> model) {
        var result = new ArrayList<PlanSchedule.Batch>();
        for (int r = 0; r < counts.length; r++) {
            var remaining = value(model, counts[r]);
            var recipe = graph.recipes().get(r);
            var allocations = new BigInteger[slots[r].length][];
            for (int s = 0; s < allocations.length; s++) {
                allocations[s] = new BigInteger[slots[r][s].length];
                for (int o = 0; o < allocations[s].length; o++) allocations[s][o] = value(model, slots[r][s][o]);
            }
            while (remaining.signum() > 0) {
                checkpoint();
                var chunk = remaining;
                for (int s = 0; s < allocations.length; s++) {
                    var first = firstPositive(allocations[s]);
                    chunk = chunk.min(allocations[s][first].divide(recipe.inputs().get(s).multiplier()));
                }
                // At most one mixed invocation per exhausted slot option, not per requested item.
                if (chunk.signum() == 0) chunk = ONE;
                var inputs = new LinkedHashMap<Integer, BigInteger>();
                var returns = new LinkedHashMap<Integer, BigInteger>();
                for (int s = 0; s < allocations.length; s++) {
                    var slot = recipe.inputs().get(s);
                    var needed = slot.multiplier();
                    for (int o = 0; o < allocations[s].length && needed.signum() > 0; o++) {
                        var take = allocations[s][o].divide(chunk).min(needed);
                        if (take.signum() == 0) continue;
                        var option = slot.options().get(o);
                        add(inputs, option.key(), take.multiply(option.amount()));
                        if (option.returnedKey() >= 0) add(returns, option.returnedKey(), take);
                        allocations[s][o] = allocations[s][o].subtract(take.multiply(chunk));
                        needed = needed.subtract(take);
                    }
                    if (needed.signum() != 0) throw new IllegalStateException("nonintegral input allocation");
                }
                result.add(new PlanSchedule.Batch(recipe.id(), inputs, recipe.outputs(), returns, chunk));
                remaining = remaining.subtract(chunk);
            }
        }
        return result;
    }

    /** Iterative SCCs, including return edges. Only cyclic regions require seed constraints. */
    private List<Set<Integer>> components() {
        int size = graph.recipes().size();
        var producers = new ArrayList<Set<Integer>>(graph.keyCount());
        for (int k = 0; k < graph.keyCount(); k++) producers.add(new HashSet<>(graph.producers(k)));
        for (int r = 0; r < size; r++) for (var slot : graph.recipes().get(r).inputs())
            for (var option : slot.options()) if (option.returnedKey() >= 0) producers.get(option.returnedKey()).add(r);
        var edges = new ArrayList<Set<Integer>>(); var back = new ArrayList<Set<Integer>>();
        for (int r = 0; r < size; r++) { edges.add(new HashSet<>()); back.add(new HashSet<>()); }
        for (int r = 0; r < size; r++) for (var slot : graph.recipes().get(r).inputs()) for (var option : slot.options())
            for (int p : producers.get(option.key())) { edges.get(p).add(r); back.get(r).add(p); }
        boolean[] seen = new boolean[size]; var finished = new ArrayList<Integer>();
        for (int start = 0; start < size; start++) if (!seen[start]) {
            var stack = new ArrayDeque<Integer>(); stack.push(start);
            while (!stack.isEmpty()) {
                checkpoint(); int next = stack.pop();
                if (next < 0) { finished.add(-next - 1); continue; }
                if (seen[next]) continue;
                seen[next] = true; stack.push(-next - 1);
                for (int child : edges.get(next)) if (!seen[child]) stack.push(child);
            }
        }
        Arrays.fill(seen, false); var result = new ArrayList<Set<Integer>>();
        for (int i = finished.size() - 1; i >= 0; i--) {
            int start = finished.get(i); if (seen[start]) continue;
            var component = new java.util.LinkedHashSet<Integer>();
            var stack = new ArrayDeque<Integer>(); stack.push(start);
            while (!stack.isEmpty()) {
                int next = stack.pop(); if (seen[next]) continue;
                seen[next] = true; component.add(next);
                for (int child : back.get(next)) if (!seen[child]) stack.push(child);
            }
            if (component.size() > 1 || edges.get(start).contains(start)) result.add(component);
        }
        return result;
    }

    private Script.LBool check() {
        checkpoint(); var result = script.checkSat(); checkpoint();
        if (result == Script.LBool.UNKNOWN) throw new IllegalStateException("integer solver returned UNKNOWN, not shortage");
        return result;
    }
    private Term nonnegative() {
        String name = "v" + fresh++;
        script.declareFun(name, new Sort[0], script.sort("Int"));
        var term = script.term(name);
        script.assertTerm(script.term(">=", term, number(ZERO)));
        return term;
    }
    private Term number(BigInteger n) { return n.signum() < 0 ? script.term("-", script.numeral(n.negate())) : script.numeral(n); }
    private Term mul(BigInteger coefficient, Term term) { return coefficient.equals(ONE) ? term : script.term("*", number(coefficient), term); }
    private Term sum(List<Term> terms) { return terms.isEmpty() ? number(ZERO) : terms.size() == 1 ? terms.get(0) : script.term("+", terms.toArray(Term[]::new)); }
    private Term eq(Term a, Term b) { return script.term("=", a, b); }
    private Term and(List<Term> terms) { return terms.isEmpty() ? script.term("true") : terms.size() == 1 ? terms.get(0) : script.term("and", terms.toArray(Term[]::new)); }
    private static List<List<Term>> lists(int size) { var result = new ArrayList<List<Term>>(size); for (int i = 0; i < size; i++) result.add(new ArrayList<>()); return result; }
    private static int firstPositive(BigInteger[] values) { for (int i = 0; i < values.length; i++) if (values[i].signum() > 0) return i; throw new IllegalStateException("empty selected slot"); }
    private static BigInteger value(Map<Term, Term> model, Term key) {
        var term = model.get(key);
        if (!(term instanceof ConstantTerm constant)) throw new IllegalStateException("nonconstant integer model: " + term);
        Object value = constant.getValue();
        if (value instanceof BigInteger integer) return integer;
        if (value instanceof Rational rational && rational.denominator().equals(ONE)) return rational.numerator();
        throw new IllegalStateException("noninteger model: " + value);
    }
    private static Map<Integer, BigInteger> quantities(Term[] terms, Map<Term, Term> model) {
        var result = new LinkedHashMap<Integer, BigInteger>();
        for (int i = 0; i < terms.length; i++) add(result, i, value(model, terms[i]));
        return result;
    }
    @Override public void close() { script.exit(); }
}
