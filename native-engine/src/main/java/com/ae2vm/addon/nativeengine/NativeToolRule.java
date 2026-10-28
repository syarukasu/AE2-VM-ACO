package com.ae2vm.addon.nativeengine;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import java.util.Objects;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.fml.ModList;

/** #236: an audited ordinary-tool transition, captured in AE2's access window. */
final class NativeToolRule {
    private final AEItemKey base;
    private final int step, maximum;
    private final boolean breakAtLimit;

    private NativeToolRule(AEItemKey base, int step, int maximum, boolean breakAtLimit) {
        this.base = base;
        this.step = step;
        this.maximum = maximum;
        this.breakAtLimit = breakAtLimit;
    }

    static boolean auditedVersion() {
        var mods = ModList.get();
        return mods != null && mods.getModContainerById("gtceu")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("7.5.3")).orElse(false);
    }

    static NativeToolRule capture(AEKey input, AEKey returned) {
        if (ForgeHooks.getCraftingPlayer() != null
                || !(input instanceof AEItemKey key)
                || !auditedItem(key)) return null;
        if (oreHammer(key.getItem())) {
            if (!oreHammerVersion() || returned != null && !(returned instanceof AEItemKey)) return null;
            var stack = key.toStack();
            if (!key.equals(AEItemKey.of(stack))) return null;
            return fromObservedAtLimit(key, (AEItemKey) returned, stack.getMaxDamage());
        }
        if (!auditedVersion() || !(returned instanceof AEItemKey remainder)) return null;
        try {
            var item = key.getItem();
            var type = item.getClass();
            if ((boolean) type.getMethod("isElectric").invoke(item)) return null;
            var definition = type.getMethod("getToolStats").invoke(item);
            if (!definition.getClass().getName().equals("com.gregtechceu.gtceu.api.item.tool.ToolDefinitionBuilder$1")) return null;
            var contract = Class.forName("com.gregtechceu.gtceu.api.item.tool.IGTToolDefinition", false, type.getClassLoader());
            if (!((net.minecraft.world.item.ItemStack) contract.getMethod("getBrokenStack").invoke(definition)).isEmpty()) return null;
            var stack = key.toStack();
            int step = (int) contract.getMethod("getToolDamagePerCraft", net.minecraft.world.item.ItemStack.class)
                    .invoke(definition, stack);
            var tag = stack.getTag();
            if (tag == null || tag.getBoolean("Unbreakable") || tag.getBoolean("DisallowContainerItem")
                    || !tag.contains("GT.Tool", Tag.TAG_COMPOUND)) return null;
            var tool = tag.getCompound("GT.Tool");
            if (tool.contains("Damage") || !tool.contains("MaxDamage", Tag.TAG_INT)
                    || !tool.contains("DefaultEnchantments", Tag.TAG_LIST)) return null;
            if (stack.getEnchantmentLevel(Enchantments.UNBREAKING) != 0) return null;
            int maximum = stack.getMaxDamage();
            // Capability-bearing or lazily migrated states are not this immutable rule.
            if (!key.equals(AEItemKey.of(stack)) || !key.equals(AEItemKey.of(item, key.getTag()))) return null;
            return fromObserved(key, remainder, step, maximum);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean auditedItem(AEItemKey key) {
        var name = key.getItem().getClass().getName();
        return name.equals("com.gregtechceu.gtceu.api.item.tool.GTToolItem")
                || name.equals("com.gregtechceu.gtceu.api.item.tool.GTSwordItem") || oreHammer(key.getItem());
    }

    private static boolean oreHammer(Item item) {
        return item.getClass().getName().equals("net.allthemods.alltheores.items.OreHammer");
    }

    private static boolean oreHammerVersion() {
        var mods = ModList.get();
        return mods != null && mods.getModContainerById("alltheores")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("2.2.4")).orElse(false);
    }

    static AEKey[] returnFamilyUpperBound(Item item) {
        if (oreHammer(item)) return oreHammerVersion() ? new AEKey[]{AEItemKey.of(item)} : null;
        if (!auditedVersion() || !auditedItem(AEItemKey.of(item))) return null;
        try {
            var type = item.getClass();
            var definition = type.getMethod("getToolStats").invoke(item);
            if (!definition.getClass().getName().equals("com.gregtechceu.gtceu.api.item.tool.ToolDefinitionBuilder$1")) return null;
            var contract = Class.forName("com.gregtechceu.gtceu.api.item.tool.IGTToolDefinition", false, type.getClassLoader());
            var broken = (net.minecraft.world.item.ItemStack) contract.getMethod("getBrokenStack").invoke(definition);
            // #236: a complete family bound is not a finite-use or deterministic-wear promise.
            return broken.isEmpty() ? new AEKey[]{AEItemKey.of(item)}
                    : new AEKey[]{AEItemKey.of(item), AEItemKey.of(broken)};
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static NativeToolRule fromObserved(AEItemKey input, AEItemKey returned, int step, int maximum) {
        var base = base(input);
        if (base == null || step <= 0 || maximum <= 0) return null;
        var rule = new NativeToolRule(base, step, maximum, false);
        return returned != null && returned.equals(rule.evaluate(input)) ? rule : null;
    }

    static NativeToolRule fromObservedAtLimit(AEItemKey input, AEItemKey returned, int maximum) {
        var base = base(input);
        if (base == null || maximum <= 0) return null;
        int damage = input.getTag().getInt("Damage");
        if (damage < 0 || damage >= maximum) return null;
        var rule = new NativeToolRule(base, 1, maximum, true);
        return Objects.equals(returned, rule.evaluate(input)) ? rule : null;
    }

    AEItemKey base() { return base; }

    com.ae2vm.addon.compiler.PatternCompiler.FiniteUse finiteUse(AEItemKey key) {
        if (!base.equals(base(key))) return null;
        int damage = key.getTag().getInt("Damage");
        if (damage < 0 || damage > maximum || breakAtLimit && damage == maximum) return null;
        var uses = java.math.BigInteger.valueOf(((long) maximum - damage - (breakAtLimit ? 1 : 0)) / step + 1);
        return new com.ae2vm.addon.compiler.PatternCompiler.FiniteUse() {
            public java.math.BigInteger uses() { return uses; }
            public AEKey after(java.math.BigInteger applications) {
                if (applications.signum() <= 0 || applications.compareTo(uses) > 0) throw new IllegalArgumentException();
                if (applications.equals(uses)) return null;
                var tag = key.copyTag();
                tag.putInt("Damage", java.math.BigInteger.valueOf(damage)
                        .add(applications.multiply(java.math.BigInteger.valueOf(step))).intValueExact());
                return AEItemKey.of(key.getItem(), tag);
            }
        };
    }

    AEItemKey evaluate(AEItemKey key) {
        if (!base.equals(base(key))) return null;
        int damage = key.getTag().getInt("Damage");
        long next = (long) damage + step;
        if (damage < 0 || next > maximum || breakAtLimit && next == maximum || next > Integer.MAX_VALUE) return null;
        var tag = key.copyTag();
        tag.putInt("Damage", (int) next);
        return AEItemKey.of(key.getItem(), tag);
    }

    static AEItemKey base(AEItemKey key) {
        CompoundTag tag = key.getTag();
        if (tag == null || !tag.contains("Damage", Tag.TAG_INT)
                || !key.equals(AEItemKey.of(key.getItem(), tag))) return null;
        tag = tag.copy();
        tag.remove("Damage");
        return AEItemKey.of(key.getItem(), tag);
    }
}
