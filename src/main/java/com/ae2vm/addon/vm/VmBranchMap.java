package com.ae2vm.addon.vm;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** #217: append/update-only trial map. The parent is unchanged until commit. */
final class VmBranchMap<K, V> extends AbstractMap<K, V> {
    private final Map<K, V> parent;
    private final Map<K, V> changes = new LinkedHashMap<>();
    private int added;

    VmBranchMap(Map<K, V> parent) { this.parent = parent; }
    @Override public V get(Object key) {
        V changed = changes.get(key);
        return changed != null ? changed : parent.get(key);
    }
    @Override public boolean containsKey(Object key) { return changes.containsKey(key) || parent.containsKey(key); }
    @Override public int size() { return parent.size() + added; }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(value);
        V previous = get(key);
        if (previous == null) added++;
        changes.put(key, value);
        return previous;
    }
    @Override public V remove(Object key) { throw new UnsupportedOperationException("Trial entries are not removed"); }
    @Override public void clear() { throw new UnsupportedOperationException("Discard the trial instead"); }
    @Override public Set<Entry<K, V>> entrySet() {
        // Materialize only for snapshot/replay iteration, never for a branch write.
        var merged = new LinkedHashMap<>(parent);
        merged.putAll(changes);
        return Collections.unmodifiableMap(merged).entrySet();
    }
    long changedEntries() { return changes.size(); }
    void commit() { changes.forEach(parent::put); }
}
