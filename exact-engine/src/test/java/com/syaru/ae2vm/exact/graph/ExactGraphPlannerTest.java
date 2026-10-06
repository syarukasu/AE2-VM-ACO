package com.syaru.ae2vm.exact.graph;

import static org.junit.jupiter.api.Assertions.*;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ExactGraphPlannerTest {
    private final ExactGraphPlanner planner = new ExactGraphPlanner();
    private static BigInteger n(long n) { return BigInteger.valueOf(n); }
    private static RecipeGraph.Slot in(int key, long amount) { return new RecipeGraph.Slot(1, new RecipeGraph.Option(key, amount)); }
    private static RecipeGraph.Recipe recipe(String id, int output, long amount, RecipeGraph.Slot... inputs) {
        return new RecipeGraph.Recipe(id, List.of(inputs), Map.of(output, n(amount)));
    }
    private GraphPlan plan(RecipeGraph graph, int root, BigInteger amount, Map<Integer, BigInteger> stock) {
        return planner.plan(graph, root, amount, stock, Set.of(), false);
    }

    @Test void compiledDemandOrderIsReusedAcrossConcurrentIndependentStocks() throws Exception {
        var graph = new RecipeGraph(3, List.of(recipe("goal",0,1,in(1,2)), recipe("wire",1,4,in(2,3))));
        var topology = graph.demandOrder();
        var worker = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var missing = worker.submit(() -> plan(graph, 0, n(10), Map.of()));
            var ready = worker.submit(() -> plan(graph, 0, n(10), Map.of(2,n(15))));
            assertEquals(Map.of(2,n(15)), missing.get().missing());
            assertTrue(ready.get().missing().isEmpty());
            assertSame(topology, graph.demandOrder());
        } finally { worker.shutdownNow(); }
    }

    @Test void sharedDemandRoundingAndIntermediateStockAreCountedOnce() {
        // G=16A+16B+16C; each intermediate uses2W; 3iron->4W.
        var graph = new RecipeGraph(6, List.of(recipe("goal",0,1,in(1,16),in(2,16),in(3,16)),
                recipe("a",1,1,in(4,2)), recipe("b",2,1,in(4,2)), recipe("c",3,1,in(4,2)),
                recipe("wire",4,4,in(5,3))));
        var empty = plan(graph, 0, ONE, Map.of());
        assertEquals(Map.of(5,n(72)), empty.missing());
        assertEquals(n(24), empty.crafts().get("wire"));
        var partial = plan(graph,0,ONE,Map.of(4,n(5),5,n(65)));
        assertEquals(Map.of(4,n(5),5,n(65)),partial.used());
        assertEquals(Map.of(5,n(4)), partial.missing());
        assertEquals(Map.of(4,ONE), partial.remaining());
        assertEquals(n(23), partial.crafts().get("wire"));
        assertEquals("shared-dag", partial.route());
    }

    @Test void bothOutputsNeedOnlyOneRecipeCount() {
        var graph = new RecipeGraph(4,List.of(recipe("goal",0,1,in(1,4),in(2,2)),
                new RecipeGraph.Recipe("coproduct",List.of(in(3,3)),Map.of(1,n(2),2,ONE))));
        var result = plan(graph,0,ONE,Map.of(3,n(6)));
        assertFalse(result.simulation());
        assertEquals(n(2),result.crafts().get("coproduct"));
        assertEquals(Map.of(3,n(6)),result.used());
        assertTrue(result.remaining().isEmpty());
    }

    @Test void alternativeMustNotStealStockFromTheOnlyProducerOfAnotherInput() {
        var graph = new RecipeGraph(5,List.of(recipe("goal",0,1,in(1,1),in(2,1)),
                recipe("a-iron",1,1,in(3,1)),recipe("a-copper",1,1,in(4,1)),recipe("b",2,1,in(3,1))));
        var result = plan(graph,0,ONE,Map.of(3,ONE,4,ONE));
        assertFalse(result.simulation());
        assertEquals(Map.of("goal",ONE,"a-copper",ONE,"b",ONE),result.crafts());
        assertEquals(Map.of(3,ONE,4,ONE),result.used());
    }

    @Test void earlierProducerWinsWhenItDoesNotCreateAShortage() {
        var graph=new RecipeGraph(3,List.of(recipe("preferred",0,1,in(1,2)),recipe("other",0,1,in(2,1))));
        var result=plan(graph,0,ONE,Map.of(1,n(2),2,ONE));
        assertEquals(Map.of("preferred",ONE),result.crafts());
        assertEquals(Map.of(1,n(2)),result.used());
    }

    @Test void inputAlternativesAreAllocatedWithoutCartesianProducts() {
        var graph = new RecipeGraph(3,List.of(recipe("goal",0,1,
                new RecipeGraph.Slot(3,new RecipeGraph.Option(1,1),new RecipeGraph.Option(2,2)))));
        var result = plan(graph,0,n(2),Map.of(1,n(4),2,n(4)));
        assertFalse(result.simulation());
        assertEquals(Map.of(1,n(4),2,n(4)),result.used());
        assertEquals(n(2),result.crafts().get("goal"));
        assertTrue(result.schedule().size() <= 2);
    }

    @Test void oneCatalystCanProduceMoreThanLongWithoutMultiplyingTheSeed() {
        var amount = BigInteger.TEN.pow(1024).subtract(ONE);
        var graph = new RecipeGraph(3,List.of(recipe("plate",0,1,in(1,1),
                new RecipeGraph.Slot(1,new RecipeGraph.Option(2,ONE,2)))));
        var result = assertTimeout(Duration.ofSeconds(8), () -> plan(graph,0,amount,Map.of(1,amount,2,ONE)));
        assertFalse(result.simulation());
        assertEquals(Map.of(1,amount,2,ONE),result.used());
        assertEquals(Map.of(2,ONE),result.remaining());
        assertEquals(amount,result.crafts().get("plate"));
        assertEquals(1,result.schedule().size());
        var missingSeed = plan(graph,0,n(100),Map.of(1,n(100)));
        assertEquals(Map.of(2,ONE),missingSeed.missing());
    }

    @Test void repeatedSeededCycleHasACompressedLegalOrder() {
        // A+ore -> B; B -> A+product.
        var amount = BigInteger.ONE.shiftLeft(128);
        var graph = new RecipeGraph(4,List.of(recipe("charge",2,1,in(1,1),in(3,1)),
                new RecipeGraph.Recipe("drain",List.of(in(2,1)),Map.of(1,ONE,0,ONE))));
        var result = assertTimeout(Duration.ofSeconds(8), () -> plan(graph,0,amount,Map.of(1,ONE,3,amount)));
        assertFalse(result.simulation());
        assertEquals(amount,result.crafts().get("charge"));
        assertEquals(Map.of(1,ONE,3,amount),result.used());
        assertTrue(result.schedule().size() <= 4);
        assertEquals(Map.of(1,ONE),result.remaining());
    }

    @Test void unseededCycleCannotBeReportedCraftable() {
        var graph = new RecipeGraph(3,List.of(recipe("first",1,1,in(2,1)),
                new RecipeGraph.Recipe("second",List.of(in(1,1)),Map.of(2,ONE,0,ONE))));
        var result = plan(graph,0,n(10),Map.of());
        assertTrue(result.simulation());
        assertFalse(result.missing().containsKey(0));
        assertEquals(ONE,result.missing().values().stream().reduce(ZERO,BigInteger::add));
    }

    @Test void existingFinalGoodsDoNotReplaceANewOrder() {
        var graph = new RecipeGraph(2,List.of(recipe("goal",0,1,in(1,2))));
        var result = plan(graph,0,ONE,Map.of(0,n(100),1,n(2)));
        assertEquals(Map.of("goal",ONE),result.crafts());
        assertEquals(Map.of(1,n(2)),result.used());
        var absent=plan(new RecipeGraph(1,List.of()),0,ONE,Map.of(0,n(100)));
        assertEquals(Map.of(0,ONE),absent.missing());
        assertTrue(absent.used().isEmpty());
        var emitted=planner.plan(new RecipeGraph(1,List.of()),0,ONE,Map.of(0,n(100)),Set.of(0),false);
        assertEquals(Map.of(0,ONE),emitted.emitted());
        assertTrue(emitted.used().isEmpty());
    }

    @Test void partiallyReturnedInputNeedsTheLastOperationSeedToo() {
        var graph = new RecipeGraph(2,List.of(recipe("goal",0,1,
                new RecipeGraph.Slot(1,new RecipeGraph.Option(1,n(2),1)))));
        var result=plan(graph,0,n(100),Map.of(1,n(100)));
        assertEquals(Map.of(1,ONE),result.missing());
        assertEquals(Map.of(1,n(100)),result.used());
        assertEquals(Map.of(1,ONE),result.remaining());
    }

    @Test void aCatalystDoesNotPermitFabricatingItsCraftableCompanionInput() {
        var graph=new RecipeGraph(4,List.of(recipe("goal",0,1,in(1,4),
                new RecipeGraph.Slot(1,new RecipeGraph.Option(2,ONE,2))),recipe("part",1,2,in(3,3))));
        var result=plan(graph,0,n(100),Map.of());
        assertEquals(Map.of(2,ONE,3,n(600)),result.missing());
        assertEquals(n(200),result.crafts().get("part"));
    }

    @Test void scheduleCanReorderRatherThanConsumeTheOnlyStartupMaterial() {
        var batches=List.of(new PlanSchedule.Batch("finish",Map.of(0,ONE),Map.of(3,ONE),Map.of(),ONE),
                new PlanSchedule.Batch("first",Map.of(0,ONE),Map.of(1,ONE),Map.of(),ONE),
                new PlanSchedule.Batch("second",Map.of(1,ONE),Map.of(0,ONE,2,ONE),Map.of(),ONE));
        var result=PlanSchedule.order(batches,Map.of(0,ONE));
        assertEquals(Map.of(0,ONE),result.summary().seed());
        assertEquals(Map.of(0,ONE.negate(),2,ONE,3,ONE),result.summary().delta());
        assertEquals("first",((PlanSchedule.Batch)result.steps().get(0)).recipe());
    }

    @Test void integerFeasibilityMatchesIndependentSmallInventoryEnumeration() {
        var random=new java.util.Random(218);
        for (int trial=0;trial<60;trial++) {
            int need=1+random.nextInt(2), outA=1+random.nextInt(2), outB=1+random.nextInt(2);
            int inA=1+random.nextInt(3), inB=1+random.nextInt(3), a=random.nextInt(4), b=random.nextInt(4);
            int requested=1+random.nextInt(2);
            var graph=new RecipeGraph(4,List.of(recipe("goal",0,1,in(1,need)),
                    recipe("a",1,outA,in(2,inA)),recipe("b",1,outB,in(3,inB))));
            // Independent finite enumeration, not the previous planner as an oracle.
            boolean feasible=false;
            for(int x=0;x<=a/inA;x++) for(int y=0;y<=b/inB;y++)
                feasible |= x*outA+y*outB>=need*requested;
            var result=plan(graph,0,n(requested),Map.of(2,n(a),3,n(b)));
            assertEquals(feasible,!result.simulation(),"trial "+trial);
            assertTrue(result.used().getOrDefault(2,ZERO).compareTo(n(a))<=0);
            assertTrue(result.used().getOrDefault(3,ZERO).compareTo(n(b))<=0);
            var summary=PlanSchedule.summarize(result.schedule());
            assertTrue(summary.seed().entrySet().stream().allMatch(e -> e.getValue().compareTo(
                    result.used().getOrDefault(e.getKey(),ZERO).add(result.missing().getOrDefault(e.getKey(),ZERO)))<=0));
        }
    }

    @Test void internalAmountsCanBeWideWhenTheRootIsOne() {
        var amount = BigInteger.TEN.pow(1100);
        var graph = new RecipeGraph(2,List.of(new RecipeGraph.Recipe("goal",
                List.of(new RecipeGraph.Slot(amount,List.of(new RecipeGraph.Option(1,1)))),Map.of(0,ONE))));
        assertEquals(Map.of(1,amount),plan(graph,0,ONE,Map.of()).missing());
    }

    @Test void largeSharedGraphDoesNotExpandPathsOrReplayQuantity() {
        int depth=80;
        var recipes = new ArrayList<RecipeGraph.Recipe>();
        for (int i=0;i<depth;i++) recipes.add(recipe("r"+i,i,1,in(i+1,2),in(i+2,3)));
        var graph = new RecipeGraph(depth+2,recipes);
        var amount = BigInteger.TEN.pow(1024).subtract(ONE);
        var result=assertTimeout(Duration.ofSeconds(2),()->plan(graph,0,amount,Map.of()));
        assertTrue(result.simulation());
        assertEquals(depth,result.crafts().size());
        assertEquals(depth*3,result.graphVisits());
        assertTrue(result.missing().values().stream().allMatch(n->n.compareTo(amount)>0));
    }

    @Test void cancellationDoesNotNeedAServerTick() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,()->plan(new RecipeGraph(1,List.of()),0,ONE,Map.of()));
        } finally { Thread.interrupted(); }
    }
}
