package com.ae2vm.addon.vm;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.config.FuzzyMode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** #236: immutable proof for pure accounting reuse, never an inventory reservation. */
public final class VmStockGuard {
    public record Rejection(String kind, AEKey key) { }
    private record Range(BigInteger minimum, BigInteger maximum) { }
    private final AEKey root;
    private final Map<AEKey, List<AEKey>> domains;
    private final Map<AEKey, Range> ranges;
    private final java.util.Set<Object> primaryKeys;

    private VmStockGuard(AEKey root, Map<AEKey, List<AEKey>> domains, Map<AEKey, Range> ranges) {
        this.root = root;
        this.domains = Map.copyOf(domains);
        this.ranges = Map.copyOf(ranges);
        primaryKeys = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        domains.keySet().forEach(key -> primaryKeys.add(key.getPrimaryKey()));
    }

    public boolean accepts(Map<AEKey, BigInteger> stock) {
        return rejection(stock) == null;
    }

    public Rejection rejection(Map<AEKey, BigInteger> stock) {
        for (var entry : ranges.entrySet()) {
            var value = entry.getKey().equals(root) ? BigInteger.ZERO : stock.getOrDefault(entry.getKey(), BigInteger.ZERO);
            var range = entry.getValue();
            if (value.compareTo(range.minimum()) < 0
                    || range.maximum() != null && value.compareTo(range.maximum()) > 0)
                return new Rejection("range", entry.getKey());
        }
        var index = new KeyCounter();
        // Match normalization and index insertion order, including hash-colliding NBT keys.
        for (var entry : Map.copyOf(stock).entrySet()) {
            if (entry.getValue().signum() < 0) return new Rejection("negative", entry.getKey());
            if (entry.getKey().equals(root) || entry.getValue().signum() == 0) continue;
            if (primaryKeys.contains(entry.getKey().getPrimaryKey())) index.add(entry.getKey(), 1);
        }
        for (var domain : domains.entrySet()) {
            int position = 0;
            for (var candidate : index.findFuzzy(domain.getKey(), FuzzyMode.IGNORE_ALL)) {
                if (position == domain.getValue().size()
                        || !domain.getValue().get(position++).equals(candidate.getKey()))
                    return new Rejection("domain_order", domain.getKey());
            }
            if (position != domain.getValue().size()) return new Rejection("domain_removed", domain.getKey());
        }
        return null;
    }

    static final class Builder {
        private final Map<AEKey, BigInteger> initial;
        private final AEKey root;
        private final Map<AEKey, List<AEKey>> domains = new HashMap<>();
        private int domainMembers;
        private final Map<AEKey, Range> ranges = new HashMap<>();
        private boolean supported;

        Builder(Map<AEKey, BigInteger> initial, AEKey root, boolean complete) {
            this.initial = new java.util.LinkedHashMap<>(Map.copyOf(initial));
            this.initial.put(root, BigInteger.ZERO);
            this.root = root;
            supported = complete;
            this.initial.forEach((key, value) -> {
                if (value.signum() < 0) supported = false;
            });
        }

        void unsupported() { supported = false; ranges.clear(); domains.clear(); }
        boolean supported() { return supported; }

        void domain(AEKey query, KeyCounter initialIndex) {
            if (!supported || domains.containsKey(query)) return;
            if (domains.size() >= 8192) { unsupported(); return; }
            var keys = new ArrayList<AEKey>();
            for (var entry : initialIndex.findFuzzy(query, FuzzyMode.IGNORE_ALL)) {
                if (++domainMembers > 16384) { unsupported(); return; }
                keys.add(entry.getKey());
            }
            domains.put(query, List.copyOf(keys));
        }

        void exact(AEKey key) {
            if (!supported || fixed(key)) return;
            var value = initial.getOrDefault(key, BigInteger.ZERO);
            constrain(key, value, value);
        }

        void positive(AEKey key, BigInteger available) {
            read(key, available, BigInteger.ONE, available.signum() <= 0);
        }

        void read(AEKey key, BigInteger available, BigInteger requested, boolean belowUnit) {
            if (!supported) return;
            if (available.signum() < 0) { unsupported(); return; }
            if (fixed(key)) return;
            var start = initial.getOrDefault(key, BigInteger.ZERO);
            var offset = available.subtract(start);
            if (belowUnit) constrain(key, offset.negate(), requested.subtract(BigInteger.ONE).subtract(offset));
            else if (available.compareTo(requested) >= 0) constrain(key, requested.subtract(offset), null);
            else constrain(key, start, start);
        }

        private boolean fixed(AEKey key) {
            var range = ranges.get(key);
            return range != null && range.minimum().equals(range.maximum());
        }

        private void constrain(AEKey key, BigInteger minimum, BigInteger maximum) {
            if (!supported) return;
            if (ranges.size() >= 8192 && !ranges.containsKey(key)) { unsupported(); return; }
            minimum = minimum.max(BigInteger.ZERO);
            var old = ranges.get(key);
            if (old != null) {
                minimum = minimum.max(old.minimum());
                if (old.maximum() != null) maximum = maximum == null ? old.maximum() : maximum.min(old.maximum());
            }
            if (maximum != null && minimum.compareTo(maximum) > 0) { unsupported(); return; }
            if (old != null && minimum.equals(old.minimum()) && java.util.Objects.equals(maximum, old.maximum())) return;
            ranges.put(key, new Range(minimum, maximum));
        }

        VmStockGuard finish() { return supported ? new VmStockGuard(root, domains, ranges) : null; }
    }
}
