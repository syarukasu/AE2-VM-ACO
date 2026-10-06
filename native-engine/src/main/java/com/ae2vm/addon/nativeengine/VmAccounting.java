package com.ae2vm.addon.nativeengine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.Level;

/** Optional exact-count ownership extension. Never supplies patterns or performs planning. */
public interface VmAccounting {
    BigInteger maximumCount();
    Stock exactStock(IGrid grid);
    ICraftingPlan wideResult(IGrid grid, Level level, NativeVmResult result);

    /** Capture submission-validation metadata with the order, not after worker computation. */
    default Object orderContext(IGrid grid, Level level) { return null; }
    default ICraftingPlan wideResult(IGrid grid, Level level, NativeVmResult result, Object orderContext) {
        return wideResult(grid,level,result);
    }

    default Stock requestStock(IGrid grid, IActionSource source, boolean simulateExtraction) {
        if (source == null) return new Stock(Map.of(), true, Set.of());
        var stock = exactStock(grid);
        if (!simulateExtraction) return stock;
        var accessible = new java.util.LinkedHashMap<AEKey, BigInteger>();
        stock.amounts().forEach((key, amount) -> accessible.put(key, simulateExtraction(grid, key, amount, source)));
        return new Stock(accessible, stock.complete(), stock.exactKeys());
    }

    default BigInteger simulateExtraction(IGrid grid, AEKey key, BigInteger amount, IActionSource source) {
        if (amount.bitLength() > 63)
            throw new IllegalStateException("Exact simulated extraction is unavailable for " + key.getId());
        long available = grid.getStorageService().getInventory().extract(key, amount.longValueExact(), Actionable.SIMULATE, source);
        if (available < 0 || BigInteger.valueOf(available).compareTo(amount) > 0)
            throw new IllegalStateException("Invalid simulated extraction result for " + key.getId());
        return BigInteger.valueOf(available);
    }

    record Stock(Map<AEKey, BigInteger> amounts, boolean complete, Set<AEKey> exactKeys) {
        public Stock {
            amounts = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(amounts));
            exactKeys = Set.copyOf(exactKeys);
            if (amounts.values().stream().anyMatch(n -> n.signum() < 0))
                throw new IllegalArgumentException("negative exact stock");
        }
        public BigInteger amount(AEKey key) {
            if (!complete && !exactKeys.contains(key))
                throw new IllegalStateException("Exact inventory quantity unavailable: " + key.getId());
            return amounts.getOrDefault(key, BigInteger.ZERO);
        }
    }
}
