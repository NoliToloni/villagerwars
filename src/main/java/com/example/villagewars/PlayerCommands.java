package com.example.villagewars;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/** Befehle für den Spieler: Ruf, Geschenke, Söldner, Dorfübernahme. */
public class PlayerCommands {
    static final int RANGE = 120;

    @SubscribeEvent
    public void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("villagewars")
                .then(Commands.literal("rep").executes(this::rep))
                .then(Commands.literal("gift").executes(this::gift))
                .then(Commands.literal("support")
                        .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 10)).executes(this::support)))
                .then(Commands.literal("hire")
                        .then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 5)).executes(this::hire)))
                .then(Commands.literal("claim").executes(this::claim))
                .then(Commands.literal("collect").executes(this::collect)));
    }

    // ------------------------------------------------------------------ Befehle

    private int rep(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SettlementData d = SettlementData.get(c.getSource().getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);
        int r = d.getRep(p.getUUID(), s);
        String owner = s.owner == null ? "niemand" : s.ownerName;
        c.getSource().sendSuccess(() -> Component.literal(s.name + ": Ruf " + r + " (" + describe(r) + "), Besitzer: " + owner), false);
        return 1;
    }

    private int gift(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SettlementData d = SettlementData.get(c.getSource().getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);

        ItemStack held = p.getMainHandItem();
        if (held.isEmpty()) return fail(c, "Halte Eisen, Gold, Smaragde, Brot, Getreide oder Holz in der Hand.");
        int n = held.getCount();
        int points;
        if (held.is(Items.IRON_INGOT)) { s.iron += n; points = n * 3; }
        else if (held.is(Items.GOLD_INGOT)) { s.gold += n; points = n * 6; }
        else if (held.is(Items.EMERALD)) { s.gold += n * 2; points = n * 8; }
        else if (held.is(Items.BREAD)) { s.food += n * 6; points = n * 2; }
        else if (held.is(Items.WHEAT) || held.is(Items.CARROT) || held.is(Items.POTATO) || held.is(Items.BEETROOT)) { s.food += n * 3; points = n; }
        else if (held.is(ItemTags.LOGS)) { s.wood += n * 2; points = n; }
        else return fail(c, "Das Dorf kann damit nichts anfangen.");

        held.shrink(n);
        int gain = Math.max(1, points / 10);
        d.addRep(p.getUUID(), s, gain);
        d.setDirty();
        c.getSource().sendSuccess(() -> Component.literal(s.name + " dankt dir! Ruf +" + gain), false);
        return 1;
    }

    private int support(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SettlementData d = SettlementData.get(c.getSource().getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);

        int n = Math.min(IntegerArgumentType.getInteger(c, "anzahl"), Settlement.MAX_SOLDIERS - s.soldiers);
        if (n <= 0) return fail(c, s.name + " hat schon die maximale Armeegröße.");
        if (!take(p, Items.GOLD_INGOT, n * 2)) return fail(c, "Du brauchst " + (n * 2) + " Goldbarren im Inventar.");

        s.soldiers += n;
        d.addRep(p.getUUID(), s, n * 3);
        d.setDirty();
        c.getSource().sendSuccess(() -> Component.literal(n + " Söldner verstärken " + s.name + ". Ruf +" + (n * 3)), false);
        return 1;
    }

    private int hire(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        ServerLevel level = c.getSource().getServer().overworld();
        SettlementData d = SettlementData.get(level);
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);

        int n = IntegerArgumentType.getInteger(c, "anzahl");
        int rep = d.getRep(p.getUUID(), s);
        if (rep < 20) return fail(c, s.name + " vertraut dir noch nicht genug (Ruf mind. 20, du hast " + rep + ").");
        if (s.soldiers - n < 2) return fail(c, s.name + " kann so viele Soldaten nicht entbehren.");
        if (!take(p, Items.EMERALD, n * 3)) return fail(c, "Du brauchst " + (n * 3) + " Smaragde im Inventar.");

        s.soldiers -= n;
        int ok = 0;
        boolean allOwned = true;
        for (int i = 0; i < n; i++) {
            Mob mob = spawnMercenary(p);
            if (mob == null) continue;
            ok++;
            if (!assignOwner(mob, p)) allOwned = false;
        }
        if (ok < n) {
            s.soldiers += n - ok;
            give(p, Items.EMERALD, (n - ok) * 3);
        }
        d.setDirty();
        final int spawned = ok;
        final boolean owned = allOwned;
        if (spawned == 0) return fail(c, "Die Söldner konnten nicht erscheinen. Ist Villager Recruits geladen? Smaragde zurückgegeben.");
        c.getSource().sendSuccess(() -> Component.literal(spawned + " Söldner aus " + s.name + " stehen bei dir."
                + (owned ? "" : " (Hinweis: Besitzer konnte nicht gesetzt werden, sprich sie mit Rechtsklick an.)")), false);
        return 1;
    }

    private int claim(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SettlementData d = SettlementData.get(c.getSource().getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);
        if (s.owner != null) return fail(c, s.name + " gehört schon " + s.ownerName + ".");

        boolean conquered = s.soldiers <= 0;
        int rep = d.getRep(p.getUUID(), s);
        if (!conquered && rep < 50) {
            return fail(c, "Du brauchst Ruf 50 bei " + s.name + " (du hast " + rep + "), oder das Dorf muss ohne Soldaten dastehen.");
        }
        int cost = conquered ? 8 : 24;
        if (!take(p, Items.GOLD_INGOT, cost)) return fail(c, "Du brauchst " + cost + " Goldbarren im Inventar.");

        s.owner = p.getUUID();
        s.ownerName = p.getGameProfile().getName();
        d.setRep(p.getUUID(), s, 100);
        d.setDirty();
        c.getSource().getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                        .append(Component.literal(s.ownerName + " übernimmt " + s.name + "!").withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private int collect(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        SettlementData d = SettlementData.get(c.getSource().getServer().overworld());
        Settlement s = Simulation.nearest(d, p.blockPosition(), RANGE);
        if (s == null) return noVillage(c);
        if (s.owner == null || !s.owner.equals(p.getUUID())) return fail(c, s.name + " gehört nicht dir.");

        int g = (int) (s.gold * 0.5);
        int i = (int) (s.iron * 0.4);
        int b = Math.min(64, (int) (s.food * 0.3) / 4);
        if (g + i + b == 0) return fail(c, "Im Lager ist noch nichts Abholbares.");

        s.gold -= g;
        s.iron -= i;
        s.food -= b * 4;
        give(p, Items.GOLD_INGOT, g);
        give(p, Items.IRON_INGOT, i);
        give(p, Items.BREAD, b);
        d.setDirty();
        c.getSource().sendSuccess(() -> Component.literal("Abgeholt: " + g + " Gold, " + i + " Eisen, " + b + " Brot."), false);
        return 1;
    }

    // ------------------------------------------------------------------ Helfer

    private static String describe(int r) {
        if (r >= 60) return "Freund";
        if (r >= 20) return "freundlich";
        if (r > -20) return "neutral";
        return "feindlich";
    }

    private static int noVillage(CommandContext<CommandSourceStack> c) {
        return fail(c, "Kein Dorf in der Nähe (" + RANGE + " Blöcke). Probiere /villagewars scan.");
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
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

    private Mob spawnMercenary(ServerPlayer p) {
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
        level.addFreshEntity(mob);
        return mob;
    }

    /** Setzt den Besitzer über die Recruits-Klasse (per Reflection, weil wir nicht dagegen kompilieren). */
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
