package com.ae2vm.addon.nativeengine;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.syaru.ae2vm.exact.graph.GraphPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/** Selected input/order evidence travels with the plan; this is not a stock/plan cache. */
public final class NativePlanSelections {
    private static final Map<ICraftingPlan, Selection> PLANS = new WeakHashMap<>();
    private NativePlanSelections() { }
    public record Selection(GraphPlan plan, List<AEKey> keys, Map<String, IPatternDetails> bindings) {
        public Selection { keys = List.copyOf(keys); bindings = Map.copyOf(bindings); }
    }
    static synchronized void attach(ICraftingPlan facade, NativeOrderSnapshot snapshot, GraphPlan result) {
        PLANS.put(facade,new Selection(result,snapshot.keys(),snapshot.bindings()));
    }
    public static synchronized Optional<Selection> find(ICraftingPlan facade) { return Optional.ofNullable(PLANS.get(facade)); }
}
