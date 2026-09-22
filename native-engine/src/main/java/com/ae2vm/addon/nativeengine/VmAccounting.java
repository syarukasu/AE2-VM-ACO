package com.ae2vm.addon.nativeengine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
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
