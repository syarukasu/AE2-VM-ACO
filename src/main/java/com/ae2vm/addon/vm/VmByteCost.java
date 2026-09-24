package com.ae2vm.addon.vm;

import java.math.BigInteger;

/** Exact fractional storage cost; round up only when producing the final plan. */
public record VmByteCost(BigInteger numerator, BigInteger denominator) {
    public static final VmByteCost ZERO = new VmByteCost(BigInteger.ZERO, BigInteger.ONE);
    public VmByteCost {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive byte unit");
        var gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }
    public VmByteCost add(VmByteCost b) {
        return new VmByteCost(numerator.multiply(b.denominator).add(b.numerator.multiply(denominator)), denominator.multiply(b.denominator));
    }
    public VmByteCost subtract(VmByteCost b) { return add(b.multiply(BigInteger.ONE.negate())); }
    public VmByteCost multiply(BigInteger factor) { return new VmByteCost(numerator.multiply(factor), denominator); }
    public int signum() { return numerator.signum(); }
    public BigInteger ceiling() {
        var parts = numerator.divideAndRemainder(denominator);
        return parts[1].signum() > 0 ? parts[0].add(BigInteger.ONE) : parts[0];
    }
}
