package com.syaru.ae2vm.exact.graph;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static com.syaru.ae2vm.exact.graph.PlanSchedule.add;
import static com.syaru.ae2vm.exact.graph.PlanSchedule.checkpoint;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One immutable order, one worker. Quantities never pass through primitive integers. */
public final class ExactGraphPlanner {
    public GraphPlan plan(RecipeGraph graph, int root, BigInteger requested,
            Map<Integer, BigInteger> stock, Set<Integer> emitters, boolean craftLess) {
        graph.checkKey(root); RecipeGraph.positive(requested);
        stock.forEach((key, amount) -> {
            graph.checkKey(key);
            if (amount.signum() < 0) throw new IllegalArgumentException("negative stock");
        });
        emitters.forEach(graph::checkKey);
        var snapshot = Map.copyOf(stock);
        var sources = Set.copyOf(emitters);
        var order = graph.demandOrder();
        var result = solve(graph, root, requested, snapshot, sources, order);
        if (!craftLess || !result.simulation()) return result;
        // Monotone feasibility for a fixed snapshot; do not recapture or call AE2.
        BigInteger low = ZERO, high = requested.subtract(ONE);
        GraphPlan best = null;
        while (low.compareTo(high) < 0) {
            checkpoint();
            var middle = low.add(high).add(ONE).shiftRight(1);
            var candidate = solve(graph, root, middle, snapshot, sources, order);
            if (candidate.simulation()) high = middle.subtract(ONE);
            else { low = middle; best = candidate; }
        }
        return low.signum() == 0 ? result
                : best != null && best.requested().equals(low) ? best
                : solve(graph, root, low, snapshot, sources, order);
    }

    private GraphPlan solve(RecipeGraph graph, int root, BigInteger amount,
            Map<Integer, BigInteger> stock, Set<Integer> emitters, List<Integer> order) {
        checkpoint();
        if (order == null) return new IntegerRecipeSolver(graph, root, amount, stock, emitters).solve();
        var demand = new LinkedHashMap<Integer, BigInteger>();
        var remaining = new LinkedHashMap<>(stock);
        // Existing finished goods do not satisfy a new crafting order.
        remaining.remove(root);
        demand.put(root, amount);
        var missing = new LinkedHashMap<Integer, BigInteger>();
        var emitted = new LinkedHashMap<Integer, BigInteger>();
        var selectedStock = new LinkedHashMap<Integer, BigInteger>();
        var batches = new ArrayList<PlanSchedule.Batch>();
        long visits = 0;
        for (int recipeId : order) {
            checkpoint(); visits++;
            var recipe = graph.recipes().get(recipeId);
            var times = ZERO;
            for (var entry : recipe.outputs().entrySet()) {
                int key = entry.getKey();
                var required = demand.getOrDefault(key, ZERO);
                var take = remaining.getOrDefault(key, ZERO).min(required);
                add(selectedStock, key, take);
                add(remaining, key, take.negate());
                required = required.subtract(take);
                demand.put(key, required);
                if (emitters.contains(key)) { add(emitted, key, required); demand.put(key, ZERO); }
                else times = times.max(ceil(required, entry.getValue()));
            }
            if (times.signum() == 0) continue;
            var inputs = new LinkedHashMap<Integer, BigInteger>();
            for (var slot : recipe.inputs()) {
                var option = slot.options().get(0);
                var count = slot.multiplier().multiply(option.amount());
                add(inputs, option.key(), count);
                add(demand, option.key(), count.multiply(times));
                visits++;
            }
            for (var entry : recipe.outputs().entrySet()) {
                int key = entry.getKey();
                add(remaining, key, entry.getValue().multiply(times).subtract(demand.getOrDefault(key, ZERO)));
                demand.put(key, ZERO);
            }
            batches.add(new PlanSchedule.Batch(recipe.id(), inputs, recipe.outputs(), Map.of(), times));
        }
        for (var entry : demand.entrySet()) {
            checkpoint();
            add(selectedStock, entry.getKey(), entry.getValue().min(remaining.getOrDefault(entry.getKey(), ZERO)));
            var deficit = entry.getValue().subtract(remaining.getOrDefault(entry.getKey(), ZERO)).max(ZERO);
            if (deficit.signum() > 0) add(emitters.contains(entry.getKey()) ? emitted : missing, entry.getKey(), deficit);
        }
        // A reverse dependency order is already a legal producer-first schedule.
        java.util.Collections.reverse(batches);
        return finish(root, amount, stock, missing, emitted, batches, visits, "shared-dag", selectedStock);
    }

    static GraphPlan finish(int root, BigInteger requested, Map<Integer, BigInteger> stock,
            Map<Integer, BigInteger> missing, Map<Integer, BigInteger> emitted,
            List<PlanSchedule.Batch> selected, long visits, String route) {
        return finish(root, requested, stock, missing, emitted, selected, visits, route, Map.of());
    }

    private static GraphPlan finish(int root, BigInteger requested, Map<Integer, BigInteger> stock,
            Map<Integer, BigInteger> missing, Map<Integer, BigInteger> emitted,
            List<PlanSchedule.Batch> selected, long visits, String route, Map<Integer, BigInteger> selectedStock) {
        var available = new LinkedHashMap<>(stock);
        missing.forEach((key, count) -> add(available, key, count));
        emitted.forEach((key, count) -> add(available, key, count));
        var ordered = route.equals("shared-dag") ? PlanSchedule.inOrder(selected,available) : PlanSchedule.order(selected, available);
        var summary = ordered.summary();
        var required = new LinkedHashMap<>(summary.seed());
        required.merge(root, requested.subtract(summary.delta().getOrDefault(root, ZERO)).max(ZERO), BigInteger::max);
        var used = new LinkedHashMap<Integer, BigInteger>();
        var exactMissing = new LinkedHashMap<Integer, BigInteger>();
        var exactEmitted = new LinkedHashMap<Integer, BigInteger>();
        required.forEach((key, count) -> {
            var eligible = key == root ? summary.seed().getOrDefault(key,ZERO) : count;
            var fromStock = eligible.min(stock.getOrDefault(key, ZERO)).max(selectedStock.getOrDefault(key, ZERO));
            if (fromStock.signum() > 0) used.put(key, fromStock);
            var supplied = fromStock.add(missing.getOrDefault(key, ZERO)).add(emitted.getOrDefault(key, ZERO));
            if (supplied.compareTo(count) < 0) throw new IllegalStateException("plan prefix exceeds captured supply");
            var deficit = count.subtract(fromStock).max(ZERO);
            var externallySupplied = deficit.min(emitted.getOrDefault(key, ZERO));
            add(exactEmitted, key, externallySupplied);
            add(exactMissing, key, deficit.subtract(externallySupplied));
        });
        selectedStock.forEach((key, count) -> used.merge(key, count, BigInteger::max));
        var actual = new LinkedHashMap<>(used);
        exactMissing.forEach((key, count) -> add(actual, key, count));
        exactEmitted.forEach((key, count) -> add(actual, key, count));
        summary.delta().forEach((key, count) -> add(actual, key, count));
        add(actual, root, requested.negate());
        if (actual.values().stream().anyMatch(count -> count.signum() < 0))
            throw new IllegalStateException("unbalanced graph result");
        var crafts = new LinkedHashMap<String, BigInteger>();
        for (var step : ordered.steps()) step.crafts().forEach((id, count) -> crafts.merge(id, count, BigInteger::add));
        return new GraphPlan(root, requested, crafts, used, exactMissing, exactEmitted,
                PlanSchedule.clean(actual), ordered.steps(), visits, route);
    }

    /** Fixed consumer-first prefix only; a coupled consumer keeps all of its dependencies symbolic. */
    static Map<Integer,BigInteger> fixedPrefix(RecipeGraph graph,int root,BigInteger requested,
            Map<Integer,BigInteger> stock,Set<Integer> emitters) {
        int size=graph.recipes().size();
        var producers=new ArrayList<Set<Integer>>();
        for(int k=0;k<graph.keyCount();k++) producers.add(new HashSet<>(graph.producers(k)));
        for(int r=0;r<size;r++) for(var slot:graph.recipes().get(r).inputs()) for(var option:slot.options())
            if(option.returnedKey()>=0) producers.get(option.returnedKey()).add(r);
        var edges=new ArrayList<Set<Integer>>(); int[] consumers=new int[size];
        for(int r=0;r<size;r++) {
            var next=new HashSet<Integer>();
            for(var slot:graph.recipes().get(r).inputs()) for(var option:slot.options()) next.addAll(producers.get(option.key()));
            edges.add(next); for(int p:next) consumers[p]++;
        }
        var ready=new ArrayDeque<Integer>();
        for(int r=0;r<size;r++) if(consumers[r]==0) ready.add(r);
        var demand=new LinkedHashMap<Integer,BigInteger>(); demand.put(root,requested);
        var result=new LinkedHashMap<Integer,BigInteger>();
        while(!ready.isEmpty()) {
            checkpoint(); int r=ready.remove(); var recipe=graph.recipes().get(r);
            if(recipe.outputs().keySet().stream().anyMatch(key->producers.get(key).size()!=1)
                    || recipe.inputs().stream().anyMatch(slot->slot.options().size()!=1 || slot.options().get(0).returnedKey()>=0))
                continue;
            BigInteger count=ZERO;
            for(var output:recipe.outputs().entrySet()) {
                int key=output.getKey(); if(emitters.contains(key)) continue;
                var available=key==root?ZERO:stock.getOrDefault(key,ZERO);
                count=count.max(ceil(demand.getOrDefault(key,ZERO).subtract(available),output.getValue()));
            }
            result.put(r,count);
            for(var slot:recipe.inputs()) {
                var option=slot.options().get(0);
                add(demand,option.key(),count.multiply(slot.multiplier()).multiply(option.amount()));
            }
            for(int p:edges.get(r)) if(--consumers[p]==0) ready.add(p);
        }
        return result;
    }

    static BigInteger ceil(BigInteger value, BigInteger divisor) {
        return value.signum() <= 0 ? ZERO : value.subtract(ONE).divide(divisor).add(ONE);
    }
}
