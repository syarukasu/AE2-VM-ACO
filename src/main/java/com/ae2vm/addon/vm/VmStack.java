package com.ae2vm.addon.vm;

import appeng.api.stacks.AEKey;
import java.math.BigInteger;
import java.util.Objects;

public record VmStack(AEKey what, BigInteger amount) {
    public VmStack {
        Objects.requireNonNull(what);
        if (Objects.requireNonNull(amount).signum() < 0) throw new IllegalArgumentException("Negative amount");
    }
}
