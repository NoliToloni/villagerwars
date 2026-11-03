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
   import net.minecraft.world.entity.EntityType;
   import net.minecraft.world.entity.Mob;
   import net.minecraft.world.entity.MobSpawnType;
   import net.minecraft.world.entity.npc.Villager;
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

   public class Simulation {
       // ---- Stellschrauben ----
       static final int DISCOVER_INTERVAL = 100;        // Ticks (5 s)
       static final int ECONOMY_INTERVAL = 1200;        // Ticks (1 min)
       static final int WAR_RANGE = 700;                // max. Entfernung für Diplomatie/Krieg
       static final int MERGE_DISTANCE = 96;            // Villager näher als das gehören zum selben Dorf
       static final int REAL_BATTLE_RADIUS = 100;       // Spieler so nah -> echte Truppen werden gespawnt
       static final int TICKS_PER_BLOCK = 8;            // Marschtempo
       static final int WAR_COOLDOWN = 12000;           // 10 min zwischen Kriegen eines Dorfes
       static final int WAR_THRESHOLD = -60;            // Beziehung, ab der ein Angriff möglich ist

       static final String[] PREFIX = {"Eichen", "Stein", "Raben", "Nebel", "Eisen", "Wolfs", "Birken", "Falken", "Rosen", "Dunkel"};
       static final String[] SUFFIX = {"dorf", "furt", "hafen", "heim", "bach", "stedt", "wacht", "tal"};
       static final ChatFormatting[] COLORS = {ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.GREEN,
               ChatFormatting.YELLOW, ChatFormatting.AQUA, ChatFormatting.LIGHT_PURPLE, ChatFormatting.GOLD, ChatFormatting.DARK_GREEN};
       static final String[] UNIT_IDS = {"recruit", "recruit", "recruit_shieldman", "bowman", "crossbowman"};

       private final RandomSource rnd = RandomSource.create();

       @SubscribeEvent
       public void onServerTick(TickEvent.ServerTickEvent e) {
           if (e.phase != TickEvent.Phase.END) return;
           MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
           if (server == null) return;
           ServerLevel level = server.overworld();
           int t = server.getTickCount();

           if (t % DISCOVER_INTERVAL == 0) discover(level);
           if (t % ECONOMY_INTERVAL == 0) {
               economy(level);
               diplomacy(level);
           }
           if (t % 20 == 0) updateExpeditions(level);
           if (t % 10 == 0) steerTroops(level);
       }

       /** Echte Truppen, die zu keinem laufenden Gefecht mehr gehören (z.B. nach Neustart), werden entfernt. */
       @SubscribeEvent
       public void onJoin(EntityJoinLevelEvent e) {
           if (e.getLevel().isClientSide()) return;
           Entity ent = e.getEntity();
           if (!ent.getPersistentData().contains("vw_exp")) return;
           if (!(e.getLevel() instanceof ServerLevel level)) return;
           UUID expId = ent.getPersistentData().getUUID("vw_exp");
           for (Expedition ex : SettlementData.get(level).expeditions) {
               if (ex.id.equals(expId) && ex.real
                       && (ex.atkEntities.contains(ent.getUUID()) || ex.defEntities.contains(ent.getUUID()))) return;
           }
           e.setCanceled(true);
       }

       // ------------------------------------------------------------------ Entdeckung

       void discover(ServerLevel level) {
           SettlementData data = SettlementData.get(level);
           for (ServerPlayer p : level.players()) {
               AABB box = p.getBoundingBox().inflate(80);
               List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box);
               for (Villager v : villagers) {
                   Settlement near = nearest(data, v.blockPosition(), MERGE_DISTANCE);
                   if (near == null) {
                       BlockPos pos = v.blockPosition();
                       int pop = Math.max(3, level.getEntitiesOfClass(Villager.class, new AABB(pos).inflate(48)).size());
                       String name = PREFIX[rnd.nextInt(PREFIX.length)] + SUFFIX[rnd.nextInt(SUFFIX.length)];
                       Settlement s = new Settlement(UUID.randomUUID(), name, pos, pop, data.settlements.size() % COLORS.length);
                       s.soldiers = Math.max(1, pop / 4);
                       data.settlements.add(s);
