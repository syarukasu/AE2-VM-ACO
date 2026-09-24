package com.ae2vm.addon.vm;

import appeng.api.crafting.IPatternDetails;
import java.math.BigInteger;
import java.util.Map;

public record VmCraftingPlan(VmStack finalOutput, BigInteger bytes, boolean simulation, boolean multiplePaths,
        VmKeyCounter usedItems, VmKeyCounter emittedItems, VmKeyCounter missingItems,
        Map<IPatternDetails, BigInteger> patternTimes) {
    public VmCraftingPlan {
        usedItems = usedItems.frozen();
        emittedItems = emittedItems.frozen();
        missingItems = missingItems.frozen();
        patternTimes = Map.copyOf(patternTimes);
    }
}
