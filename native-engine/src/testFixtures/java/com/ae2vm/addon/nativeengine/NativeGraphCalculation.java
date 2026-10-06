package com.ae2vm.addon.nativeengine;

import appeng.api.stacks.AEKey;
import com.syaru.ae2vm.exact.graph.ExactGraphPlanner;
import com.syaru.ae2vm.exact.graph.GraphPlan;
import com.syaru.ae2vm.exact.graph.PlanSchedule;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adapter around the pure solver. Only the snapshot, not live AE2 services, enters arithmetic. */
final class NativeGraphCalculation {
    record Result(VmQuantities quantities, GraphPlan graphPlan, boolean wideOutputs) { }

    static Result plan(NativeOrderSnapshot input, BigInteger requested, boolean craftLess) {
        var result = new ExactGraphPlanner().plan(input.graph(),input.root(),requested,
                input.stock(),input.emitters(),craftLess);
        var charges = new LinkedHashMap<Integer,BigInteger>();
        charges.put(input.root(),result.requested());
        for (var step : result.schedule()) charge(step,BigInteger.ONE,charges);
        BigInteger operations = result.crafts().values().stream().reduce(BigInteger.ZERO,BigInteger::add);
        // Node overhead reflects the stored shared graph, not an expanded recursive tree.
        long nodes = 1;
        var pending = new LinkedHashMap<Integer,BigInteger>();
        for (var recipe : input.graph().recipes()) {
            var times = result.crafts().get(recipe.id());
            if (times == null) continue;
            nodes += recipe.inputs().size();
            recipe.outputs().forEach((key,count) -> pending.merge(key,count.multiply(times),BigInteger::add));
        }
        boolean alternatives = input.graph().recipes().stream().anyMatch(recipe ->
                recipe.inputs().stream().anyMatch(slot -> slot.options().size()>1))
                || input.graph().recipes().stream().flatMap(r -> r.outputs().keySet().stream())
                .anyMatch(key -> input.graph().producers(key).size()>1);
        var quantities = new VmQuantities(input.keys().get(input.root()),result.requested(),result.crafts(),
                keys(input,result.used()), keys(input,result.emitted()), keys(input,result.missing()),
                operations.add(BigInteger.valueOf(nodes).multiply(BigInteger.valueOf(8))), keys(input,charges),
                alternatives,result.graphVisits(),result.graphVisits(),BigInteger.ZERO);
        return new Result(quantities,result,pending.values().stream().anyMatch(count -> count.bitLength()>63));
    }

    private static void charge(PlanSchedule.Step step,BigInteger outer,Map<Integer,BigInteger> charges) {
        if (step instanceof PlanSchedule.Batch batch) {
            var count = batch.times().multiply(outer);
            batch.inputs().forEach((key,amount) -> charges.merge(key,amount.multiply(count),BigInteger::add));
            batch.returns().forEach((key,amount) -> charges.merge(key,amount.multiply(count),BigInteger::add));
        } else if (step instanceof PlanSchedule.Repeat repeat) {
            for (var child : repeat.body()) charge(child,outer.multiply(repeat.times()),charges);
        }
    }

    private static Map<AEKey,BigInteger> keys(NativeOrderSnapshot input,Map<Integer,BigInteger> indexed) {
        var result = new LinkedHashMap<AEKey,BigInteger>();
        indexed.forEach((key,amount) -> result.put(input.keys().get(key),amount));
        return result;
    }
}
