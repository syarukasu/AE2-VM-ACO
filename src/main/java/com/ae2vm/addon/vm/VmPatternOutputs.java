package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ordered output validation adapted from Applied Enhancements' AelisPatternOutputs.
 * Copyright (c) 2026 HibikiShino. MIT; see META-INF/LICENSE-AppliedEnhancements.txt.
 * #232: sums and scaled coefficients stay exact, including above Long.MAX_VALUE.
 */
public record VmPatternOutputs(AEKey primary, Map<AEKey, BigInteger> amounts) {
    public VmPatternOutputs {
        amounts = Collections.unmodifiableMap(new LinkedHashMap<>(amounts));
    }

    public static VmPatternOutputs inspect(IPatternDetails pattern) {
        var primary = pattern.getPrimaryOutput();
        var outputs = pattern.getOutputs();
        var scale = pattern instanceof PatternCompiler.DetachedPattern detached
                ? detached.outputScale() : BigInteger.ONE;
        if (primary == null || primary.what() == null || primary.amount() <= 0
                || outputs == null || outputs.length == 0 || scale == null || scale.signum() <= 0)
            throw new IllegalArgumentException("positive pattern output and scale required");
        var amounts = new LinkedHashMap<AEKey, BigInteger>();
        for (var output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0)
                throw new IllegalArgumentException("positive pattern output required");
            amounts.merge(output.what(), BigInteger.valueOf(output.amount()).multiply(scale), BigInteger::add);
        }
        if (!amounts.containsKey(primary.what()))
            throw new IllegalArgumentException("primary key absent from pattern outputs");
        return new VmPatternOutputs(primary.what(), amounts);
    }

    public BigInteger amount(AEKey key) {
        var amount = amounts.get(key);
        if (amount == null) throw new IllegalArgumentException("pattern does not produce requested key");
        return amount;
    }
}
