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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Alle Spieleraktionen (werden vom Menü aufgerufen). */
public class Actions {
    public static final int RANGE = 120;
    public static final int GIFT = 1, SUPPORT = 2, HIRE1 = 3, HIRE3 = 4, CLAIM = 5, COLLECT = 6, DECLARE = 7, PEACE = 8, FOCUS = 9;

    public record Result(boolean ok, String msg) {}

    private static Result ok(String m) { return new Result(true, m); }
    private static Result fail(String m) { return new Result(false, m); }

    public static Result run(ServerPlayer p, int action, int arg) {
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
            case DECLARE -> declare(p, d, s, arg);
            case PEACE -> peace(p, d, s, arg);
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
        boolean mine = isOwner(p, s);
        if (!mine && !take(p, Items.EMERALD, n * 3)) return fail("Du brauchst " + (n * 3) + " Smaragde im Inventar.");

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
            if (!mine) give(p, Items.EMERALD, (n - done) * 3);
        }
        d.setDirty();
        if (done == 0) return fail("Söldner konnten nicht erscheinen (ist Recruits geladen?). Smaragde zurück.");
        return ok(done + " Söldner aus " + s.name + " stehen bei dir."
                + (allOwned ? "" : " Sprich sie per Rechtsklick an, um sie zu übernehmen."));
    }    /** Preis für ein Dorf ohne Krieger: wächst mit der Einwohnerzahl. */
    public static int claimCost(int population) {
        return 40 + population * 2;
    }

    private static Result claim(ServerPlayer p, SettlementData d, Settlement s) {
        if (s.owner != null) return fail(s.name + " gehört schon " + s.ownerName + ".");
        boolean first = countOwned(d, p) == 0;
        if (!first) {
            boolean conquered = (s.soldiers <= 0 && War.field(p.getServer().overworld(), s.id) == 0)
            || War.isFallen(s.id, p.getServer().overworld().getGameTime());
            if (!conquered) return fail(s.name + " hat noch Krieger. Erkläre den Krieg und besiege sie zuerst.");
            int cost = claimCost(s.population);
            if (!take(p, Items.GOLD_INGOT, cost)) return fail("Du brauchst " + cost + " Goldbarren im Inventar.");
        }

        s.owner = p.getUUID();
        s.ownerName = p.getGameProfile().getName();
        d.setRep(p.getUUID(), s, 100);
        War.endWars(d, s);
        d.setDirty();
        p.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                        .append(Component.literal(s.ownerName + (first ? " führt jetzt " : " übernimmt ") + s.name + "!").withStyle(ChatFormatting.GRAY)), false);
        return ok(first ? "Du führst jetzt " + s.name + "!" : "Du hast " + s.name + " übernommen!");
    }

    /** Anführer: Krieg erklären. Die Schlacht führst du selbst mit deinen Soldaten. */
    private static Result declare(ServerPlayer p, SettlementData d, Settlement s, int idx) {
        if (!isOwner(p, s)) return fail("Nur der Anführer kann Krieg erklären.");
        List<Settlement> nb = neighbours(d, s);
        if (idx < 0 || idx >= nb.size()) return fail("Dieses Dorf gibt es nicht mehr.");
        Settlement t = nb.get(idx);
        if (t.owner != null && t.owner.equals(p.getUUID())) return fail("Das ist dein eigenes Dorf.");
        if (War.isWar(d, s, t)) return fail("Ihr seid schon im Krieg.");

        War.setWar(d, s, t, true);
        d.setRelation(s, t, -100);
        int dist = (int) Math.sqrt(Simulation.horizDistSq(s.center, t.center));
        p.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                        .append(Component.literal(s.name + " erklärt " + t.name + " den Krieg!").withStyle(ChatFormatting.GRAY)), false);
        return ok("Krieg erklärt! " + t.name + " liegt " + dist + " Blöcke entfernt. Zieh mit deinen Soldaten hin.");
    }

    /** Anführer: Frieden gegen 10 Gold Tribut aus der Dorfkasse. */
    private static Result peace(ServerPlayer p, SettlementData d, Settlement s, int idx) {
        if (!isOwner(p, s)) return fail("Nur der Anführer kann Frieden schließen.");
        List<Settlement> nb = neighbours(d, s);
        if (idx < 0 || idx >= nb.size()) return fail("Dieses Dorf gibt es nicht mehr.");
        Settlement t = nb.get(idx);
        if (!War.isWar(d, s, t)) return fail("Ihr seid nicht im Krieg.");
        for (Expedition e : d.expeditions) {
            if ((e.from.equals(s.id) && e.to.equals(t.id)) || (e.from.equals(t.id) && e.to.equals(s.id))) {
                return fail("Solange Truppen unterwegs sind, ist kein Frieden möglich.");
            }
        }
        if (s.gold < 10) return fail("Die Dorfkasse braucht 10 Gold als Tribut.");
        s.gold -= 10;
        t.gold += 10;
        War.setWar(d, s, t, false);
        d.setRelation(s, t, 0);
        d.setDirty();
        return ok("Frieden mit " + t.name + " (10 Gold Tribut).");
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

    public static boolean isOwner(ServerPlayer p, Settlement s) {
        return s.owner != null && s.owner.equals(p.getUUID());
    }

    public static int countOwned(SettlementData d, ServerPlayer p) {
        int n = 0;
        for (Settlement s : d.settlements) if (isOwner(p, s)) n++;
        return n;
    }

    /** Die (bis zu) 4 nächsten Dörfer in Reichweite, nach Entfernung sortiert. */
    public static List<Settlement> neighbours(SettlementData d, Settlement s) {
        List<Settlement> list = new ArrayList<>();
        for (Settlement o : d.settlements) {
            if (o == s) continue;
            if (Simulation.horizDistSq(s.center, o.center) > (double) Simulation.WAR_RANGE * Simulation.WAR_RANGE) continue;
            list.add(o);
        }
        list.sort((x, y) -> Double.compare(Simulation.horizDistSq(s.center, x.center), Simulation.horizDistSq(s.center, y.center)));
        return list.size() > 4 ? new ArrayList<>(list.subList(0, 4)) : list;
    }

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
