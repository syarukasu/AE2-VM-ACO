package com.syaru.ae2vm.exact;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Function;

/** Caller-owned immutable observations and deterministic key ordering; no world access here. */
public interface ExactBranchInputRules<K> {
    Input<K> input(ExactBranchBytecode<K> pattern, int slot);
    KeyIndex<K> newIndex();
    List<K> storedFuzzyKeys(K key);

    record Template<K>(K key, BigInteger amount) {
        public Template {
            if (key == null || amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("invalid input template");
        }
    }
    record Observation<K>(boolean valid, K remainder) { }
    record Input<K>(List<Template<K>> templates, K craftedKey, boolean emittable,
            Function<K, Observation<K>> observe) {
        public Input { templates = List.copyOf(templates); }
    }
    interface KeyIndex<K> {
        void add(K key);
        List<K> fuzzy(K key);
        List<K> keys();
    }
}
