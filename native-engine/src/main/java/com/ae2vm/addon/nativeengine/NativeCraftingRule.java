package com.ae2vm.addon.nativeengine;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.crafting.pattern.AECraftingPattern;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/** #218: immutable vanilla matching data; no recipe, item or world callbacks on the worker. */
final class NativeCraftingRule {
    record Decision(boolean accepted, AEKey remainder) { }
    private static final Decision REJECTED = new Decision(false, null);
    private static final Field RECIPE = recipeField();
    private final List<Set<Item>> ingredients;
    private final Item[] encoded;
    private final int width, height, slot;
    private final AEKey primary;
    private final Map<Item, AEKey> remainders;
    private final Map<Item, Boolean> matches = new IdentityHashMap<>();

    private NativeCraftingRule(List<Set<Item>> ingredients, Item[] encoded, int width, int height,
            int slot, AEKey primary, Map<Item, AEKey> remainders) {
        this.ingredients = ingredients;
        this.encoded = encoded;
        this.width = width;
        this.height = height;
        this.slot = slot;
        this.primary = primary;
        this.remainders = remainders;
    }

    static NativeCraftingRule[] capture(AECraftingPattern pattern, Level level) {
        if (!pattern.canSubstitute() || RECIPE == null) return null;
        try {
            var recipe = (CraftingRecipe) RECIPE.get(pattern);
            if (recipe.getClass() != ShapedRecipe.class && recipe.getClass() != ShapelessRecipe.class) return null;
            var result = recipe.getResultItem(level.registryAccess());
            var output = pattern.getPrimaryOutput();
            if (!Objects.equals(AEItemKey.of(result), output.what()) || result.getCount() != output.amount()) return null;
            var ingredients = new ArrayList<Set<Item>>();
            var remainders = new IdentityHashMap<Item, AEKey>();
            for (var ingredient : recipe.getIngredients()) {
                if (ingredient.getClass() != Ingredient.class) return null;
                var items = Collections.newSetFromMap(new IdentityHashMap<Item, Boolean>());
                for (var stack : ingredient.getItems()) {
                    var item = stack.getItem();
                    items.add(item);
                    // Default Item has a fixed container, independent of NBT. Tools/custom items do not.
                    if (item.getClass() == Item.class) {
                        var returned = item.getCraftingRemainingItem();
                        remainders.put(item, returned == null ? null : AEItemKey.of(returned));
                    }
                }
                ingredients.add(Collections.unmodifiableSet(items));
            }
            var sparse = pattern.getSparseInputs();
            var encoded = new Item[9];
            for (int i = 0; i < encoded.length; i++) {
                if (sparse[i] == null) continue;
                if (!(sparse[i].what() instanceof AEItemKey key)) return null;
                encoded[i] = key.getItem();
            }
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 0;
            int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : 0;
            var inputs = pattern.getInputs();
            var rules = new NativeCraftingRule[inputs.length];
            for (int index = 0; index < inputs.length; index++) {
                var primary = inputs[index].getPossibleInputs()[0].what();
                for (int i = 0; i < sparse.length; i++) {
                    if (sparse[i] != null && sparse[i].what().equals(primary)) {
                        rules[index] = new NativeCraftingRule(List.copyOf(ingredients), encoded, width, height,
                                i, primary, remainders);
                        break;
                    }
                }
            }
            return rules;
        } catch (IllegalAccessException unavailable) {
            return null;
        }
    }

    boolean covers(AEKey key) {
        return !(key instanceof AEItemKey item) || remainders.containsKey(item.getItem());
    }

    Decision evaluate(AEKey key) {
        if (!(key instanceof AEItemKey item)) return REJECTED;
        boolean accepted = primary.equals(key) || matches.computeIfAbsent(item.getItem(), this::matches);
        if (!accepted) return REJECTED;
        if (!remainders.containsKey(item.getItem())) return null;
        return new Decision(true, remainders.get(item.getItem()));
    }

    private boolean matches(Item replacement) {
        if (width == 0) return shapeless(0, 0, replacement, new boolean[1 << ingredients.size()]);
        for (int y = 0; y <= 3 - height; y++) for (int x = 0; x <= 3 - width; x++)
            if (shaped(x, y, false, replacement) || shaped(x, y, true, replacement)) return true;
        return false;
    }

    private boolean shaped(int left, int top, boolean mirror, Item replacement) {
        for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
            int column = x - left, row = y - top;
            Set<Item> expected = column >= 0 && row >= 0 && column < width && row < height
                    ? ingredients.get(row * width + (mirror ? width - column - 1 : column)) : Set.of();
            var actual = y * 3 + x == slot ? replacement : encoded[y * 3 + x];
            if (actual == null ? !expected.isEmpty() : !expected.contains(actual)) return false;
        }
        return true;
    }

    private boolean shapeless(int index, int used, Item replacement, boolean[] rejected) {
        while (index < encoded.length && index != slot && encoded[index] == null) index++;
        if (index == encoded.length) return Integer.bitCount(used) == ingredients.size();
        if (rejected[used]) return false;
        var actual = index == slot ? replacement : encoded[index];
        for (int i = 0; i < ingredients.size(); i++) {
            int bit = 1 << i;
            if ((used & bit) == 0 && ingredients.get(i).contains(actual)
                    && shapeless(index + 1, used | bit, replacement, rejected)) return true;
        }
        rejected[used] = true;
        return false;
    }

    private static Field recipeField() {
        try {
            var field = AECraftingPattern.class.getDeclaredField("recipe");
            return field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return null;
        }
    }
}
