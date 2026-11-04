package com.example.villagewars;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/** Alle Spieleraktionen (werden vom Menü aufgerufen). */
public class Actions {
    public static final int RANGE = 120;
    public static final int GIFT = 1, SUPPORT = 2, HIRE1 = 3, HIRE3 = 4, CLAIM = 5, COLLECT = 6;

    public record Result(boolean ok, String msg) {}

    private static Result ok(String m) { return new Result(true, m); }
    private static Result fail(String m) { return new Result(false, m); }

    public static Result run(ServerPlayer p, int action) {
        SettlementData d = SettlementData.get(p.getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return fail("Kein Dorf in der Nähe.");
        return switch (action) {
            case GIFT -> gift(p, d, s);
            case SUPPORT -> support(p, d, s, 3);
            case HIRE1 -> hire(p, d, s, 1);
            case HIRE3 -> hire(p, d, s, 3);
            case CLAIM -> claim(p, d, s);
            case COLLECT -> collect(p, d, s);
            default -> fail("Unbekannte Aktion.");
        };
    }

    private static Result gift(ServerPlayer p, SettlementData d, Settlement s) {
        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return fail("Halte Eisen, Gold, Smaragde, Brot, Getreide oder Holz in der Hand.");
        int n = held.getCount();
        int points;
        if (held.is(Items.IRON_INGOT)) { s.iron += n; points = n * 3; }
        else if (held.is(Items.GOLD_INGOT)) { s.gold += n; points = n * 6; }
        else if (held.is(Items.EMERALD)) { s.gold += n * 2; points = n * 8; }
        else if (held.is(Items.BREAD)) { s.food += n * 6; points = n * 2; }
        else if (held.is(Items.WHEAT) || held.is(Items.CARROT) || held.is(Items.POTATO) || held.is(Items.BEETROOT)) { s.food += n * 3; points = n; }
        else if (held.is(ItemTags.LOGS)) { s.wood += n * 2; points = n; }
        else return fail("Das Dorf kann damit nichts anfangen.");

        held.shrink(n);
        int gain = Math.max(1, points / 10);
        d.addRep(p.getUUID(), s, gain);
        d.setDirty();
        return ok(s.name + " dankt dir! Ruf +" + gain);
    }

    private static Result support(ServerPlayer p, SettlementData d, Settlement s, int want) {
        int n = Math.min(want, Settlement.MAX_SOLDIERS - s.soldiers);
        if (n <= 0) return fail(s.name + " hat schon die maximale Armeegröße.");
        if (!take(p, Items.GOLD_INGOT, n * 2)) return fail("Du brauchst " + (n * 2) + " Goldbarren im Inventar.");
        s.soldiers += n;
        d.addRep(p.getUUID(), s, n * 3);
        d.setDirty();
        return ok(n + " Söldner verstärken " + s.name + ". Ruf +" + (n * 3));
    }

    private static Result hire(ServerPlayer p, SettlementData d, Settlement s, int n) {
        int rep = d.getRep(p.getUUID(), s);
        if (rep < 20) return fail(s.name + " vertraut dir noch nicht (Ruf mind. 20, du hast " + rep + ").");
        if (s.soldiers - n < 2) return fail(s.name + " kann so viele Soldaten nicht entbehren.");
        if (!take(p, Items.EMERALD, n * 3)) return fail("Du brauchst " + (n * 3) + " Smaragde im Inventar.");

        s.soldiers -= n;
        int done = 0;
        boolean allOwned = true;
        for (int i = 0; i < n; i++) {
            Mob mob = spawnMercenary(p, s);
            if (mob == null) continue;
            done++;
            if (!assignOwner(mob, p)) allOwned = false;
        }
        if (done < n) {
            s.soldiers += n - done;
            give(p, Items.EMERALD, (n - done) * 3);
        }
        d.setDirty();
        if (done == 0) return fail("Söldner konnten nicht erscheinen (ist Recruits geladen?). Smaragde zurück.");
        return ok(done + " Söldner aus " + s.name + " stehen bei dir."
                + (allOwned ? "" : " Sprich sie per Rechtsklick an, um sie zu übernehmen."));
    }

    private static Result claim(ServerPlayer p, SettlementData d, Settlement s) {
        if (s.owner != null) return fail(s.name + " gehört schon " + s.ownerName + ".");
        boolean conquered = s.soldiers <= 0;
        int rep = d.getRep(p.getUUID(), s);
        if (!conquered && rep < 50) return fail("Du brauchst Ruf 50 (du hast " + rep + "), oder das Dorf muss ohne Soldaten sein.");
        int cost = conquered ? 8 : 24;
        if (!take(p, Items.GOLD_INGOT, cost)) return fail("Du brauchst " + cost + " Goldbarren im Inventar.");

        s.owner = p.getUUID();
        s.ownerName = p.getGameProfile().getName();
        d.setRep(p.getUUID(), s, 100);
        d.setDirty();
        p.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                        .append(Component.literal(s.ownerName + " übernimmt " + s.name + "!").withStyle(ChatFormatting.GRAY)), false);
        return ok("Du hast " + s.name + " übernommen!");
    }

    private static Result collect(ServerPlayer p, SettlementData d, Settlement s) {
        if (s.owner == null || !s.owner.equals(p.getUUID())) return fail(s.name + " gehört nicht dir.");
        int g = (int) (s.gold * 0.5);
        int i = (int) (s.iron * 0.4);
        int b = Math.min(64, (int) (s.food * 0.3) / 4);
        if (g + i + b == 0) return fail("Im Lager ist noch nichts Abholbares.");
        s.gold -= g;
        s.iron -= i;
        s.food -= b * 4;
        give(p, Items.GOLD_INGOT, g);
        give(p, Items.IRON_INGOT, i);
        give(p, Items.BREAD, b);
        d.setDirty();
        return ok("Abgeholt: " + g + " Gold, " + i + " Eisen, " + b + " Brot.");
    }

    // ------------------------------------------------------------------ Helfer

    private static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static boolean take(ServerPlayer p, Item item, int amount) {
        if (count(p, item) < amount) return false;
        int left = amount;
        for (ItemStack s : p.getInventory().items) {
            if (left <= 0) break;
            if (s.is(item)) {
                int t = Math.min(left, s.getCount());
                s.shrink(t);
                left -= t;
            }
        }
        p.getInventory().setChanged();
        return true;
    }

    private static void give(ServerPlayer p, Item item, int amount) {
        while (amount > 0) {
            int n = Math.min(amount, 64);
            ItemStack st = new ItemStack(item, n);
            if (!p.getInventory().add(st)) p.drop(st, false);
            amount -= n;
        }
    }

    private static Mob spawnMercenary(ServerPlayer p, Settlement village) {
        ResourceLocation rl = new ResourceLocation("recruits", "recruit");
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(rl)) return null;
        ServerLevel level = p.serverLevel();
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
        Entity ent = type.create(level);
        if (!(ent instanceof Mob mob)) return null;
        double x = p.getX() + (level.random.nextDouble() - 0.5) * 3;
        double z = p.getZ() + (level.random.nextDouble() - 0.5) * 3;
        mob.moveTo(x, p.getY(), z, p.getYRot(), 0f);
        mob.setPersistenceRequired();
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.MOB_SUMMONED, null, null);
        Equipment.apply(mob, village, level.random, false);
        level.addFreshEntity(mob);
        return mob;
    }

    /** Setzt den Besitzer über die Recruits-Klasse (per Reflection). */
    private static boolean assignOwner(Mob mob, ServerPlayer p) {
        try {
            mob.getClass().getMethod("setOwnerUUID", Optional.class).invoke(mob, Optional.of(p.getUUID()));
        } catch (Throwable t) {
            return false;
        }
        try {
            mob.getClass().getMethod("setFollowState", int.class).invoke(mob, 1);
        } catch (Throwable ignored) {
        }
        return true;
    }
}
