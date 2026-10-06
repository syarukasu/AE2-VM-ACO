package com.syaru.ae2vm.exact.graph;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** A selected plan and its executable compressed order, never physical inventory. */
public record GraphPlan(int root, BigInteger requested, Map<String, BigInteger> crafts,
        Map<Integer, BigInteger> used, Map<Integer, BigInteger> missing,
        Map<Integer, BigInteger> emitted, Map<Integer, BigInteger> remaining,
        List<PlanSchedule.Step> schedule, long graphVisits, String route) {
    public GraphPlan {
        crafts = Map.copyOf(crafts); used = Map.copyOf(used); missing = Map.copyOf(missing);
        emitted = Map.copyOf(emitted); remaining = Map.copyOf(remaining); schedule = List.copyOf(schedule);
    }
    public boolean simulation() { return !missing.isEmpty(); }
}
