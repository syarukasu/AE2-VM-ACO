package com.ae2vm.addon.nativeengine.mixin;

import appeng.crafting.CraftingCalculation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = CraftingCalculation.class, remap = false)
public interface CraftingCalculationControl {
    @Invoker("handlePausing") void vm$pause() throws InterruptedException;
    @Invoker("finish") void vm$finish();
}
