package com.syaru.ae2vm.exact.graph;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reproducible arithmetic benchmark, explicitly not a production-server acceptance test. */
public final class GraphPlannerBenchmark {
    public static void main(String[] args) throws Exception {
        var quantities=List.of(BigInteger.ONE,BigInteger.TWO,BigInteger.TEN,BigInteger.valueOf(50),
                BigInteger.valueOf(100),BigInteger.valueOf(1000),BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),new BigInteger("9220000000000000000"),
                BigInteger.TEN.pow(1024).subtract(BigInteger.ONE));
        var rows=new ArrayList<String>();
        rows.add("fixture,stock,quantity_bits,quantity,elapsed_ns,simulation,visits,schedule_blocks");
        var planner=new ExactGraphPlanner();
        for (var name:List.of("shared-dag-80","coupled-catalyst","shared-80-one-choice")) {
            var graph=name.equals("shared-dag-80")?shared():name.equals("coupled-catalyst")?coupled():sharedChoice();
            for (var quantity:quantities) {
                var empty=planner.plan(graph,0,quantity,Map.of(),Set.of(),false);
                for (var mode:List.of("none","half","full")) {
                    var stock=new LinkedHashMap<Integer,BigInteger>();
                    if (!mode.equals("none")) empty.missing().forEach((key,count)->stock.put(key,
                            mode.equals("half")?count.shiftRight(1):count));
                    long started=System.nanoTime();
                    var result=planner.plan(graph,0,quantity,stock,Set.of(),false);
                    long elapsed=System.nanoTime()-started;
                    if (result.simulation() == mode.equals("full"))
                        throw new AssertionError("wrong stock sufficiency: " + name + "/" + mode);
                    rows.add(String.join(",",name,mode,Integer.toString(quantity.bitLength()),quantity.toString(),
                            Long.toString(elapsed),Boolean.toString(result.simulation()),Long.toString(result.graphVisits()),
                            Integer.toString(result.schedule().size())));
                    System.out.printf("%s stock=%s bits=%d %.3fms missing=%s visits=%d blocks=%d%n",
                            name,mode,quantity.bitLength(),elapsed/1_000_000.0,result.simulation(),result.graphVisits(),result.schedule().size());
                }
            }
        }
        var output=Path.of(args[0]); Files.createDirectories(output.getParent()); Files.write(output,rows);
    }
    private static RecipeGraph shared() {
        var recipes=new ArrayList<RecipeGraph.Recipe>();
        for(int i=0;i<80;i++) recipes.add(recipe("r"+i,i,1,input(i+1,2),input(i+2,3)));
        return new RecipeGraph(82,recipes);
    }
    private static RecipeGraph coupled() {
        return new RecipeGraph(7,List.of(recipe("goal",0,1,input(1,16),input(2,16)),
                recipe("a",1,1,input(3,2),new RecipeGraph.Slot(1,new RecipeGraph.Option(6,BigInteger.ONE,6))),
                recipe("b",2,1,input(3,3)),recipe("wire-iron",3,4,input(4,3)),recipe("wire-copper",3,3,input(5,2))));
    }
    private static RecipeGraph sharedChoice() {
        var fixed=shared(); var recipes=new ArrayList<>(fixed.recipes());
        recipes.add(recipe("alternative",79,2,input(81,4)));
        return new RecipeGraph(fixed.keyCount(),recipes);
    }
    private static RecipeGraph.Slot input(int key,long amount) { return new RecipeGraph.Slot(1,new RecipeGraph.Option(key,amount)); }
    private static RecipeGraph.Recipe recipe(String name,int output,long amount,RecipeGraph.Slot... inputs) {
        return new RecipeGraph.Recipe(name,List.of(inputs),Map.of(output,BigInteger.valueOf(amount)));
    }
}
