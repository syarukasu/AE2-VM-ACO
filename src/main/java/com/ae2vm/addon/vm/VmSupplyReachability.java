package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.util.*;

/** #236: optimistic supply closure. Absence proves failure; presence never proves a funded quantity. */
final class VmSupplyReachability {
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
    private record Rule(int requirements, int[] outputs, boolean arbitraryReturns) { }
    private final Map<Family, Integer> families = new HashMap<>();
    private final List<List<Integer>> watchers = new ArrayList<>();
    private final List<Integer> owners = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final List<Set<Integer>> prerequisites = new ArrayList<>();
    private final Set<Integer> unknownReturnInputs = new HashSet<>();
    private final Map<Integer, BitSet> dependencies = new HashMap<>();
    private int[] required, clauseOwners, unconditional;
    private int[][] listeners;
    private final List<int[]> alternatives = new ArrayList<>();
    private int[][] producerRules, inputClauses;
    private int[] unknownRules;
    record Query(int family, BitSet seeds) { }

    static VmSupplyReachability compile(Collection<List<IPatternDetails>> producers, Runnable checkpoint) {
        var graph = new VmSupplyReachability();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        for (var choices : producers) for (var pattern : choices) {
            checkpoint.run();
            if (!seen.add(pattern)) continue;
            int rule = graph.rules.size(), required = 0;
            boolean returns = false;
            var outputs = new HashSet<Integer>();
            var allInputs = new HashSet<Integer>();
            for (var slot : pattern.getInputs()) {
                if (!(slot instanceof PatternCompiler.DetachedInput input) || input.craftedKey() == null
                        || !input.isCandidatePrepared(input.craftedKey())
                        || PatternCompiler.inputMultiplier(input).signum() <= 0) return null;
                if (input.mayReturnItems()) {
                    var bound = input.returnFamilyUpperBound();
                    if (bound == null) returns = true;
                    else for (var returned : bound) {
                        if (returned == null) return null;
                        outputs.add(graph.id(returned));
                    }
                }
                if (input.emittable()) continue;
                var candidates = new HashSet<Integer>();
                candidates.add(graph.id(input.craftedKey()));
                if (input.getPossibleInputs().length == 0) return null;
                for (var template : input.getPossibleInputs()) {
                    if (template.what() == null || template.amount() <= 0) return null;
                    candidates.add(graph.id(template.what()));
                }
                int clause = graph.owners.size();
                allInputs.addAll(candidates);
                graph.owners.add(rule);
                graph.alternatives.add(candidates.stream().mapToInt(Integer::intValue).toArray());
                required++;
                for (int family : candidates) graph.watchers.get(family).add(clause);
            }
            for (var output : pattern.getOutputs()) {
                if (output.what() == null || output.amount() <= 0) return null;
                outputs.add(graph.id(output.what()));
            }
            if (outputs.isEmpty()) return null;
            for (int output : outputs) graph.prerequisites.get(output).addAll(allInputs);
            if (returns) graph.unknownReturnInputs.addAll(allInputs);
            graph.rules.add(new Rule(required, outputs.stream().mapToInt(Integer::intValue).toArray(), returns));
        }
        graph.required = graph.rules.stream().mapToInt(Rule::requirements).toArray();
        graph.clauseOwners = graph.owners.stream().mapToInt(Integer::intValue).toArray();
        graph.listeners = graph.watchers.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        graph.unconditional = java.util.stream.IntStream.range(0, graph.rules.size())
                .filter(i -> graph.required[i] == 0).toArray();
        var producersByFamily = new ArrayList<List<Integer>>();
        for (int i = 0; i < graph.families.size(); i++) producersByFamily.add(new ArrayList<>());
        var clausesByRule = new ArrayList<List<Integer>>();
        for (int i = 0; i < graph.rules.size(); i++) {
            clausesByRule.add(new ArrayList<>());
            for (int family : graph.rules.get(i).outputs) producersByFamily.get(family).add(i);
        }
        for (int i = 0; i < graph.clauseOwners.length; i++) clausesByRule.get(graph.clauseOwners[i]).add(i);
        graph.producerRules = producersByFamily.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        graph.inputClauses = clausesByRule.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        graph.unknownRules = java.util.stream.IntStream.range(0, graph.rules.size())
                .filter(i -> graph.rules.get(i).arbitraryReturns).toArray();
        return graph;
    }

    private int id(AEKey key) {
        return families.computeIfAbsent(Family.of(key), ignored -> {
            watchers.add(new ArrayList<>());
            prerequisites.add(new HashSet<>());
            return watchers.size() - 1;
        });
    }

    Query query(AEKey key, BitSet seeds, Runnable checkpoint) {
        int family = families.getOrDefault(Family.of(key), -1);
        var relevant = dependencies.get(family);
        if (relevant == null) {
            relevant = new BitSet(families.size());
            var pending = new ArrayDeque<Integer>();
            for (int input : unknownReturnInputs) {
                relevant.set(input);
                pending.add(input);
            }
            if (family >= 0 && !relevant.get(family)) { relevant.set(family); pending.add(family); }
            while (!pending.isEmpty()) {
                checkpoint.run();
                int next = pending.removeFirst();
                // Shared prerequisites enter the queue once, including cyclic dependencies.
                for (int input : prerequisites.get(next)) if (!relevant.get(input)) {
                    relevant.set(input);
                    pending.addLast(input);
                }
            }
            dependencies.put(family, relevant);
        }
        var projected = (BitSet) seeds.clone();
        projected.and(relevant);
        return new Query(family, projected);
    }

    boolean unreachable(Query query, Runnable checkpoint) {
        var reachable = close(query.seeds, checkpoint);
        return reachable != null && (query.family < 0 || !reachable.get(query.family));
    }

    BitSet blockedCut(Query query, Runnable checkpoint) {
        var reachable = close(query.seeds, checkpoint);
        return blockedCut(query.family, reachable, checkpoint);
    }

    BitSet blockedCut(int target, BitSet reachable, Runnable checkpoint) {
        if (reachable == null || target >= 0 && reachable.get(target)) return null;
        var blocked = new BitSet(families.size());
        var pending = new ArrayDeque<Integer>();
        var visitedRules = new BitSet(rules.size());
        if (target >= 0) pending.add(target);
        for (int rule : unknownRules)
            if (!blockRule(rule, reachable, pending, visitedRules)) return null;
        while (!pending.isEmpty()) {
            checkpoint.run();
            int family = pending.removeFirst();
            if (blocked.get(family)) continue;
            if (reachable.get(family)) return null;
            blocked.set(family);
            for (int rule : producerRules[family])
                if (!blockRule(rule, reachable, pending, visitedRules)) return null;
        }
        return blocked;
    }

    private boolean blockRule(int rule, BitSet reachable, ArrayDeque<Integer> pending, BitSet visited) {
        if (visited.get(rule)) return true;
        int[] selected = null;
        for (int clause : inputClauses[rule]) {
            int[] candidates = alternatives.get(clause);
            boolean satisfied = false;
            for (int candidate : candidates) if (reachable.get(candidate)) { satisfied = true; break; }
            if (!satisfied && (selected == null || candidates.length < selected.length)) selected = candidates;
        }
        if (selected == null) return false;
        visited.set(rule);
        for (int family : selected) pending.add(family);
        return true;
    }

    // Unknown return families make the entire proof inconclusive, rather than silently omitting them.
    BitSet close(Collection<AEKey> positiveStock, Runnable checkpoint) {
        return close(seeds(positiveStock), checkpoint);
    }

    BitSet seeds(Collection<AEKey> positiveStock) {
        var result = new BitSet(families.size());
        for (var key : positiveStock) {
            var family = families.get(Family.of(key));
            if (family != null) result.set(family);
        }
        return result;
    }

    BitSet membership(BitSet seeds, AEKey key, boolean present) {
        var family = families.get(Family.of(key));
        if (family == null || seeds.get(family) == present) return seeds;
        var result = (BitSet) seeds.clone();
        result.set(family, present);
        return result;
    }

    BitSet close(BitSet seeds, Runnable checkpoint) {
        checkpoint.run();
        var reachable = (BitSet) seeds.clone();
        var pending = new ArrayDeque<Integer>();
        for (int i = reachable.nextSetBit(0); i >= 0; i = reachable.nextSetBit(i + 1)) pending.add(i);
        int[] remaining = required.clone();
        for (int i : unconditional) {
            checkpoint.run();
            if (!activate(rules.get(i), reachable, pending)) return null;
        }
        boolean[] satisfied = new boolean[clauseOwners.length];
        while (!pending.isEmpty()) {
            checkpoint.run();
            for (int clause : listeners[pending.removeFirst()]) {
                if (satisfied[clause]) continue;
                satisfied[clause] = true;
                int rule = clauseOwners[clause];
                if (--remaining[rule] == 0 && !activate(rules.get(rule), reachable, pending)) return null;
            }
        }
        return reachable;
    }

    private boolean activate(Rule rule, BitSet reachable, ArrayDeque<Integer> pending) {
        if (rule.arbitraryReturns) return false;
        for (int output : rule.outputs) if (!reachable.get(output)) {
            reachable.set(output); pending.addLast(output);
        }
        return true;
    }

    boolean contains(BitSet reachable, AEKey key) {
        var family = families.get(Family.of(key));
        return family != null && reachable.get(family);
    }

    int family(AEKey key) { return families.getOrDefault(Family.of(key), -1); }

    boolean knownAbsent(BitSet seeds, AEKey key) {
        var family = families.get(Family.of(key));
        return family != null && !seeds.get(family);
    }

    BitSet knownFamilies(Collection<AEKey> keys) {
        var result = new BitSet(families.size());
        for (var key : keys) {
            var family = families.get(Family.of(key));
            if (family == null) return null;
            result.set(family);
        }
        return result;
    }
}
