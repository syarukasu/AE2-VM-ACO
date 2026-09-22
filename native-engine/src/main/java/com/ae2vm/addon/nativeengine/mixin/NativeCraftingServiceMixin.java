package com.ae2vm.addon.nativeengine.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.nativeengine.NativeVm;
import java.math.BigInteger;
import java.util.concurrent.Future;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The fork owns the native request entry, not an ACO pattern adapter. */
@Mixin(value = CraftingService.class, remap = false, priority = 2000)
public abstract class NativeCraftingServiceMixin implements com.ae2vm.addon.nativeengine.NativeVmHook {
    @Shadow @Final private IGrid grid;

    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, require = 1)
    private void vm$calculate(Level level, ICraftingSimulationRequester requester, AEKey output,
            long amount, CalculationStrategy strategy, CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        if (level == null || level.isClientSide || amount <= 0 || output == null || requester == null) return;
        cir.setReturnValue(NativeVm.calculate(grid, level, requester, output, BigInteger.valueOf(amount), strategy));
    }

    @Inject(method = {"refreshNodeCraftingProvider", "removeNode"}, at = @At("TAIL"), require = 1)
    private void vm$changed(appeng.api.networking.IGridNode node, CallbackInfo ci) {
        if (node.getService(appeng.api.networking.crafting.ICraftingProvider.class) != null)
            NativeVm.patternsChanged(grid);
    }
}
