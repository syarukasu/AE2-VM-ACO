package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** #236: certify commuting missing-only branches; execution remains in the ordered VM. */
final class VmMissingProgram {
    private static final BigInteger ONE = BigInteger.ONE;
    record FailureCut(AEKey key, BigInteger amount) { }

    static List<FailureCut> failureCuts(IPatternDetails root,
            Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint) {
        var candidates = new ArrayList<FailureCut>();
        for (var slot : root.getInputs()) {
            if (!(slot instanceof PatternCompiler.DetachedInput input) || input.exactKey() == null) return List.of();
            var key = input.exactKey();
            if (!input.emittable() && resolver.apply(key).isEmpty())
                candidates.add(new FailureCut(key, PatternCompiler.inputMultiplier(slot)
                        .multiply(BigInteger.valueOf(slot.getPossibleInputs()[0].amount()))));
        }
        if (candidates.isEmpty()) return List.of();
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        var pending = new ArrayDeque<IPatternDetails>(); pending.add(root);
        int work = 0;
        while (!pending.isEmpty()) {
            checkpoint.run();
            if (++work > 8192) return List.of();
            var pattern = pending.removeFirst();
            if (!seen.add(pattern)) continue;
            for (var output : pattern.getOutputs()) candidates.removeIf(cut -> cut.key.equals(output.what()));
            if (candidates.isEmpty()) return List.of();
            for (var slot : pattern.getInputs()) {
                if (!(slot instanceof PatternCompiler.DetachedInput input) || input.exactKey() == null
                        || input.mayReturnItems() || slot.getPossibleInputs().length != 1
                        || !input.exactKey().equals(input.craftedKey())
                        || !input.isCandidatePrepared(input.exactKey())
                        || slot.getRemainingKey(input.exactKey()) != null) return List.of();
                pending.addAll(resolver.apply(input.exactKey()));
            }
        }
        return List.copyOf(candidates);
    }
    private record Input(Node node, BigInteger amount) { }
    private record Requirement(AEKey key, BigInteger amount) { }
    private record Recipe(int inputCount, List<Input> inputs, List<Requirement> requirements) { }
    private record Node(AEKey key, boolean emit, List<Recipe> recipes) { }
    private record Path(AEKey key, List<AEKey> ancestors, boolean full) { }
    private record Dependencies(Set<AEKey> all, Set<AEKey> intermediates) { }
    private final Map<AEKey, BigInteger> yields;
    private final List<Node> alternatives;
    final boolean sharedIntermediates;
    private final Map<AEKey, List<IPatternDetails>> selectedSources;

    private VmMissingProgram(Map<AEKey, BigInteger> yields, List<Node> alternatives, boolean sharedIntermediates,
            Map<AEKey, List<IPatternDetails>> selectedSources) {
        this.yields = Map.copyOf(yields); this.alternatives = List.copyOf(alternatives);
        this.sharedIntermediates = sharedIntermediates;
        this.selectedSources = Map.copyOf(selectedSources);
    }

    boolean fixedSources() { return alternatives.isEmpty(); }
    List<IPatternDetails> firstSource(AEKey key) {
        var sources = selectedSources.getOrDefault(key, List.of());
        return sources.isEmpty() ? sources : sources.subList(0, 1);
    }

    static VmMissingProgram compile(IPatternDetails pattern, AEKey target, List<AEKey> ancestors,
            Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint) {
        return compile(pattern, target, ancestors, resolver, checkpoint, false);
    }

    static VmMissingProgram compile(IPatternDetails pattern, AEKey target, List<AEKey> ancestors,
            Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint, boolean allowShared) {
        try {
            var builder = new Builder(resolver, checkpoint);
            var path = new ArrayList<>(ancestors); path.add(target);
            var root = builder.recipe(pattern, target, path, true);
            if (!Collections.disjoint(builder.inputs, builder.secondary)) return null;
            builder.independent(root);
            if (builder.sharedIntermediates && !allowShared) return null;
            var dominance = new IdentityHashMap<Node, Boolean>();
            var monotonicity = new IdentityHashMap<Node, Boolean>();
            var conditional = builder.alternatives.stream().filter(node -> !dominated(node, dominance, monotonicity, checkpoint)).toList();
            return new VmMissingProgram(builder.yields, conditional, builder.sharedIntermediates, builder.selected);
        } catch (Decline unsupported) { return null; }
    }

    /** Values are upper bounds on stock that must be recorded before reusing the proof. */
    Map<AEKey, BigInteger> certify(Function<AEKey, BigInteger> stock, Runnable checkpoint) {
        var bounds = new LinkedHashMap<AEKey, BigInteger>();
        var failed = new IdentityHashMap<Recipe, Boolean>();
        for (var node : alternatives) {
            for (var recipe : node.recipes)
                if (!blocked(recipe, stock, bounds, failed, checkpoint)) return null;
        }
        return bounds;
    }

    /** Componentwise monotone sources exhaust the first source before any larger-input source can run. */
    private static boolean dominated(Node node, Map<Node, Boolean> memo, Map<Node, Boolean> monotonicity, Runnable checkpoint) {
        checkpoint.run();
        var cached = memo.get(node);
        if (cached != null) return cached;
        if (node.recipes.isEmpty()) return true;
        var first = node.recipes.get(0);
        if (first.inputCount == 0 || first.inputs.size() != first.inputCount || first.requirements == null) return false;
        for (var recipe : node.recipes) {
            if (recipe.requirements == null || recipe.inputCount != first.inputCount) return false;
            for (int i = 0; i < first.inputCount; i++) {
                var wanted = first.requirements.get(i);
                var other = recipe.requirements.get(i);
                if (!other.key.equals(wanted.key) || other.amount.compareTo(wanted.amount) < 0) return false;
            }
        }
        for (var input : first.inputs)
            if (input.node.emit || !dominated(input.node, memo, monotonicity, checkpoint)
                    && !(first.inputCount == 1 && monotoneSupply(input.node, monotonicity, checkpoint))) return false;
        memo.put(node, true);
        return true;
    }

    private static boolean monotoneSupply(Node node, Map<Node, Boolean> memo, Runnable checkpoint) {
        checkpoint.run();
        var cached = memo.get(node);
        if (cached != null) return cached;
        // #236: a single-input chain cannot trade stock between competing sibling requirements.
        boolean result = !node.emit;
        for (var recipe : node.recipes) {
            if (recipe.inputCount != 1 || recipe.inputs.size() != 1 || recipe.requirements == null
                    || !monotoneSupply(recipe.inputs.get(0).node, memo, checkpoint)) {
                result = false;
                break;
            }
        }
        memo.put(node, result);
        return result;
    }

    private boolean blocked(Recipe recipe, Function<AEKey, BigInteger> stock,
            Map<AEKey, BigInteger> bounds, Map<Recipe, Boolean> failed, Runnable checkpoint) {
        checkpoint.run();
        var cached = failed.get(recipe);
        if (cached != null) return cached;
        boolean result = false;
        if (!recipe.inputs.isEmpty()) {
            var first = recipe.inputs.get(0);
            var node = first.node;
            var bound = bounds.computeIfAbsent(node.key,
                    key -> stock.apply(key).max(yields.getOrDefault(key, ONE).subtract(ONE)));
            if (!node.emit && first.amount.compareTo(bound) > 0) {
                result = true;
                for (var child : node.recipes)
                    if (!blocked(child, stock, bounds, failed, checkpoint)) { result = false; break; }
            }
        }
        failed.put(recipe, result);
        return result;
    }

    private static final class Builder {
        final Function<AEKey, List<IPatternDetails>> resolver;
        final Runnable checkpoint;
        final Map<Path, Node> nodes = new HashMap<>();
        final Map<AEKey, BigInteger> yields = new LinkedHashMap<>();
        final Set<AEKey> inputs = new HashSet<>(), secondary = new HashSet<>();
        final List<Node> alternatives = new ArrayList<>();
        final Map<Recipe, Dependencies> dependencies = new IdentityHashMap<>();
        final Map<AEKey, List<IPatternDetails>> selected = new HashMap<>();
        boolean sharedIntermediates;
        int work;
        Builder(Function<AEKey, List<IPatternDetails>> resolver, Runnable checkpoint) {
            this.resolver = resolver; this.checkpoint = checkpoint;
        }
        void step() { checkpoint.run(); if (++work > 8192) throw new Decline(); }
        Recipe recipe(IPatternDetails pattern, AEKey target, List<AEKey> ancestors, boolean full) {
            step();
            if (ancestors.size() > 64) throw new Decline();
            var outputs = VmPatternOutputs.inspect(pattern);
            if (!outputs.primary().equals(target)) throw new Decline();
            outputs.amounts().forEach((key, amount) -> {
                if (key.equals(target)) yields.merge(key, amount, BigInteger::max); else secondary.add(key);
            });
            var children = new ArrayList<Input>();
            var slots = pattern.getInputs();
            for (var slot : slots) {
                step();
                if (!(slot instanceof PatternCompiler.DetachedInput input)) throw new Decline();
                var templates = slot.getPossibleInputs();
                if (templates.length != 1 || templates[0].amount() != 1) throw new Decline();
                var key = templates[0].what();
                if (!input.isCandidatePrepared(key) || !key.equals(input.exactKey())
                        || !key.equals(input.craftedKey()) || input.mayReturnItems()
                        || slot.getRemainingKey(key) != null) throw new Decline();
                var amount = PatternCompiler.inputMultiplier(slot);
                if (amount.signum() <= 0 || ancestors.contains(key)) throw new Decline();
                inputs.add(key);
                children.add(new Input(input.emittable() ? new Node(key, true, List.of()) : node(key, ancestors, full), amount));
                // Failed alternatives never visit later inputs. Their first-input chain is sufficient evidence.
                if (!full) break;
            }
            return new Recipe(slots.length, List.copyOf(children), requirements(slots));
        }
        private List<Requirement> requirements(IPatternDetails.IInput[] slots) {
            var result = new ArrayList<Requirement>();
            for (var slot : slots) {
                step();
                if (!(slot instanceof PatternCompiler.DetachedInput input) || input.exactKey() == null
                        || input.emittable() || input.mayReturnItems()) return null;
                var values = slot.getPossibleInputs();
                var key = input.exactKey();
                if (values.length != 1 || values[0].amount() != 1 || !key.equals(values[0].what())
                        || !key.equals(input.craftedKey()) || !input.isCandidatePrepared(key)
                        || slot.getRemainingKey(key) != null) return null;
                var amount = PatternCompiler.inputMultiplier(slot);
                if (amount.signum() <= 0) return null;
                result.add(new Requirement(key, amount));
            }
            return List.copyOf(result);
        }
        Node node(AEKey key, List<AEKey> ancestors, boolean full) {
            var path = new Path(key, List.copyOf(ancestors), full);
            var cached = nodes.get(path);
            if (cached != null) return cached;
            step();
            var children = new ArrayList<Recipe>();
            var next = new ArrayList<>(ancestors); next.add(key);
            var definitions = resolver.apply(key).stream().filter(p -> !conflicts(p, ancestors)).toList();
            if (full) {
                var previous = selected.putIfAbsent(key, definitions);
                // #236: a shared demand cannot merge different ancestor-dependent source orders.
                if (previous != null && (previous.size() != definitions.size()
                        || java.util.stream.IntStream.range(0, previous.size()).anyMatch(i -> previous.get(i) != definitions.get(i))))
                    throw new Decline();
            }
            for (var pattern : definitions) {
                children.add(recipe(pattern, key, next, full && children.isEmpty()));
            }
            var result = new Node(key, false, List.copyOf(children));
            nodes.put(path, result);
            if (children.size() > 1) alternatives.add(result);
            return result;
        }
        Dependencies independent(Recipe recipe) {
            var cached = dependencies.get(recipe);
            if (cached != null) return cached;
            step();
            var all = new HashSet<AEKey>();
            var intermediates = new HashSet<AEKey>();
            for (var input : recipe.inputs) {
                var node = input.node;
                var childAll = new HashSet<AEKey>(); childAll.add(node.key);
                var childIntermediates = new HashSet<AEKey>();
                if (!node.recipes.isEmpty()) {
                    var child = independent(node.recipes.get(0));
                    childAll.addAll(child.all);
                    childIntermediates.addAll(child.intermediates); childIntermediates.add(node.key);
                }
                if (!Collections.disjoint(intermediates, childAll) || !Collections.disjoint(all, childIntermediates))
                    sharedIntermediates = true;
                all.addAll(childAll); intermediates.addAll(childIntermediates);
            }
            var result = new Dependencies(Set.copyOf(all), Set.copyOf(intermediates));
            dependencies.put(recipe, result);
            return result;
        }
        private static boolean conflicts(IPatternDetails pattern, List<AEKey> ancestors) {
            for (var key : ancestors) {
                for (var output : pattern.getOutputs()) if (key.equals(output.what())) return true;
                for (var input : pattern.getInputs()) if (key.equals(input.getPossibleInputs()[0].what())) return true;
            }
            return false;
        }
    }
    private static final class Decline extends RuntimeException { Decline() { super(null, null, false, false); } }
}
