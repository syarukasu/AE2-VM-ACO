package com.ae2vm.addon.nativeengine;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;

/** Immutable exact accounting payload, independent of any planner implementation. */
public record VmQuantities(AEKey root, BigInteger requested, Map<String, BigInteger> crafts,
        Map<AEKey, BigInteger> used, Map<AEKey, BigInteger> emitted, Map<AEKey, BigInteger> missing,
        BigInteger integerCharges, Map<AEKey, BigInteger> stackCharges, boolean multiplePaths,
        long instructions, long work, BigInteger skippedIterations) {
    public VmQuantities {
        crafts = Map.copyOf(crafts); used = Map.copyOf(used); emitted = Map.copyOf(emitted);
        missing = Map.copyOf(missing); stackCharges = Map.copyOf(stackCharges);
    }
    public double legacyBytes() { return integerCharges.doubleValue(); }
}
