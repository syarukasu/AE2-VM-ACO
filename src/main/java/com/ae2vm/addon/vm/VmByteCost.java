package com.ae2vm.addon.vm;

import java.math.BigInteger;

/** Exact fractional storage cost; round up only when producing the final plan. */
public record VmByteCost(BigInteger numerator, BigInteger denominator) {
    public static final VmByteCost ZERO = new VmByteCost(BigInteger.ZERO, BigInteger.ONE);
    public VmByteCost {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive byte unit");
        // #221: canonical integers need no gcd/division; retain exact fractional reduction.
        if (numerator.signum() == 0) denominator = BigInteger.ONE;
        else if (!denominator.equals(BigInteger.ONE)) {
            var gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
    }
    public VmByteCost add(VmByteCost b) {
        if (signum() == 0) return b;
        if (b.signum() == 0) return this;
        if (denominator.equals(b.denominator)) return new VmByteCost(numerator.add(b.numerator), denominator);
        return new VmByteCost(numerator.multiply(b.denominator).add(b.numerator.multiply(denominator)), denominator.multiply(b.denominator));
    }
    public VmByteCost subtract(VmByteCost b) { return add(b.multiply(BigInteger.ONE.negate())); }
    public VmByteCost multiply(BigInteger factor) {
        if (factor.signum() == 0) return ZERO;
        if (factor.equals(BigInteger.ONE)) return this;
        return new VmByteCost(numerator.multiply(factor), denominator);
    }
    public int signum() { return numerator.signum(); }
    public BigInteger ceiling() {
        if (denominator.equals(BigInteger.ONE)) return numerator;
        var parts = numerator.divideAndRemainder(denominator);
        return parts[1].signum() > 0 ? parts[0].add(BigInteger.ONE) : parts[0];
    }
}
