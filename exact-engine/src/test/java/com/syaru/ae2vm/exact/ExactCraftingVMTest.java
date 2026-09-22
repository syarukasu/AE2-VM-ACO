package com.syaru.ae2vm.exact;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ExactCraftingVMTest {
    @Test void recipeCoefficientsAndStockCanExceedLongIndependently() {
        BigInteger wide = BigInteger.TEN.pow(64).add(BigInteger.ONE);
        var code = ExactBytecode.compile(List.of(
                new ExactBytecode.Node(wide, List.of(new ExactBytecode.Input(1, wide.add(BigInteger.TWO)))),
                new ExactBytecode.Node(0, List.of())));
        var result = ExactCraftingVM.execute(code, 0, wide.add(BigInteger.ONE),
                new BigInteger[] {BigInteger.ZERO, wide}, MAX, i -> {});
        assertEquals(BigInteger.TWO, result.executionsAt(0));
        assertEquals(wide, result.usedAt(1));
        assertEquals(wide.add(BigInteger.valueOf(4)), result.missingAt(1));
        var noInputNeeded = ExactCraftingVM.execute(code, 0, BigInteger.ONE,
                new BigInteger[] {BigInteger.ZERO, wide.add(BigInteger.TWO)}, MAX, i -> {});
        assertEquals(BigInteger.ONE, noInputNeeded.executionsAt(0));
        assertEquals(BigInteger.ZERO, noInputNeeded.missingAt(1));
    }
    private static final BigInteger ZERO = BigInteger.ZERO, ONE = BigInteger.ONE;
    private static final BigInteger MAX = BigInteger.TEN.pow(16384).subtract(ONE);
    private static ExactBytecode tree() {
        return ExactBytecode.compile(List.of(
            new ExactBytecode.Node(1, List.of(new ExactBytecode.Input(1,16),
                new ExactBytecode.Input(2,16), new ExactBytecode.Input(3,16))),
            new ExactBytecode.Node(1, List.of(new ExactBytecode.Input(4,2))),
            new ExactBytecode.Node(0,List.of()), new ExactBytecode.Node(0,List.of()),
            new ExactBytecode.Node(0,List.of())));
    }
    private static BigInteger[] empty(int n) {
        BigInteger[] a = new BigInteger[n]; Arrays.fill(a, ZERO); return a;
    }
    @Test void exactQuantityStockMatrix() {
        var code = tree();
        for (BigInteger n : List.of(ONE, BigInteger.TWO, BigInteger.valueOf(Long.MAX_VALUE),
                ONE.shiftLeft(63), BigInteger.TEN.pow(64))) {
            BigInteger intermediate = n.multiply(BigInteger.valueOf(16));
            for (int stockCase = 0; stockCase < 3; stockCase++) {
                BigInteger[] stock = empty(5);
                BigInteger supplied = stockCase == 0 ? ZERO
                    : stockCase == 1 ? intermediate.divide(BigInteger.TWO) : intermediate;
                stock[2]=supplied; stock[3]=supplied; stock[4]=supplied.multiply(BigInteger.TWO);
                BigInteger[] before = stock.clone();
                var r = ExactCraftingVM.execute(code,0,n,stock,MAX,i -> {});
                assertEquals(n,r.executionsAt(0));
                assertEquals(intermediate,r.executionsAt(1));
                assertEquals(supplied,r.usedAt(2));
                assertEquals(intermediate.subtract(supplied),r.missingAt(2));
                assertEquals(intermediate.subtract(supplied),r.missingAt(3));
                assertEquals(intermediate.subtract(supplied).multiply(BigInteger.TWO),r.missingAt(4));
                assertArrayEquals(before,stock);
            }
        }
    }
    @Test void partialIntermediateStockAndRounding() {
        var code=ExactBytecode.compile(List.of(
            new ExactBytecode.Node(3,List.of(new ExactBytecode.Input(1,2))),
            new ExactBytecode.Node(0,List.of())));
        var r=ExactCraftingVM.execute(code,0,BigInteger.valueOf(8),
                new BigInteger[]{BigInteger.TWO,BigInteger.valueOf(3)},MAX,i -> {});
        assertEquals(BigInteger.TWO,r.executionsAt(0));
        assertEquals(BigInteger.TWO,r.usedAt(0));
        assertEquals(ONE,r.missingAt(1));
    }
    @Test void finishedStockDoesNotManufactureMore() {
        var r=ExactCraftingVM.execute(tree(),0,ONE,
                new BigInteger[]{ONE,ZERO,ZERO,ZERO,ZERO},MAX,i -> {});
        assertEquals(ONE,r.usedAt(0)); assertEquals(ZERO,r.executionsAt(0));
        assertEquals(ZERO,r.missingAt(4));
    }
    @Test void cancellationAndLimitsDoNotMutateInput() {
        var stock=empty(5);
        assertThrows(IllegalStateException.class,() -> ExactCraftingVM.execute(tree(),0,ONE,
                stock,MAX,i -> {throw new IllegalStateException("cancel");}));
        assertThrows(IllegalArgumentException.class,() -> ExactCraftingVM.execute(tree(),0,
                BigInteger.valueOf(Long.MAX_VALUE),stock,BigInteger.valueOf(Long.MAX_VALUE),i -> {}));
        assertArrayEquals(empty(5),stock);
    }
    @Test void sharedProgramHasNoCrossRequestStockCache() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var p=tree();
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<BigInteger>>();
            for(int i=0;i<40;i++){
                BigInteger n=BigInteger.valueOf(i+1);
                tasks.add(pool.submit(() -> ExactCraftingVM.execute(p,0,n,empty(5),MAX,x -> {}).missingAt(4)));
            }
            for(int i=0;i<40;i++)assertEquals(BigInteger.valueOf((i+1)*32L),tasks.get(i).get());
        } finally {pool.shutdownNow();}
    }
    @Test void malformedGraphIsNotCompiled() {
        assertThrows(IllegalArgumentException.class,() -> ExactBytecode.compile(List.of(
            new ExactBytecode.Node(1,List.of(new ExactBytecode.Input(0,1))))));
    }
}
