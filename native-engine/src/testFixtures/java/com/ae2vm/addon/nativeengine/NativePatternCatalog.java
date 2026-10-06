package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AEProcessingPattern;
import com.syaru.ae2vm.exact.graph.RecipeGraph;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Server-owned definitions, never stock or answers. Programs survive their catalog generation. */
public final class NativePatternCatalog {
    record Input(AEKey key, BigInteger amount, BigInteger multiplier) { }
    record Output(AEKey key, BigInteger amount) { }
    record Definition(List<Input> inputs, List<Output> outputs) {
        Definition { inputs = List.copyOf(inputs); outputs = List.copyOf(outputs); }
    }
    record Coefficients(IPatternDetails pattern, BigInteger scale) { }
    private record Entry(IPatternDetails source, Definition definition) { }
    private record NodeState(Set<IPatternDetails> patterns, Set<AEKey> outputs) { }
    private static final class Registered {
        Definition definition;
        int owners;
        Registered(Definition definition) { this.definition = definition; }
    }

    private final Map<IGridNode, NodeState> nodes = new IdentityHashMap<>();
    private final Map<IPatternDetails, Registered> registered = new IdentityHashMap<>();
    private final Map<AEKey, List<Entry>> producers = new LinkedHashMap<>();
    private final Map<AEKey, Program> programs = new LinkedHashMap<>();
    private final Map<AEKey, Set<AEKey>> dependents = new LinkedHashMap<>();

    /** Called after AE2 updates its own provider index, including same-tick changes. */
    public void providerChanged(IGridNode node, boolean removed) {
        var previous = nodes.remove(node);
        var patterns = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        var outputs = new LinkedHashSet<AEKey>();
        var provider = removed ? null : node.getService(ICraftingProvider.class);
        if (provider != null) {
            for (var pattern : List.copyOf(provider.getAvailablePatterns())) {
                patterns.add(pattern);
            }
        }
        if (previous != null) for (var pattern : previous.patterns()) if (!patterns.contains(pattern)) {
            var state = registered.get(pattern);
            if (state != null && --state.owners == 0) registered.remove(pattern);
        }
        for (var pattern : patterns) {
            // Unknown callbacks are deliberately not frozen or run at registration.
            var definition = freeze(pattern);
            var state = registered.computeIfAbsent(pattern, key -> new Registered(definition));
            state.definition = definition;
            if (previous == null || !previous.patterns().contains(pattern)) state.owners++;
            if (definition != null) definition.outputs.forEach(output -> outputs.add(output.key));
            else for (var output : pattern.getOutputs()) if (output != null) outputs.add(output.what());
        }
        if (!removed && provider != null) nodes.put(node, new NodeState(patterns, Set.copyOf(outputs)));
        if (previous != null) outputs.addAll(previous.outputs());
        for (var output : outputs) {
            producers.remove(output);
            var roots = dependents.get(output);
            if (roots != null) for (var root : List.copyOf(roots)) invalidate(root);
        }
    }

    private void invalidate(AEKey root) {
        var previous = programs.remove(root);
        if (previous == null) return;
        for (var key : previous.demands) {
            var roots = dependents.get(key);
            if (roots != null && roots.remove(root) && roots.isEmpty()) dependents.remove(key);
        }
    }

    Definition definition(IPatternDetails pattern) {
        var state = registered.get(pattern);
        return state == null ? freeze(pattern) : state.definition;
    }

    private List<Entry> producers(AEKey key, ICraftingService service) {
        return producers.computeIfAbsent(key, ignored -> {
            var result = new ArrayList<Entry>();
            for (var pattern : List.copyOf(service.getCraftingFor(key)))
                result.add(new Entry(pattern, definition(pattern)));
            return List.copyOf(result);
        });
    }

    Program program(AEKey root, ICraftingService service, java.util.function.BooleanSupplier cancelled) {
        var cached = programs.get(root);
        if (cached != null) return cached;
        var pending = new ArrayDeque<AEKey>();
        var demands = new LinkedHashSet<AEKey>();
        var keys = new LinkedHashSet<AEKey>();
        var entries = new LinkedHashMap<IPatternDetails, Entry>();
        pending.add(root); demands.add(root); keys.add(root);
        while (!pending.isEmpty()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            var key = pending.remove();
            for (var entry : producers(key, service)) {
                if (entry.definition == null) return null;
                if (entries.putIfAbsent(entry.source, entry) != null) continue;
                entry.definition.outputs.forEach(output -> keys.add(output.key));
                for (var input : entry.definition.inputs) {
                    keys.add(input.key);
                    if (demands.add(input.key)) pending.add(input.key);
                }
            }
        }
        // Do not grow a cache for arbitrary uncraftable terminal requests.
        if (entries.isEmpty()) { producers.remove(root); return null; }
        var program = new Program(List.copyOf(keys), List.copyOf(demands), List.copyOf(entries.values()));
        programs.put(root, program);
        for (var key : demands) dependents.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(root);
        return program;
    }

    /** All fields consumed by graph() are immutable values, with no live callbacks. */
    static final class Program {
        final List<AEKey> keys, demands;
        final Set<AEKey> inputKeys;
        final Map<String, IPatternDetails> bindings;
        private final List<Definition> definitions;
        private volatile RecipeGraph graph;

        Program(List<AEKey> keys, List<AEKey> demands, List<Entry> entries) {
            this.keys = keys; this.demands = demands;
            var bindings = new LinkedHashMap<String, IPatternDetails>();
            var definitions = new ArrayList<Definition>();
            var inputs = new LinkedHashSet<AEKey>();
            for (var entry : entries) {
                bindings.put("graph-" + definitions.size(), entry.source);
                definitions.add(entry.definition);
                entry.definition.inputs.forEach(input -> inputs.add(input.key));
            }
            this.bindings = Map.copyOf(bindings);
            this.definitions = List.copyOf(definitions);
            this.inputKeys = Set.copyOf(inputs);
        }

        RecipeGraph graph() {
            var result = graph;
            if (result != null) return result;
            synchronized (this) {
                if (graph != null) return graph;
                var ids = new LinkedHashMap<AEKey, Integer>();
                for (int i = 0; i < keys.size(); i++) ids.put(keys.get(i), i);
                var recipes = new ArrayList<RecipeGraph.Recipe>();
                for (var definition : definitions) {
                    var slots = new ArrayList<RecipeGraph.Slot>();
                    for (var input : definition.inputs) slots.add(new RecipeGraph.Slot(input.multiplier,
                            List.of(new RecipeGraph.Option(ids.get(input.key), input.amount, -1))));
                    var outputs = new LinkedHashMap<Integer, BigInteger>();
                    for (var output : definition.outputs) outputs.merge(ids.get(output.key), output.amount, BigInteger::add);
                    recipes.add(new RecipeGraph.Recipe("graph-" + recipes.size(), slots, outputs));
                }
                return graph = new RecipeGraph(keys.size(), recipes);
            }
        }

        boolean compiled() { return graph != null; }
    }

    private static Definition freeze(IPatternDetails source) {
        Coefficients coefficients;
        try { coefficients = coefficients(source); }
        catch (RuntimeException unsupported) { return null; } // The order-local adapter reports contract failures.
        var pattern = coefficients.pattern;
        if (!(pattern instanceof AEProcessingPattern)) return null;
        try {
            for (var method : List.of("getInputs", "getOutputs"))
                if (pattern.getClass().getMethod(method).getDeclaringClass() != AEProcessingPattern.class) return null;
        } catch (ReflectiveOperationException unknown) { return null; }
        var inputs = new ArrayList<Input>();
        for (var slot : pattern.getInputs()) {
            if (!slot.getClass().getName().equals("appeng.crafting.pattern.AEProcessingPattern$Input")) return null;
            var possible = slot.getPossibleInputs();
            if (possible.length != 1 || possible[0] == null || possible[0].amount() <= 0 || slot.getMultiplier() <= 0)
                return null;
            inputs.add(new Input(possible[0].what(), BigInteger.valueOf(possible[0].amount()),
                    BigInteger.valueOf(slot.getMultiplier()).multiply(coefficients.scale)));
        }
        var outputs = new ArrayList<Output>();
        for (var output : pattern.getOutputs()) {
            if (output == null || output.amount() <= 0) return null;
            outputs.add(new Output(output.what(), BigInteger.valueOf(output.amount()).multiply(coefficients.scale)));
        }
        return outputs.isEmpty() ? null : new Definition(inputs, outputs);
    }

    /** #218: expand original coefficients before an EAEP long multiplication can wrap. */
    static Coefficients coefficients(IPatternDetails source) {
        var result = NativePatternCoefficients.read(source);
        return new Coefficients(result.pattern(), result.scale());
    }
}
