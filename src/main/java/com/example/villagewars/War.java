package com.example.villagewars;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

/**
 * Kriegszustand zwischen Dörfern. Der Spieler erklärt den Krieg und zieht selbst los:
 * Kommt er mit seinen Truppen in die Nähe des Feindes, wirft das Dorf echte Verteidiger aus.
 */
public class War {
    static final int TRIGGER = 70;        // Spieler so nah -> Verteidiger erscheinen
    static final int GUARD_RANGE = 64;    // Spieler so nah -> die Wachen des Dorfes werden sichtbar
    static final int ABANDON = 160;       // Spieler weiter weg -> Verteidiger ziehen sich zurück
    static final int FALLEN_TICKS = 6000; // so lange ist ein besiegtes Dorf übernehmbar (5 Min.)

    private static final Map<UUID, Long> FALLEN = new HashMap<>();

    private static class Defense {
        final List<UUID> ids = new ArrayList<>();
        long lastNear;
    }

    private static final Map<UUID, Defense> defenses = new HashMap<>();
    private final Simulation sim = new Simulation();
    private final RandomSource rnd = RandomSource.create();

    // ------------------------------------------------------------------ Kriegszustand
    // wird in SettlementData.relations unter dem Schlüssel "W:idA|idB" gespeichert

    private static String key(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return "W:" + (x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x);
    }

    public static boolean isWar(SettlementData d, Settlement a, Settlement b) {
        return d.relations.containsKey(key(a.id, b.id));
    }

    public static void setWar(SettlementData d, Settlement a, Settlement b, boolean on) {
        if (on) d.relations.put(key(a.id, b.id), 1);
        else d.relations.remove(key(a.id, b.id));
        d.setDirty();
    }

    public static boolean inWar(SettlementData d, Settlement s) {
        String id = s.id.toString();
        for (String k : d.relations.keySet()) {
            if (k.startsWith("W:") && k.contains(id)) return true;
        }
        return false;
    }

    public static void endWars(SettlementData d, Settlement s) {
        String id = s.id.toString();
        d.relations.keySet().removeIf(k -> k.startsWith("W:") && k.contains(id));
        d.setDirty();
    }

    public static boolean isFallen(UUID id, long now) {
        Long t = FALLEN.get(id);
        return t != null && t > now;
    }

    /** Verteidiger eines Dorfes, die gerade im Kampf leben. */
    public static int field(ServerLevel level, UUID settlementId) {
        Defense def = defenses.get(settlementId);
        return def == null ? 0 : countAlive(level, def.ids);
    }

    /** Besitzer eines Recruits-Soldaten (Recruits-Besitzer oder das Zeichen deines Schwert-Addons), sonst null. */
    public static UUID ownerOf(Mob m) {
        if (m.getPersistentData().contains("vw_def")) return null;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(m.getType());
        if (id == null || !id.getNamespace().equals("recruits")) return null;
        try {
            Object o = m.getClass().getMethod("getOwnerUUID").invoke(m);
            if (o instanceof UUID u) return u;
        } catch (Throwable ignored) {
        }
        if (m.getPersistentData().hasUUID("recruitOwner")) return m.getPersistentData().getUUID("recruitOwner");
        return null;
    }

    /** Wie viele Rekruten des Spielers stehen im Umkreis des Dorfes. */
    public static int ownTroops(ServerLevel level, BlockPos center, UUID owner, int radius) {
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(center).inflate(radius))) {
            if (m.isAlive() && owner.equals(ownerOf(m))) n++;
        }
        return n;
    }

    private static boolean playerNear(ServerLevel level, BlockPos c, int range) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (Simulation.horizDistSq(p.blockPosition(), c) <= (double) range * range) return true;
        }
        return false;
    }

    private static Settlement[] pair(SettlementData d, String k) {
        try {
            String[] ids = k.substring(2).split("\\|");
            Settlement a = d.byId(UUID.fromString(ids[0]));
            Settlement b = d.byId(UUID.fromString(ids[1]));
            return a == null || b == null ? null : new Settlement[]{a, b};
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Tick

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        int t = server.getTickCount();
        if (t % 1200 == 600) keepHostile(level);
        if (t % 20 == 10) updateDefenses(server, level);
        if (t % 10 == 5) steer(level);
    }

    /** Übrig gebliebene Verteidiger (z.B. nach einem Neustart) werden entfernt. */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide()) return;
        Entity ent = e.getEntity();
        if (!ent.getPersistentData().contains("vw_def")) return;
        for (Defense d : defenses.values()) {
            if (d.ids.contains(ent.getUUID())) return;
        }
        e.setCanceled(true);
    }

    /** Im Krieg bleibt die Beziehung feindlich, damit die KI nicht von selbst Frieden macht. */
    private void keepHostile(ServerLevel level) {
        SettlementData d = SettlementData.get(level);
        for (String k : new ArrayList<>(d.relations.keySet())) {
            if (!k.startsWith("W:")) continue;
            Settlement[] pr = pair(d, k);
            if (pr == null) {
                d.relations.remove(k);
                d.setDirty();
                continue;
            }
            if (d.getRelation(pr[0], pr[1]) > -80) d.setRelation(pr[0], pr[1], -80);
        }
    }

    // ------------------------------------------------------------------ Verteidigung

    private void updateDefenses(MinecraftServer server, ServerLevel level) {
        SettlementData d = SettlementData.get(level);
        long now = level.getGameTime();

        for (String k : new ArrayList<>(d.relations.keySet())) {
            if (!k.startsWith("W:")) continue;
            Settlement[] pr = pair(d, k);
            if (pr == null) continue;
            trigger(server, level, d, pr[0], pr[1], now);
            trigger(server, level, d, pr[1], pr[0], now);
        }

        // Friedenszeit: Wachen sichtbar machen, solange ein Spieler im Dorf ist
        for (Settlement s : d.settlements) {
            if (defenses.containsKey(s.id) || s.soldiers <= 0 || isFallen(s.id, now)) continue;
            if (!playerNear(level, s.center, GUARD_RANGE)) continue;
            int n = Math.min(10, s.soldiers);
            Defense g = new Defense();
            g.lastNear = now;
            defenses.put(s.id, g);
            spawnDefenders(level, s, g, n);
            if (g.ids.isEmpty()) {
                defenses.remove(s.id);
                continue;
            }
            s.soldiers -= g.ids.size();
            d.setDirty();
        }

        for (Map.Entry<UUID, Defense> en : new ArrayList<>(defenses.entrySet())) {
            Settlement y = d.byId(en.getKey());
            Defense def = en.getValue();
            if (y == null) {
                discard(level, def.ids);
                defenses.remove(en.getKey());
                continue;
            }
            int alive = countAlive(level, def.ids);
            if (playerNear(level, y.center, ABANDON)) def.lastNear = now;
            if (alive == 0) {
                defenses.remove(y.id);
                if (y.soldiers <= 0 && inWar(d, y)) {
                    FALLEN.put(y.id, now + FALLEN_TICKS);
                    Simulation.tell(level, y.center, 300, Component.literal("[Dorfkrieg] " + y.name
                            + " ist gefallen und kann übernommen werden!").withStyle(ChatFormatting.DARK_RED));
                }
            } else if (now - def.lastNear > 600) {
                y.soldiers += alive;
                discard(level, def.ids);
                defenses.remove(y.id);
                d.setDirty();
            }
        }
    }

    private void trigger(MinecraftServer server, ServerLevel level, SettlementData d,
                         Settlement attacker, Settlement target, long now) {
        if (attacker.owner == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(attacker.owner);
        if (p == null) return;
        double dist = Math.sqrt(Simulation.horizDistSq(p.blockPosition(), target.center));

        Defense def = defenses.get(target.id);
        if (def != null) {
            if (dist <= ABANDON) def.lastNear = now;
            return;
        }
        if (dist > TRIGGER || target.soldiers <= 0 || isFallen(target.id, now)) return;

        int n = Math.min(10, target.soldiers);
        target.soldiers -= n;
        def = new Defense();
        def.lastNear = now;
        defenses.put(target.id, def);
        spawnDefenders(level, target, def, n);
        if (def.ids.size() < n) target.soldiers += n - def.ids.size();
        if (def.ids.isEmpty()) {
            defenses.remove(target.id);
            return;
        }
        d.setDirty();
        Simulation.tell(level, target.center, 200, Component.literal("[Dorfkrieg] " + target.name
                + " verteidigt sich!").withStyle(ChatFormatting.DARK_RED));
    }

    private void spawnDefenders(ServerLevel level, Settlement y, Defense def, int n) {
        PlayerTeam team = sim.ensureTeam(level, y);
        Scoreboard sb = level.getScoreboard();
        BlockPos c = y.center;
        for (int i = 0; i < n; i++) {
            ResourceLocation rl = new ResourceLocation("recruits", Simulation.UNIT_IDS[rnd.nextInt(Simulation.UNIT_IDS.length)]);
            if (!ForgeRegistries.ENTITY_TYPES.containsKey(rl)) continue;
            Entity ent = ForgeRegistries.ENTITY_TYPES.getValue(rl).create(level);
            if (!(ent instanceof Mob mob)) continue;
            int x = c.getX() + rnd.nextInt(13) - 6, z = c.getZ() + rnd.nextInt(13) - 6;
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            mob.moveTo(x + 0.5, h, z + 0.5, rnd.nextFloat() * 360f, 0f);
            mob.getPersistentData().putBoolean("vw_def", true);
            mob.setPersistenceRequired();
            def.ids.add(mob.getUUID());
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null, null);
            Equipment.apply(mob, y, rnd, true);
            level.addFreshEntity(mob);
            sb.addPlayerToTeam(mob.getScoreboardName(), team);
        }
    }

    /** Verteidiger greifen den Spieler und dessen Rekruten an; die Rekruten des Spielers die Verteidiger. */
    private void steer(ServerLevel level) {
        if (defenses.isEmpty()) return;
        SettlementData d = SettlementData.get(level);
        for (Map.Entry<UUID, Defense> en : defenses.entrySet()) {
            Settlement y = d.byId(en.getKey());
            if (y == null || !inWar(d, y)) continue;
            List<Mob> defenders = aliveMobs(level, en.getValue().ids);
            if (defenders.isEmpty()) continue;
            Mob first = defenders.get(0);
            AABB box = first.getBoundingBox().inflate(48);

            List<LivingEntity> enemies = new ArrayList<>();
            for (ServerPlayer p : level.players()) {
                if (p.isCreative() || p.isSpectator()) continue;
                if (y.owner != null && y.owner.equals(p.getUUID())) continue;
                if (p.distanceToSqr(first) < 48.0 * 48.0) enemies.add(p);
            }
            List<Mob> playerTroops = new ArrayList<>();
            for (Mob m : level.getEntitiesOfClass(Mob.class, box)) {
                if (isPlayerRecruit(m)) {
                    enemies.add(m);
                    playerTroops.add(m);
                }
            }

            for (Mob df : defenders) {
                LivingEntity best = null;
                double bd = Double.MAX_VALUE;
                for (LivingEntity c : enemies) {
                    double dd = df.distanceToSqr(c);
                    if (dd < bd) {
                        bd = dd;
                        best = c;
                    }
                }
                if (best != null) df.setTarget(best);
            }

            for (Mob troop : playerTroops) {
                LivingEntity cur = troop.getTarget();
                if (cur != null && cur.isAlive()) continue;
                Mob near = null;
                double bd = 30.0 * 30.0;
                for (Mob df : defenders) {
                    double dd = troop.distanceToSqr(df);
                    if (dd < bd) {
                        bd = dd;
                        near = df;
                    }
                }
                if (near != null) troop.setTarget(near);
            }
        }
    }

    /** Ein Recruits-Soldat mit Besitzer (per Reflection abgefragt, weil wir nicht dagegen kompilieren). */
    private static boolean isPlayerRecruit(Mob m) {
        return ownerOf(m) != null;
    }

    private static List<Mob> aliveMobs(ServerLevel level, List<UUID> ids) {
        List<Mob> list = new ArrayList<>();
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (e instanceof Mob m && m.isAlive()) list.add(m);
        }
        return list;
    }

    private static int countAlive(ServerLevel level, List<UUID> ids) {
        return aliveMobs(level, ids).size();
    }

    private static void discard(ServerLevel level, List<UUID> ids) {
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        ids.clear();
    }
}
