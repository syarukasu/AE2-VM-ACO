package com.ae2vm.addon.nativeengine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.hooks.ticking.TickHandler;
import com.ae2vm.addon.nativeengine.mixin.CraftingCalculationControl;
import java.util.concurrent.CancellationException;
import net.minecraft.world.level.Level;

/** Reuses AE2's pool and simulateFor/handlePausing/finish protocol, not its long quantity tree. */
public final class NativeVmCalculation extends CraftingCalculation {
    private final Level world;
    private final java.util.function.Function<Runnable, ICraftingPlan> calculation;

    NativeVmCalculation(Level level, IGrid grid, AEKey output, CalculationStrategy strategy,
            java.util.function.Function<Runnable, ICraftingPlan> calculation) {
        // Scheduling only: null action source skips AE2's second stock snapshot.
        // The placeholder is never executed. The VM receives the original BigInteger request.
        super(level, grid, () -> null, new GenericStack(output, 1), strategy);
        world = level;
        this.calculation = calculation;
    }

    @Override public ICraftingPlan run() {
        var control = (CraftingCalculationControl) (Object) this;
        try {
            TickHandler.instance().registerCraftingSimulation(world, this);
            checkpoint();
            return calculation.apply(this::checkpoint);
        } finally {
            control.vm$finish();
        }
    }

    private void checkpoint() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("VM calculation cancelled");
        try { ((CraftingCalculationControl) (Object) this).vm$pause(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("VM calculation cancelled");
        }
    }
}
