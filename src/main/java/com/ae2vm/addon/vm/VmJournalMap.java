package com.ae2vm.addon.vm;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** #218: scoped before-values for replay, not a copy of the whole visited ledger. */
final class VmJournalMap<K, V> extends AbstractMap<K, V> {
    private final Map<K, V> values;
    private final List<Snapshot<K, V>> snapshots = new ArrayList<>();

    VmJournalMap() { this(new LinkedHashMap<>()); }
    VmJournalMap(Map<K, V> values) { this.values = values; }
    Map<K, V> backing() { return values; }
    @Override public V get(Object key) { return values.get(key); }
    @Override public boolean containsKey(Object key) { return values.containsKey(key); }
    @Override public int size() { return values.size(); }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(value);
        return putWithPrevious(key, value, values.get(key));
    }
    @Override public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        var old = values.get(key);
        var merged = old == null ? value : remappingFunction.apply(old, value);
        if (merged == null) return remove(key);
        putWithPrevious(key, merged, old);
        return merged;
    }
    private V putWithPrevious(K key, V value, V old) {
        if (Objects.equals(old, value)) return old;
        for (var snapshot : snapshots)
            if (!snapshot.original.containsKey(key)) snapshot.original.put(key, old);
        // #236: keep journal before-values, but do not read through the branch twice.
        return values instanceof VmBranchMap<K, V> branch
                ? branch.putWithPrevious(key, value, old) : values.put(key, value);
    }
    @Override public Set<Entry<K, V>> entrySet() { return Collections.unmodifiableMap(values).entrySet(); }
    @Override public V remove(Object key) { throw new UnsupportedOperationException("Ledger keys are not removed"); }
    @Override public void clear() { throw new UnsupportedOperationException("Discard the ledger instead"); }

    Snapshot<K, V> snapshot() {
        var snapshot = new Snapshot<K, V>(this);
        snapshots.add(snapshot);
        return snapshot;
    }
    long changedEntries() { return ((VmBranchMap<K, V>) values).changedEntries(); }
    void commit() { ((VmBranchMap<K, V>) values).commit(); }

    static final class Snapshot<K, V> extends AbstractMap<K, V> implements AutoCloseable {
        private static final Object ABSENT = new Object();
        private final VmJournalMap<K, V> owner;
        private final Map<K, V> original = new LinkedHashMap<>();
        private Map<Object, Object> reads;
        private final int size;
        private boolean closed;
        Snapshot(VmJournalMap<K, V> owner) { this.owner = owner; size = owner.size(); }
        private void checkOpen() { if (closed) throw new IllegalStateException("Replay checkpoint is closed"); }
        @SuppressWarnings("unchecked")
        @Override public V get(Object key) {
            checkOpen();
            if (reads != null) {
                var cached = reads.get(key);
                if (cached != null) return cached == ABSENT ? null : (V) cached;
            }
            var value = original.get(key);
            if (value == null && !original.containsKey(key)) value = owner.get(key);
            // Before-values are immutable; memo reads must not become journaled changes.
            if (reads == null) reads = new java.util.HashMap<>();
            if (reads.size() < 4096) reads.put(key, value == null ? ABSENT : value);
            return value;
        }
        @Override public boolean containsKey(Object key) { return get(key) != null; }
        @Override public int size() { checkOpen(); return size; }
        Set<K> changedKeys() { checkOpen(); return Collections.unmodifiableSet(original.keySet()); }
        @Override public Set<Entry<K, V>> entrySet() {
            checkOpen();
            var result = VmBranchMap.snapshot(owner);
            original.forEach((key, value) -> { if (value == null) result.remove(key); else result.put(key, value); });
            return Collections.unmodifiableMap(result).entrySet();
        }
        @Override public void close() {
            if (closed) return;
            for (int i = 0; i < owner.snapshots.size(); i++) {
                if (owner.snapshots.get(i) == this) { owner.snapshots.remove(i); break; }
            }
            closed = true;
            original.clear();
            reads = null;
        }
    }
}
