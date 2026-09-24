package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.*;

/** Quantity storage for the VM only; never an AE2 inventory facade. */
public final class VmKeyCounter implements Iterable<Map.Entry<AEKey, BigInteger>> {
    private final Map<AEKey, BigInteger> amounts;
    public VmKeyCounter() { amounts = new LinkedHashMap<>(); }
    public VmKeyCounter(Map<AEKey, BigInteger> initial) { amounts = new LinkedHashMap<>(initial); }
    private VmKeyCounter(VmKeyCounter source) { amounts = Map.copyOf(source.amounts); }
    public VmKeyCounter frozen() { return new VmKeyCounter(this); }
    public BigInteger get(AEKey key) { return amounts.getOrDefault(key, BigInteger.ZERO); }
    public void set(AEKey key, BigInteger amount) { amounts.put(key, Objects.requireNonNull(amount)); }
    public void add(AEKey key, BigInteger amount) { set(key, get(key).add(amount)); }
    public void remove(AEKey key) { amounts.remove(key); }
    public void remove(AEKey key, BigInteger amount) { add(key, amount.negate()); }
    public boolean isEmpty() { return amounts.values().stream().allMatch(v -> v.signum() == 0); }
    public int size() { return (int) amounts.values().stream().filter(v -> v.signum() != 0).count(); }
    public Set<AEKey> keySet() { return Collections.unmodifiableSet(amounts.keySet()); }
    public List<Map.Entry<AEKey, BigInteger>> findFuzzy(AEKey key, FuzzyMode mode) {
        return amounts.entrySet().stream().filter(e -> key.fuzzyEquals(e.getKey(), mode))
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }
    public Map<AEKey, BigInteger> quantities() {
        Map<AEKey, BigInteger> result = new LinkedHashMap<>();
        amounts.forEach((key, value) -> { if (value.signum() != 0) result.put(key, value); });
        return Map.copyOf(result);
    }
    @Override public Iterator<Map.Entry<AEKey, BigInteger>> iterator() {
        return Collections.unmodifiableMap(amounts).entrySet().iterator();
    }
}
