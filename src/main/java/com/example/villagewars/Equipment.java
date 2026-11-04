package com.example.villagewars;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/** Rüstet Soldaten je nach Reichtum ihres Dorfes aus. */
public class Equipment {
    public static final String[] TIER_NAMES = {"einfach", "Kette", "Eisen", "Diamant"};

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Item[][] ARMOR = {
            {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS},
            {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
    };
    private static final Item[] SWORDS = {Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD};
    /** Wahrscheinlichkeit pro Rüstungsteil. */
    private static final float[] CHANCE = {0.35f, 0.6f, 0.8f, 0.95f};

    /** 0 = ärmlich, 3 = sehr reich. Hängt von Vorräten, Bevölkerung und Armee ab. */
    public static int tier(Settlement s) {
        double wealth = s.iron + s.gold * 4 + s.population * 3 + s.soldiers * 2;
        if (wealth >= 450) return 3;
        if (wealth >= 220) return 2;
        if (wealth >= 90) return 1;
        return 0;
    }

    public static void apply(Mob mob, Settlement s, RandomSource rnd, boolean noDrops) {
        int tier = tier(s);
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(mob.getType());
        String kind = id == null ? "" : id.getPath();

        for (int i = 0; i < SLOTS.length; i++) {
            if (rnd.nextFloat() < CHANCE[tier]) {
                mob.setItemSlot(SLOTS[i], new ItemStack(ARMOR[tier][i]));
                if (noDrops) mob.setDropChance(SLOTS[i], 0f);
            }
        }

        boolean ranged = kind.equals("bowman") || kind.equals("crossbowman");
        if (!ranged) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(SWORDS[tier]));
            if (noDrops) mob.setDropChance(EquipmentSlot.MAINHAND, 0f);
            if (kind.equals("recruit") && tier >= 1 && rnd.nextFloat() < 0.5f) {
                mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
                if (noDrops) mob.setDropChance(EquipmentSlot.OFFHAND, 0f);
            }
        }
    }
}
