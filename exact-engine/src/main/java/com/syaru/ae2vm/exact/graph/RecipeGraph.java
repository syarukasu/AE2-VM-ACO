package com.syaru.ae2vm.exact.graph;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, exact-key indexed coefficients. No game objects or callbacks. */
public final class RecipeGraph {
    public record Option(int key, BigInteger amount, int returnedKey) {
        public Option {
            positive(amount);
            if (key < 0 || returnedKey < -1) throw new IllegalArgumentException("negative key");
        }
        public Option(int key, long amount) { this(key, BigInteger.valueOf(amount), -1); }
    }

    public record Slot(BigInteger multiplier, List<Option> options) {
        public Slot {
            positive(multiplier);
            options = List.copyOf(options);
            if (options.isEmpty()) throw new IllegalArgumentException("empty input slot");
        }
        public Slot(long multiplier, Option... options) { this(BigInteger.valueOf(multiplier), List.of(options)); }
    }

    public record Recipe(String id, List<Slot> inputs, Map<Integer, BigInteger> outputs) {
        public Recipe {
            Objects.requireNonNull(id);
            inputs = List.copyOf(inputs);
            outputs = positiveMap(outputs);
            if (outputs.isEmpty()) throw new IllegalArgumentException("empty outputs");
        }
    }

    private final int keyCount;
    private final List<Recipe> recipes;
    private final List<List<Integer>> producers;
    private volatile Topology topology;
    private record Topology(List<Integer> demandOrder) { }

    public RecipeGraph(int keyCount, List<Recipe> recipes) {
        if (keyCount <= 0) throw new IllegalArgumentException("empty key table");
        this.keyCount = keyCount;
        this.recipes = List.copyOf(recipes);
        var index = new ArrayList<List<Integer>>(keyCount);
        for (int key = 0; key < keyCount; key++) index.add(new ArrayList<>());
        var ids = new java.util.HashSet<String>();
        for (int r = 0; r < recipes.size(); r++) {
            var recipe = recipes.get(r);
            if (!ids.add(recipe.id())) throw new IllegalArgumentException("duplicate recipe id");
            for (var key : recipe.outputs().keySet()) { checkKey(key); index.get(key).add(r); }
            for (var slot : recipe.inputs()) for (var option : slot.options()) {
                checkKey(option.key());
                if (option.returnedKey() >= 0) checkKey(option.returnedKey());
            }
        }
        producers = index.stream().map(List::copyOf).toList();
    }

    public int keyCount() { return keyCount; }
    public List<Recipe> recipes() { return recipes; }
    public List<Integer> producers(int key) { checkKey(key); return producers.get(key); }

    /** Null selects the coupled solver. The topology is shared, never order quantities. */
    List<Integer> demandOrder() {
        var existing = topology;
        if (existing != null) return existing.demandOrder;
        synchronized (this) {
            if (topology == null) topology = new Topology(buildDemandOrder());
            return topology.demandOrder;
        }
    }

    private List<Integer> buildDemandOrder() {
        int size = recipes.size();
        var edges = new ArrayList<java.util.Set<Integer>>(size);
        int[] incoming = new int[size];
        for (var values : producers) if (values.size() > 1) return null;
        for (int r = 0; r < size; r++) {
            PlanSchedule.checkpoint();
            var next = new java.util.LinkedHashSet<Integer>();
            for (var slot : recipes.get(r).inputs()) {
                if (slot.options().size() != 1 || slot.options().get(0).returnedKey() >= 0) return null;
                next.addAll(producers(slot.options().get(0).key()));
            }
            edges.add(next);
            for (int producer : next) incoming[producer]++;
        }
        var ready = new java.util.ArrayDeque<Integer>();
        for (int r = 0; r < size; r++) if (incoming[r] == 0) ready.add(r);
        var order = new ArrayList<Integer>(size);
        while (!ready.isEmpty()) {
            PlanSchedule.checkpoint();
            int recipe = ready.remove(); order.add(recipe);
            for (int producer : edges.get(recipe)) if (--incoming[producer] == 0) ready.add(producer);
        }
        return order.size() == size ? List.copyOf(order) : null;
    }

    public void checkKey(int key) {
        if (key < 0 || key >= keyCount) throw new IllegalArgumentException("key outside graph: " + key);
    }

    static void positive(BigInteger value) {
        if (Objects.requireNonNull(value).signum() <= 0) throw new IllegalArgumentException("nonpositive coefficient");
    }
    static Map<Integer, BigInteger> positiveMap(Map<Integer, BigInteger> values) {
        var copy = new LinkedHashMap<Integer, BigInteger>();
        values.forEach((key, value) -> { positive(value); copy.put(key, value); });
        return Collections.unmodifiableMap(copy);
    }
}
