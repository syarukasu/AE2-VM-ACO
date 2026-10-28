package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** #236: quantity-independent failed first-input prefixes; never a stock allocation. */
final class VmZeroSupply {
    record Process(IPatternDetails pattern, int slots, Node first) {
        BigInteger nodes() { return first.nodes.add(BigInteger.valueOf(slots - 1)); }
    }
    static final class Node {
        final List<Process> processes;
        final Map<AEKey, BigInteger> guards;
        final Map<AEKey, BigInteger> producerGuards;
        final Set<AEKey> domains;
        final BigInteger nodes;
        final boolean multiple;
        Node(AEKey key, BigInteger required, List<Process> processes, Map<AEKey, BigInteger> guards, Set<AEKey> domains) {
            this.processes = List.copyOf(processes);
            this.producerGuards = Map.copyOf(guards);
            guards.merge(key, required, BigInteger::min);
            this.guards = Map.copyOf(guards);
            this.domains = Set.copyOf(domains);
            var count = BigInteger.ONE;
            boolean alternatives = processes.size() > 1;
            for (var process : processes) {
                count = count.add(process.nodes());
                alternatives |= process.first.multiple;
            }
            nodes = count;
            multiple = alternatives;
        }
        Process process(IPatternDetails pattern) {
            for (var process : processes) if (process.pattern == pattern) return process;
            return null;
        }
    }
    private record Path(AEKey key, BigInteger required, Set<AEKey> ancestors) { }
    private final Function<AEKey, List<IPatternDetails>> resolver;
    private final Predicate<AEKey> emitters;
    private final Runnable checkpoint;
    private final Map<AEKey, Optional<Set<AEKey>>> relevant = new HashMap<>();
    private final Map<Path, Optional<Node>> proofs = new HashMap<>();
    private int weight, work;
    private long requests, limits, unsupported;
    String diagnostics() { return "requests=" + requests + ",limits=" + limits + ",unsupported=" + unsupported
            + ",proofs=" + proofs.size() + ",weight=" + weight; }

    VmZeroSupply(Function<AEKey, List<IPatternDetails>> resolver, Predicate<AEKey> emitters, Runnable checkpoint) {
        this.resolver = resolver; this.emitters = emitters; this.checkpoint = checkpoint;
    }

    Node get(AEKey key, Set<AEKey> ancestors) {
        requests++;
        work = 0;
        try { return build(key, BigInteger.ONE, ancestors, 0); }
        catch (Decline unsupported) { limits++; return null; }
    }

    private void step() {
        checkpoint.run();
        if (++work > 8192) throw new Decline();
    }

    private Node build(AEKey key, BigInteger required, Set<AEKey> ancestors, int depth) {
        step();
        if (depth > 64 || emitters.test(key)) return null;
        var domain = relevant(key);
        if (domain == null) return null;
        var restricted = new HashSet<>(ancestors);
        restricted.retainAll(domain);
        var path = new Path(key, required, Set.copyOf(restricted));
        var cached = proofs.get(path);
        if (cached != null) return cached.orElse(null);
        var children = new ArrayList<Process>();
        var guards = new HashMap<AEKey, BigInteger>();
        var domains = new HashSet<AEKey>();
        var next = new HashSet<>(restricted); next.add(key);
        for (var pattern : resolver.apply(key)) {
            step();
            if (conflicts(pattern, restricted)) continue;
            var slots = pattern.getInputs();
            if (slots.length == 0 || !(slots[0] instanceof PatternCompiler.DetachedInput input)
                    || input.craftedKey() == null || input.emittable() || !input.isCandidatePrepared(input.craftedKey())
                    || PatternCompiler.inputMultiplier(input).signum() <= 0
                    || slots[0].getPossibleInputs()[0].amount() <= 0) { unsupported++; return remember(path, null); }
            // #236: finite allocation reserves ordinary ingredients before tools; bytecode uses slot order.
            for (int i = 1; i < slots.length; i++)
                if (!(slots[i] instanceof PatternCompiler.DetachedInput other)
                        || other.craftedKey() == null
                        || !other.isCandidatePrepared(other.craftedKey())
                        || input.mayReturnItems() && !other.mayReturnItems())
                    return remember(path, null);
            var minimum = PatternCompiler.inputMultiplier(input)
                    .multiply(BigInteger.valueOf(input.getPossibleInputs()[0].amount()));
            if (input.exactKey() == null) {
                for (var template : input.getPossibleInputs()) domains.add(template.what());
            } else guards.merge(input.exactKey(), minimum, BigInteger::min);
            var child = build(input.craftedKey(), minimum, next, depth + 1);
            if (child == null) return remember(path, null);
            child.guards.forEach((guard, bound) -> guards.merge(guard, bound, BigInteger::min));
            domains.addAll(child.domains);
            if (guards.size() + domains.size() > 4096) return null;
            children.add(new Process(pattern, slots.length, child));
        }
        return remember(path, new Node(key, required, children, guards, domains));
    }

    private Node remember(Path path, Node node) {
        int cost = path.ancestors.size() + 1 + (node == null ? 0 : node.guards.size()
                + node.producerGuards.size() + node.domains.size() + node.processes.size());
        if (proofs.size() < 4096 && weight + cost <= 65536) {
            proofs.put(path, Optional.ofNullable(node)); weight += cost;
        }
        return node;
    }

    private Set<AEKey> relevant(AEKey root) {
        var cached = relevant.get(root);
        if (cached != null) return cached.orElse(null);
        var result = new HashSet<AEKey>();
        var visited = new HashSet<AEKey>();
        var pending = new ArrayDeque<AEKey>(); pending.add(root);
        while (!pending.isEmpty()) {
            step();
            var key = pending.removeFirst();
            if (!visited.add(key)) continue;
            result.add(key);
            for (var pattern : resolver.apply(key)) {
                for (var output : pattern.getOutputs()) result.add(output.what());
                var slots = pattern.getInputs();
                for (var input : slots) result.add(input.getPossibleInputs()[0].what());
                if (slots.length > 0 && slots[0] instanceof PatternCompiler.DetachedInput input && input.craftedKey() != null)
                    pending.add(input.craftedKey());
            }
            if (result.size() > 4096) return null;
        }
        var immutable = Set.copyOf(result);
        if (relevant.size() < 1024 && weight + result.size() <= 65536) {
            relevant.put(root, Optional.of(immutable)); weight += result.size();
        }
        return immutable;
    }

    private static boolean conflicts(IPatternDetails pattern, Set<AEKey> ancestors) {
        for (var output : pattern.getOutputs()) if (ancestors.contains(output.what())) return true;
        for (var slot : pattern.getInputs()) if (ancestors.contains(slot.getPossibleInputs()[0].what())) return true;
        return false;
    }
    private static final class Decline extends RuntimeException {
        Decline() { super(null, null, false, false); }
    }
}
