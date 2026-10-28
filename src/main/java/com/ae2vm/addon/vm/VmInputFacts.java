package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;

/** #236: captured input definitions only; never inventory, allocations or live callback failures. */
final class VmInputFacts {
    private static final int MAX_CANDIDATES = 8192;
    private final Map<PatternCompiler.DetachedInput, Map<AEKey, Candidate>> inputs = new IdentityHashMap<>();
    private final Map<Candidate, Boolean> recent = new LinkedHashMap<>(128, 0.75f, true);
    private int size;
    private static final class Candidate {
        final PatternCompiler.DetachedInput input;
        final AEKey key;
        Candidate(PatternCompiler.DetachedInput input, AEKey key) { this.input = input; this.key = key; }
        Boolean valid;
        boolean returnedKnown;
        AEKey returned;
        PatternCompiler.FiniteUse law;
    }

    boolean ready(PatternCompiler.DetachedInput input, AEKey key) {
        var candidates = inputs.get(input);
        var existing = candidates == null ? null : candidates.get(key);
        if (existing != null) { recent.get(existing); return true; }
        if (!input.isCandidatePrepared(key)) return false;
        remember(input, key);
        return true;
    }

    private Candidate remember(PatternCompiler.DetachedInput input, AEKey key) {
        var valuesForInput = inputs.get(input);
        var existing = valuesForInput == null ? null : valuesForInput.get(key);
        if (existing != null) { recent.get(existing); return existing; }
        if (size >= MAX_CANDIDATES) {
            var oldest = recent.keySet().iterator();
            var removed = oldest.next(); oldest.remove();
            var values = inputs.get(removed.input);
            values.remove(removed.key);
            if (values.isEmpty()) inputs.remove(removed.input);
            size--;
        }
        var added = new Candidate(input, key);
        inputs.computeIfAbsent(input, ignored -> new HashMap<>()).put(key, added);
        recent.put(added, Boolean.TRUE);
        size++;
        return added;
    }

    private Candidate candidate(IPatternDetails.IInput input, AEKey key) {
        if (!(input instanceof PatternCompiler.DetachedInput detached) || !ready(detached, key)) return null;
        var candidates = inputs.get(detached);
        return candidates == null ? null : candidates.get(key);
    }

    boolean valid(IPatternDetails.IInput input, AEKey key) {
        var fact = candidate(input, key);
        if (fact != null && fact.valid != null) return fact.valid;
        boolean valid = input.isValid(key, null);
        if (fact != null) fact.valid = valid;
        return valid;
    }

    AEKey returned(IPatternDetails.IInput input, AEKey key) {
        var fact = candidate(input, key);
        if (fact != null && fact.returnedKnown) return fact.returned;
        var returned = fact != null && fact.law instanceof RemainingUse
                ? after((PatternCompiler.DetachedInput) input, fact.law, BigInteger.ONE)
                : input.getRemainingKey(key);
        if (fact != null) { fact.returned = returned; fact.returnedKnown = true; }
        return returned;
    }

    PatternCompiler.FiniteUse finite(PatternCompiler.DetachedInput input, AEKey key) {
        var fact = candidate(input, key);
        if (fact != null && fact.law != null) return fact.law;
        var law = input.finiteUse(key);
        // A previously absent law can become available after more candidates are captured.
        if (fact != null && law != null) fact.law = law;
        return law;
    }

    int size() { return size; }

    AEKey after(PatternCompiler.DetachedInput input, PatternCompiler.FiniteUse law, BigInteger count) {
        var returned = law.after(count);
        if (returned != null && count.signum() > 0 && count.compareTo(law.uses()) < 0) {
            var fact = remember(input, returned);
            if (fact.valid == null) fact.valid = true;
            if (fact.law == null) fact.law = new RemainingUse(law, count);
        }
        return returned;
    }

    /** #236: certified intermediate returns need no second host-side semantic discovery. */
    private static final class RemainingUse implements PatternCompiler.FiniteUse {
        private final PatternCompiler.FiniteUse original;
        private final BigInteger offset, remaining;
        RemainingUse(PatternCompiler.FiniteUse law, BigInteger count) {
            remaining = law.uses().subtract(count);
            if (law instanceof RemainingUse prior) { original = prior.original; offset = prior.offset.add(count); }
            else { original = law; offset = count; }
        }
        public BigInteger uses() { return remaining; }
        public AEKey after(BigInteger count) {
            if (count.signum() <= 0 || count.compareTo(remaining) > 0) throw new IllegalArgumentException("Invalid finite-use count");
            return original.after(offset.add(count));
        }
    }
}
