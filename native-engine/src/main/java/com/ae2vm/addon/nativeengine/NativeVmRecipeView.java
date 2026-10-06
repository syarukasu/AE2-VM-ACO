package com.ae2vm.addon.nativeengine;

import net.minecraft.world.item.crafting.CraftingRecipe;

/** #218: the bound recipe, not a fresh lookup that might refer to a later reload. */
public interface NativeVmRecipeView {
    CraftingRecipe ae2vm$boundRecipe();
}
