package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** #236: optimistic gross supply, never a funded allocation or a missing-material verdict. */
final class VmSupplyUpperBound {
    private record Family(Object type, Object primary) {
        static Family of(AEKey key) { return new Family(key.getType(), key.getPrimaryKey()); }
    }
    private record Need(AEKey key, List<AEKey> families, BigInteger amount) { }
    private record Source(IPatternDetails pattern, BigInteger output) { }
    private final Map<IPatternDetails, List<Need>> constraints = new IdentityHashMap<>();
    private final Map<AEKey, List<Source>> producers = new HashMap<>();
    private final Map<Family, Set<AEKey>> familyOutputs = new HashMap<>();
    private final Set<AEKey> captured;
    private final Set<Family> returned = new HashSet<>();
    private final Set<AEKey> boundedKeys = new HashSet<>();
    private final Set<IPatternDetails> boundedPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Family> boundedFamilies = new HashSet<>();
    private final Map<Object, Memo> versions = new LinkedHashMap<>(4, 0.75f, true);
    private static final class Version {
        final Object stamp = new Object();
        Memo memo;
    }
    static Object version() { return new Version(); }
    static Object stamp(Object version) { return version instanceof Version v ? v.stamp : version; }
    private Memo memo(Object version) {
        if (version instanceof Version local) {
            if (local.memo == null) local.memo = new Memo();
            return local.memo;
        }
        var result = versions.get(version);
        if (result == null) {
            if (versions.size() >= 4) versions.remove(versions.keySet().iterator().next());
            result = new Memo(); versions.put(version, result);
        }
        return result;
    }
    private static final class Memo {
        final Map<AEKey, Optional<BigInteger>> supplies = new HashMap<>();
        final Map<IPatternDetails, Optional<BigInteger>> patterns = new IdentityHashMap<>();
        final Map<Family, Optional<BigInteger>> families = new HashMap<>();
        final Map<AEKey, Capped> cappedSupplies = new HashMap<>();
        final Map<IPatternDetails, Capped> cappedPatterns = new IdentityHashMap<>();
        final Map<Family, Capped> cappedFamilies = new HashMap<>();
    }
    private record Capped(BigInteger value, BigInteger cap) {
        boolean covers(BigInteger requested) { return value.compareTo(cap) < 0 || requested.compareTo(cap) <= 0; }
        BigInteger at(BigInteger requested) { return value.min(requested); }
    }
    private VmSupplyUpperBound(Set<AEKey> captured) { this.captured = Set.copyOf(captured); }

    static VmSupplyUpperBound compile(Map<AEKey, List<IPatternDetails>> catalog, Runnable checkpoint) {
        var graph = new VmSupplyUpperBound(catalog.keySet());
        var seen = Collections.newSetFromMap(new IdentityHashMap<IPatternDetails, Boolean>());
        for (var definitions : catalog.values()) for (var pattern : definitions) {
            checkpoint.run();
            if (!seen.add(pattern)) continue;
            var needs = new ArrayList<Need>();
            for (var slot : pattern.getInputs()) {
                if (!(slot instanceof PatternCompiler.DetachedInput input)) return null;
                if (input.mayReturnItems()) {
                    var families = input.returnFamilyUpperBound();
                    if (families == null) return null;
                    for (var key : families) {
                        if (key == null) return null;
                        graph.returned.add(Family.of(key));
                    }
                    continue;
                }
                var key = input.exactKey();
                if (input.emittable() || !input.isCandidatePrepared(input.craftedKey())) continue;
                var templates = input.getPossibleInputs();
                if (templates.length == 0) return null;
                var alternatives = new LinkedHashMap<Family, AEKey>();
                long minimum = Long.MAX_VALUE;
                for (var template : templates) {
                    if (template.amount() <= 0) return null;
                    minimum = Math.min(minimum, template.amount());
                    alternatives.putIfAbsent(Family.of(template.what()), template.what());
                }
                if (key != null && (!key.equals(input.craftedKey()) || templates.length != 1)) continue;
                var amount = PatternCompiler.inputMultiplier(input)
                        .multiply(BigInteger.valueOf(minimum));
                if (amount.signum() <= 0) return null;
                needs.add(new Need(key, List.copyOf(alternatives.values()), amount));
            }
            graph.constraints.put(pattern, List.copyOf(needs));
            VmPatternOutputs.inspect(pattern).amounts().forEach((key, amount) ->
                    graph.producers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Source(pattern, amount)));
        }
        for (var key : graph.producers.keySet())
            graph.familyOutputs.computeIfAbsent(Family.of(key), ignored -> new HashSet<>()).add(key);
        graph.compileBoundedness(checkpoint);
        return graph;
    }

    private void compileBoundedness(Runnable checkpoint) {
        var allFamilies = new HashSet<Family>(familyOutputs.keySet());
        var keyDepth = new HashMap<AEKey, Integer>();
        var familyDepth = new HashMap<Family, Integer>();
        for (var needs : constraints.values()) for (var need : needs)
            for (var key : need.families) allFamilies.add(Family.of(key));
        boolean changed;
        int depth = 0;
        do {
            checkpoint.run();
            changed = false;
            for (var key : captured) if (!boundedKeys.contains(key) && !returned.contains(Family.of(key))
                    && producers.getOrDefault(key, List.of()).stream().allMatch(source -> boundedPatterns.contains(source.pattern)))
                if (boundedKeys.add(key)) { changed = true; keyDepth.put(key, depth); }
            for (var family : allFamilies) if (!returned.contains(family)
                    && boundedKeys.containsAll(familyOutputs.getOrDefault(family, Set.of())))
                if (boundedFamilies.add(family)) { changed = true; familyDepth.put(family, depth); }
            for (var entry : constraints.entrySet()) if (!boundedPatterns.contains(entry.getKey()))
                for (var need : entry.getValue()) {
                    boolean finite = need.key != null ? boundedKeys.contains(need.key)
                            : need.families.stream().allMatch(key -> boundedFamilies.contains(Family.of(key)));
                    if (finite) { changed |= boundedPatterns.add(entry.getKey()); break; }
                }
            depth++;
        } while (changed);
        // Necessary bounds commute; a cheap zero avoids expanding unrelated shared paths.
        constraints.replaceAll((pattern, needs) -> needs.stream().sorted(Comparator.comparingInt(need -> {
            if (need.key != null) return keyDepth.getOrDefault(need.key, Integer.MAX_VALUE);
            int maximum = 0;
            for (var key : need.families)
                maximum = Math.max(maximum, familyDepth.getOrDefault(Family.of(key), Integer.MAX_VALUE));
            return maximum;
        })).toList());
    }

    BigInteger limit(IPatternDetails pattern, Object version, Function<AEKey, BigInteger> stock, Runnable checkpoint) {
        return limit(pattern, version, stock, key -> null, checkpoint);
    }

    BigInteger limit(IPatternDetails pattern, Object version, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, Runnable checkpoint) {
        checkpoint.run();
        var memo = memo(version);
        var saved = memo.patterns.get(pattern);
        if (saved != null) return saved.orElse(null);
        var result = patternLimit(pattern, stock, familyStock, memo, new HashSet<>(), checkpoint);
        if (memo.patterns.size() < 4096) memo.patterns.put(pattern, Optional.ofNullable(result));
        return result;
    }

    private BigInteger patternLimit(IPatternDetails pattern, Function<AEKey, BigInteger> stock, Function<AEKey, BigInteger> familyStock,
            Memo memo, Set<AEKey> path, Runnable checkpoint) {
        if (!boundedPatterns.contains(pattern)) return null;
        BigInteger limit = null;
        for (var need : constraints.getOrDefault(pattern, List.of())) {
            checkpoint.run();
            BigInteger available;
            if (need.key != null) available = supply(need.key, stock, familyStock, memo, path, checkpoint);
            else {
                available = BigInteger.ZERO;
                for (var family : need.families) {
                    var next = familySupply(family, stock, familyStock, memo, path, checkpoint);
                    if (next == null) { available = null; break; }
                    available = available.add(next);
                }
            }
            if (available == null) continue;
            var next = available.divide(need.amount);
            limit = limit == null ? next : limit.min(next);
            if (limit.signum() == 0) break;
        }
        return limit;
    }

    /** #236: interest is bounded by the request, never by narrowing the inventory. */
    BigInteger limit(IPatternDetails pattern, Object version, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, BigInteger requested, Runnable checkpoint) {
        if (requested.signum() <= 0) return BigInteger.ZERO;
        var memo = memo(version);
        return cappedPattern(pattern, requested, stock, familyStock, memo, new HashSet<>(), checkpoint);
    }

    private static BigInteger quotient(BigInteger value, BigInteger amount) {
        int shift = amount.getLowestSetBit();
        if (amount.bitLength() == shift + 1) return value.shiftRight(shift);
        return value.divide(amount);
    }

    private BigInteger cappedPattern(IPatternDetails pattern, BigInteger cap, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, Memo memo, Set<AEKey> path, Runnable checkpoint) {
        checkpoint.run();
        if (!boundedPatterns.contains(pattern)) return null;
        var saved = memo.cappedPatterns.get(pattern);
        if (saved != null && saved.covers(cap)) return saved.at(cap);
        var limit = cap;
        for (var need : constraints.getOrDefault(pattern, List.of())) {
            var wanted = limit.multiply(need.amount);
            BigInteger available;
            if (need.key != null) available = cappedSupply(need.key, wanted, stock, familyStock, memo, path, checkpoint);
            else {
                available = BigInteger.ZERO;
                for (var family : need.families) {
                    var remaining = wanted.subtract(available);
                    if (remaining.signum() <= 0) break;
                    var next = cappedFamily(family, remaining, stock, familyStock, memo, path, checkpoint);
                    if (next == null) { available = null; break; }
                    available = available.add(next);
                }
            }
            if (available != null) limit = limit.min(quotient(available, need.amount));
            if (limit.signum() == 0) break;
        }
        if (memo.cappedPatterns.size() < 4096) memo.cappedPatterns.put(pattern, new Capped(limit, cap));
        return limit;
    }

    private BigInteger cappedSupply(AEKey key, BigInteger cap, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, Memo memo, Set<AEKey> path, Runnable checkpoint) {
        checkpoint.run();
        var total = stock.apply(key);
        if (total.compareTo(cap) >= 0) return cap;
        if (!boundedKeys.contains(key) || path.contains(key) || path.size() >= 64) return null;
        var saved = memo.cappedSupplies.get(key);
        if (saved != null && saved.covers(cap)) return saved.at(cap);
        path.add(key);
        try {
            for (var source : producers.getOrDefault(key, List.of())) {
                var wanted = PatternCompiler.ceilDiv(cap.subtract(total), source.output);
                var times = cappedPattern(source.pattern, wanted, stock, familyStock, memo, path, checkpoint);
                if (times == null) return null;
                total = total.add(times.multiply(source.output));
                if (total.compareTo(cap) >= 0) { total = cap; break; }
            }
        } finally { path.remove(key); }
        if (memo.cappedSupplies.size() < 4096) memo.cappedSupplies.put(key, new Capped(total, cap));
        return total;
    }

    private BigInteger cappedFamily(AEKey template, BigInteger cap, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, Memo memo, Set<AEKey> path, Runnable checkpoint) {
        checkpoint.run();
        var family = Family.of(template);
        var total = familyStock.apply(template);
        if (total == null) return null;
        if (total.compareTo(cap) >= 0) return cap;
        if (!boundedFamilies.contains(family)) return null;
        for (var key : path) if (Family.of(key).equals(family)) return null;
        var saved = memo.cappedFamilies.get(family);
        if (saved != null && saved.covers(cap)) return saved.at(cap);
        for (var key : familyOutputs.getOrDefault(family, Set.of())) {
            if (!path.add(key)) return null;
            try {
                for (var source : producers.getOrDefault(key, List.of())) {
                    var wanted = PatternCompiler.ceilDiv(cap.subtract(total), source.output);
                    var times = cappedPattern(source.pattern, wanted, stock, familyStock, memo, path, checkpoint);
                    if (times == null) return null;
                    total = total.add(times.multiply(source.output));
                    if (total.compareTo(cap) >= 0) { total = cap; break; }
                }
            } finally { path.remove(key); }
            if (total.compareTo(cap) >= 0) break;
        }
        if (memo.cappedFamilies.size() < 4096) memo.cappedFamilies.put(family, new Capped(total, cap));
        return total;
    }

    private BigInteger supply(AEKey key, Function<AEKey, BigInteger> stock, Function<AEKey, BigInteger> familyStock,
            Memo memo, Set<AEKey> path, Runnable checkpoint) {
        checkpoint.run();
        if (!boundedKeys.contains(key) || path.contains(key) || path.size() >= 64) return null;
        var saved = memo.supplies.get(key);
        if (saved != null) return saved.orElse(null);
        BigInteger total = stock.apply(key);
        path.add(key);
        try {
            for (var source : producers.getOrDefault(key, List.of())) {
                var bound = patternLimit(source.pattern, stock, familyStock, memo, path, checkpoint);
                if (bound == null) { total = null; break; }
                total = total.add(bound.multiply(source.output));
            }
        } finally { path.remove(key); }
        if (memo.supplies.size() < 4096) memo.supplies.put(key, Optional.ofNullable(total));
        return total;
    }

    private BigInteger familySupply(AEKey template, Function<AEKey, BigInteger> stock,
            Function<AEKey, BigInteger> familyStock, Memo memo, Set<AEKey> path, Runnable checkpoint) {
        var family = Family.of(template);
        if (!boundedFamilies.contains(family)) return null;
        // Any recursive member makes this family potentially self-supplying.
        for (var key : path) if (Family.of(key).equals(family)) return null;
        var saved = memo.families.get(family);
        if (saved != null) return saved.orElse(null);
        var total = familyStock.apply(template);
        if (total == null) return null;
        for (var key : familyOutputs.getOrDefault(family, Set.of())) {
            var produced = supply(key, stock, familyStock, memo, path, checkpoint);
            if (produced == null) { total = null; break; }
            total = total.add(produced.subtract(stock.apply(key)));
        }
        if (memo.families.size() < 4096) memo.families.put(family, Optional.ofNullable(total));
        return total;
    }
}
