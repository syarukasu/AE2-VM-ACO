package com.ae2vm.addon.nativeengine;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingCalculation;
import appeng.hooks.ticking.TickHandler;
import net.minecraft.world.level.Level;

/** Uses AE2's pool and tick hook; only live reads wait for an exclusive interval. */
public final class NativeVmCalculation extends CraftingCalculation {
    private final Level world;
    private final NativeVmAccess access = new NativeVmAccess();
    private final java.util.function.Function<NativeVmAccess, ICraftingPlan> calculation;

    NativeVmCalculation(Level level, IGrid grid, AEKey output, CalculationStrategy strategy,
            java.util.function.Function<NativeVmAccess, ICraftingPlan> calculation) {
        // Scheduling only: null action source skips AE2's second stock snapshot.
        // The placeholder is never executed. The VM receives the original BigInteger request.
        super(level, grid, () -> null, new GenericStack(output, 1), strategy);
        world = level;
        this.calculation = calculation;
    }

    @Override public ICraftingPlan run() {
        try {
            TickHandler.instance().registerCraftingSimulation(world, this);
            access.checkpoint();
            return calculation.apply(access);
        } finally {
            access.finish();
        }
    }

    @Override public boolean simulateFor(int micros) { return access.simulateFor(micros); }
}
