package com.ae2vm.addon.vm;

import java.math.BigInteger;

/** Exact fractional storage cost; round up only when producing the final plan. */
public final class VmByteCost {
    private final BigInteger numerator, denominator;
    private volatile VmByteCost canonical;
    public static final VmByteCost ZERO = new VmByteCost(BigInteger.ZERO, BigInteger.ONE);
    public VmByteCost(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive byte unit");
        // #221: canonical integers need no gcd/division; retain exact fractional reduction.
        if (numerator.signum() == 0) denominator = BigInteger.ONE;
        else if (!denominator.equals(BigInteger.ONE)) {
            var gcd = numerator.gcd(denominator);
            numerator = numerator.divide(gcd);
            denominator = denominator.divide(gcd);
        }
        this.numerator = numerator;
        this.denominator = denominator;
        this.canonical = this;
    }
    private VmByteCost(BigInteger numerator, BigInteger denominator, boolean reduced) {
        this.numerator = numerator;
        this.denominator = numerator.signum() == 0 ? BigInteger.ONE : denominator;
        if (reduced || this.denominator.equals(BigInteger.ONE)) this.canonical = this;
    }
    private VmByteCost canonical() {
        var result = canonical;
        if (result == null) canonical = result = new VmByteCost(numerator, denominator);
        return result;
    }
    public BigInteger numerator() { return canonical().numerator; }
    public BigInteger denominator() { return canonical().denominator; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof VmByteCost b
                && numerator().equals(b.numerator()) && denominator().equals(b.denominator());
    }
    @Override public int hashCode() { return 31 * numerator().hashCode() + denominator().hashCode(); }
    @Override public String toString() { return "VmByteCost[numerator=" + numerator() + ", denominator=" + denominator() + "]"; }
    public VmByteCost add(VmByteCost b) {
        if (signum() == 0) return b;
        if (b.signum() == 0) return this;
        if (denominator.equals(b.denominator)) return new VmByteCost(numerator.add(b.numerator), denominator, false);
        // #236: retain a bounded common denominator; reduce wide quantities only when observed.
        var common = denominator.gcd(b.denominator);
        var left = common.equals(BigInteger.ONE) ? denominator : denominator.divide(common);
        var right = common.equals(BigInteger.ONE) ? b.denominator : b.denominator.divide(common);
        var sum = (right.equals(BigInteger.ONE) ? numerator : numerator.multiply(right))
                .add(left.equals(BigInteger.ONE) ? b.numerator : b.numerator.multiply(left));
        return new VmByteCost(sum, left.multiply(b.denominator), false);
    }
    public static VmByteCost forStack(BigInteger amount, int amountPerByte) {
        if (amountPerByte <= 0) throw new IllegalArgumentException("Nonpositive byte unit");
        // #236: reduce the constant fraction before touching an arbitrarily wide quantity.
        int common = Math.min(Integer.lowestOneBit(amountPerByte), 8);
        var numerator = common == 8 ? amount : amount.multiply(BigInteger.valueOf(8 / common));
        return new VmByteCost(numerator, BigInteger.valueOf(amountPerByte / common), false);
    }
    public VmByteCost subtract(VmByteCost b) { return add(b.multiply(BigInteger.ONE.negate())); }
    public VmByteCost multiply(BigInteger factor) {
        if (factor.signum() == 0) return ZERO;
        if (factor.equals(BigInteger.ONE)) return this;
        return new VmByteCost(numerator.multiply(factor), denominator, false);
    }
    public int signum() { return numerator.signum(); }
    public BigInteger ceiling() {
        if (denominator.equals(BigInteger.ONE)) return numerator;
        var parts = numerator.divideAndRemainder(denominator);
        return parts[1].signum() > 0 ? parts[0].add(BigInteger.ONE) : parts[0];
    }
}
