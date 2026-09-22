package com.syaru.ae2vm.exact;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactBranchVMTest {
    @Test void coproductStockMatrixPreservesExactCountsAtBothLongBoundaries() {
        var root = new ExactBranchBytecode<>("root", List.of(slot("a", 16), slot("b", 16)),
                Map.of("goal", BigInteger.ONE));
        var coproduct = new ExactBranchBytecode<>("coproduct", List.of(slot("ore", 1)),
                Map.of("a", BigInteger.ONE, "b", BigInteger.ONE));
        for (var amount : List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), BigInteger.TEN.pow(64))) {
            var demand = amount.multiply(BigInteger.valueOf(16));
            for (var stock : List.of(BigInteger.ZERO, demand.divide(BigInteger.TWO), demand)) {
                var result = new ExactBranchVM<>("goal", key -> switch (key) {
                    case "goal" -> List.of(root);
                    case "a", "b" -> List.of(coproduct);
                    default -> List.of();
                }, key -> false, key -> key.equals("ore") ? stock : BigInteger.ZERO,
                        key -> 1, ignored -> {}, BigInteger.ONE.shiftLeft(4096)).plan(amount, false);
                assertEquals(amount, result.requested());
                assertEquals(Map.of("root", amount, "coproduct", demand), result.crafts());
                assertEquals(stock, result.used().getOrDefault("ore", BigInteger.ZERO));
                assertEquals(demand.subtract(stock), result.missing().getOrDefault("ore", BigInteger.ZERO));
                assertTrue(result.emitted().isEmpty());
            }
        }
    }

    private static ExactBranchBytecode.InputSlot<String> slot(String key, long count) {
        return new ExactBranchBytecode.InputSlot<>(List.of(new ExactBranchBytecode.Stack<>(key,
                BigInteger.valueOf(count))), BigInteger.ONE);
    }

    @Test void instructionCoefficientsAndOutputsCanExceedLong() {
        var wide = BigInteger.ONE.shiftLeft(80).add(BigInteger.valueOf(17));
        var input = new ExactBranchBytecode.InputSlot<>(
                List.of(new ExactBranchBytecode.Stack<>("ore", wide)), BigInteger.ONE);
        var code = new ExactBranchBytecode<>("wide", List.of(input), Map.of("out", wide, "byproduct", wide));
        var request = wide.multiply(BigInteger.valueOf(3)).subtract(BigInteger.ONE);
        var stock = wide.multiply(BigInteger.valueOf(3));
        var result = new ExactBranchVM<>("out", k -> k.equals("out") ? List.of(code) : List.of(),
                k -> false, k -> k.equals("ore") ? stock : BigInteger.ZERO,
                k -> 1, ignored -> {}, BigInteger.ONE.shiftLeft(4096)).plan(request, false);
        assertEquals(Map.of("wide", BigInteger.valueOf(3)), result.crafts());
        assertEquals(Map.of("ore", stock), result.used());
        assertTrue(result.missing().isEmpty());
        assertEquals(request, result.requested());
        assertTrue(result.instructions() > 0);
    }

    @Test void wideTemplateUsesExactDivisibility() {
        var quantum = BigInteger.ONE.shiftLeft(70);
        var code = new ExactBranchBytecode<>("template", List.of(new ExactBranchBytecode.InputSlot<>(
                List.of(new ExactBranchBytecode.Stack<>("raw", quantum)), quantum)), Map.of("out", BigInteger.ONE));
        ExactBranchInputRules<String> rules = new ExactBranchInputRules<>() {
            public Input<String> input(ExactBranchBytecode<String> pattern, int slot) {
                return new Input<>(List.of(new Template<>("raw", quantum)), "raw", false,
                        k -> new Observation<>(true, null));
            }
            public List<String> storedFuzzyKeys(String key) { return List.of(key); }
            public KeyIndex<String> newIndex() {
                return new KeyIndex<>() {
                    private final java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
                    public void add(String key) { keys.add(key); }
                    public List<String> fuzzy(String key) { return keys.contains(key) ? List.of(key) : List.of(); }
                    public List<String> keys() { return List.copyOf(keys); }
                };
            }
        };
        var result = new ExactBranchVM<>("out", k -> k.equals("out") ? List.of(code) : List.of(),
                k -> false, k -> k.equals("raw") ? quantum.multiply(BigInteger.TWO).subtract(BigInteger.ONE) : BigInteger.ZERO,
                k -> 1, ignored -> {}, BigInteger.ONE.shiftLeft(4096), rules).plan(BigInteger.TWO, false);
        assertEquals(Map.of("raw", quantum), result.used());
        assertEquals(Map.of("raw", quantum), result.missing());
    }

    @Test void countLimitRejectsInsteadOfClampingAndCancellationPropagates() {
        var code = new ExactBranchBytecode<>("overflow", List.of(new ExactBranchBytecode.InputSlot<>(
                List.of(new ExactBranchBytecode.Stack<>("raw", BigInteger.valueOf(90))), BigInteger.ONE)),
                Map.of("out", BigInteger.ONE));
        assertThrows(IllegalArgumentException.class, () -> new ExactBranchVM<>("out",
                k -> k.equals("out") ? List.of(code) : List.of(), k -> false, k -> BigInteger.ZERO,
                k -> 1, ignored -> {}, BigInteger.valueOf(100)).plan(BigInteger.TWO, false));
        var cancellation = new IllegalStateException("cancel");
        assertSame(cancellation, assertThrows(IllegalStateException.class, () -> new ExactBranchVM<>("out",
                k -> List.of(code), k -> false, k -> BigInteger.ZERO, k -> 1,
                ignored -> { throw cancellation; }, BigInteger.TEN).plan(BigInteger.ONE, false)));
    }
}
