package com.ae2vm.addon.vm;

import java.util.*;

/** #236: scoped read-only union over immutable or suspended maps, without flattening. */
final class VmOverlayMap<K, V> extends AbstractMap<K, V> {
    private final Map<K, V> base, overrides;
    VmOverlayMap(Map<K, V> base, Map<K, V> overrides) {
        this.base = base; this.overrides = overrides;
    }
    @Override public V get(Object key) {
        var value = overrides.get(key);
        return value != null || overrides.containsKey(key) ? value : base.get(key);
    }
    @Override public boolean containsKey(Object key) { return overrides.containsKey(key) || base.containsKey(key); }
    @Override public int size() {
        int result = base.size();
        for (var key : overrides.keySet()) if (!base.containsKey(key)) result++;
        return result;
    }
    @Override public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return VmOverlayMap.this.size(); }
            @Override public Iterator<Entry<K, V>> iterator() {
                var original = base.entrySet().iterator();
                var changed = overrides.entrySet().iterator();
                return new Iterator<>() {
                    Entry<K, V> next;
                    @Override public boolean hasNext() {
                        if (next != null) return true;
                        if (original.hasNext()) {
                            var entry = original.next();
                            next = new SimpleImmutableEntry<>(entry.getKey(), get(entry.getKey()));
                        } else while (changed.hasNext()) {
                            var entry = changed.next();
                            if (!base.containsKey(entry.getKey())) {
                                next = new SimpleImmutableEntry<>(entry); break;
                            }
                        }
                        return next != null;
                    }
                    @Override public Entry<K, V> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        var result = next; next = null; return result;
                    }
                };
            }
        };
    }
}
