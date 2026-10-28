package com.ae2vm.addon.vm;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** #217: append/update-only trial map. The parent is unchanged until commit. */
final class VmBranchMap<K, V> extends AbstractMap<K, V> {
    private static final Object ABSENT = new Object();
    private static final Object PRESENT_NULL = new Object();
    private final Map<K, V> parent;
    private K firstKey;
    private V firstValue;
    private Map<K, V> changes;
    private Object readKey, readValue;
    private Map<Object, Object> inherited;
    private boolean memoReads;
    private int added;

    VmBranchMap(Map<K, V> parent) { this.parent = parent; }
    VmBranchMap(Map<K, V> parent, boolean suspendedParent) {
        this(parent);
        memoReads = suspendedParent;
    }
    @SuppressWarnings("unchecked")
    @Override public V get(Object key) {
        if (firstValue != null && Objects.equals(firstKey, key)) return firstValue;
        V changed = changes == null ? null : changes.get(key);
        if (changed != null) return changed;
        if (!memoReads) return parent.get(key);
        var cached = readValue != null && Objects.equals(readKey, key) ? readValue
                : inherited == null ? null : inherited.get(key);
        if (cached != null) return cached == ABSENT || cached == PRESENT_NULL ? null : (V) cached;
        var value = parent.get(key);
        Object answer = value != null ? value : parent.containsKey(key) ? PRESENT_NULL : ABSENT;
        // #236: memoized absence stays null, never a ledger entry or an initial-stock override.
        if (readValue == null) {
            readKey = key;
            readValue = answer;
        } else {
            if (inherited == null) inherited = new java.util.HashMap<>();
            if (inherited.size() < 4095) inherited.put(key, answer);
        }
        return value;
    }
    @Override public boolean containsKey(Object key) {
        if (firstValue != null && Objects.equals(firstKey, key)
                || changes != null && changes.containsKey(key)) return true;
        if (memoReads) {
            if (get(key) != null) return true;
            var cached = readValue != null && Objects.equals(readKey, key) ? readValue
                    : inherited == null ? null : inherited.get(key);
            if (cached != null) return cached != ABSENT;
        }
        return parent.containsKey(key);
    }
    @Override public int size() { return parent.size() + added; }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(value);
        return putWithPrevious(key, value, get(key));
    }
    // #236: caller just read previous; no intervening mutation may invalidate it.
    V putWithPrevious(K key, V value, V previous) {
        Objects.requireNonNull(value);
        if (previous == null) added++;
        // #236: most rejected trials have zero/one writes; allocate overflow only on a second key.
        if (firstValue == null || Objects.equals(firstKey, key)) {
            firstKey = key;
            firstValue = value;
        } else {
            if (changes == null) changes = new LinkedHashMap<>();
            changes.put(key, value);
        }
        return previous;
    }
    @Override public V remove(Object key) { throw new UnsupportedOperationException("Trial entries are not removed"); }
    @Override public void clear() { throw new UnsupportedOperationException("Discard the trial instead"); }
    @Override public Set<Entry<K, V>> entrySet() {
        return Collections.unmodifiableMap(snapshot(this)).entrySet();
    }
    static <K, V> Map<K, V> snapshot(Map<K, V> source) {
        var result = new LinkedHashMap<K, V>();
        copyInto(source, result);
        return result;
    }
    static <K, V> void copyInto(Map<K, V> source, Map<K, V> target) {
        // #218: flatten once, not one full ancestor snapshot per nested trial.
        var layers = new ArrayDeque<VmBranchMap<K, V>>();
        while (true) {
            if (source instanceof VmJournalMap<K, V> journal) source = journal.backing();
            else if (source instanceof VmBranchMap<K, V> branch) {
                layers.addFirst(branch);
                source = branch.parent;
            } else break;
        }
        target.putAll(source);
        for (var layer : layers) layer.copyChanges(target);
    }
    private void copyChanges(Map<K, V> target) {
        if (firstValue != null) target.put(firstKey, firstValue);
        if (changes != null) changes.forEach(target::put);
    }
    long changedEntries() { return (firstValue == null ? 0 : 1) + (changes == null ? 0 : changes.size()); }
    boolean anyChanged(java.util.function.BiPredicate<K, V> predicate) {
        if (firstValue != null && predicate.test(firstKey, firstValue)) return true;
        if (changes != null) for (var entry : changes.entrySet())
            if (predicate.test(entry.getKey(), entry.getValue())) return true;
        return false;
    }
    void commit() {
        memoReads = false;
        readKey = readValue = null;
        inherited = null;
        copyChanges(parent);
    }
}
