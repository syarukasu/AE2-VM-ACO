package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Map;

/** Exact result, not a generated item/receipt. Bindings remain AE2-owned. */
public record NativeVmResult(VmQuantities amounts,
        Map<String, IPatternDetails> bindings, BigInteger bytes, boolean wideQuantity) {
    public NativeVmResult { bindings = Map.copyOf(bindings); }
    public boolean wide() { return wideQuantity || bytes.bitLength() > 63; }
}
