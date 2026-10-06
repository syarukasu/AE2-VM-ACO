package com.ae2vm.addon.nativeengine.mixin;

import appeng.crafting.pattern.AECraftingPattern;
import com.ae2vm.addon.nativeengine.NativeVmRecipeView;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = AECraftingPattern.class, remap = false)
public abstract class NativeCraftingRecipeMixin implements NativeVmRecipeView {
    @Shadow @Final private CraftingRecipe recipe;

    @Override public CraftingRecipe ae2vm$boundRecipe() { return recipe; }
}
