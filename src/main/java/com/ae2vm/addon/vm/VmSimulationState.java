package com.ae2vm.addon.vm;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.*;

/** Worker-owned scratch inventory, constructed from an immutable exact snapshot. */
public final class VmSimulationState {
    private final VmKeyCounter stock;
    private final Map<AEKey, BigInteger> initial;
    private VmByteCost bytes = VmByteCost.ZERO;
    public VmSimulationState(Map<AEKey, BigInteger> initial) {
        this.initial = Map.copyOf(initial);
        if (initial.values().stream().anyMatch(v -> v.signum() < 0)) throw new IllegalArgumentException("Negative initial stock");
        stock = new VmKeyCounter(initial);
    }
    public Map<AEKey, BigInteger> initialStock() { return initial; }
    public BigInteger available(AEKey key) { return stock.get(key); }
    public BigInteger extract(AEKey key, BigInteger amount, Actionable mode) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative extraction");
        var result = amount.min(available(key));
        if (mode == Actionable.MODULATE) stock.remove(key, result);
        return result;
    }
    public void insert(AEKey key, BigInteger amount, Actionable mode) {
        if (amount.signum() < 0) throw new IllegalArgumentException("Negative insertion");
        if (mode == Actionable.MODULATE) stock.add(key, amount);
    }
    public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
        return stock.findFuzzy(key, FuzzyMode.IGNORE_ALL).stream().map(Map.Entry::getKey).toList();
    }
    public void addCrafting(IPatternDetails pattern, BigInteger times) { }
    public void addBytes(BigInteger count) { addBytes(new VmByteCost(count, BigInteger.ONE)); }
    public void addBytes(VmByteCost cost) { bytes = bytes.add(cost); }
    public void addStackBytes(AEKey key, BigInteger amount, BigInteger multiplier) {
        addBytes(new VmByteCost(amount.multiply(multiplier).multiply(BigInteger.valueOf(8)),
                BigInteger.valueOf(key.getType().getAmountPerByte())));
    }
    public VmByteCost byteCost() { return bytes; }
}
