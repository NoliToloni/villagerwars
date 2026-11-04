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
                       data.setDirty();
                       ensureTeam(level, s);
                       tell(level, s.center, 200, Component.literal("[Dorfkrieg] Siedlung entdeckt: ").withStyle(ChatFormatting.GRAY)
                               .append(Component.literal(s.name).withStyle(COLORS[s.colorIndex])));
                   }
               }
           }
           // Bevölkerung geladener Dörfer an die Realität angleichen
           for (Settlement s : data.settlements) {
               if (level.hasChunkAt(s.center)) {
                   int real = level.getEntitiesOfClass(Villager.class, new AABB(s.center).inflate(64)).size();
                   if (real > 0 && real != s.population) {
                       s.population = real;
                       data.setDirty();
                   }
               }
           }
       }

       static Settlement nearest(SettlementData data, BlockPos pos, int maxDist) {
           Settlement best = null;
           double bestD = (double) maxDist * maxDist;
           for (Settlement s : data.settlements) {
               double d = horizDistSq(s.center, pos);
               if (d <= bestD) {
                   bestD = d;
                   best = s;
               }
           }
           return best;
       }

       static double horizDistSq(BlockPos a, BlockPos b) {
           double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
           return dx * dx + dz * dz;
       }

       // ------------------------------------------------------------------ Wirtschaft

       void economy(ServerLevel level) {
           SettlementData data = SettlementData.get(level);
           for (Settlement s : data.settlements) {
               // Produktion
               s.food += s.population * 2.0;
               s.wood += s.population * 0.8;
               s.iron += s.population * 0.3;
               s.gold += s.population * 0.2;
               // Verbrauch
               s.food -= s.population * 1.0 + s.soldiers * 1.5;

               if (s.food < 0) {
                   s.food = 0;
                   // Hunger: Soldaten desertieren zuerst, dann sinkt die Bevölkerung
                   if (s.soldiers > 0 && rnd.nextInt(2) == 0) s.soldiers--;
                   else if (s.population > 2 && rnd.nextInt(3) == 0) s.population--;
               } else if (s.food > s.population * 12.0 && s.population < 40) {
                   s.population++;
                   s.food -= 12;
               }

               // Rekrutierung
               int cap = Math.min(Settlement.MAX_SOLDIERS, (int) Math.ceil(s.population * 0.6));
               for (int i = 0; i < 2 && s.soldiers < cap && s.iron >= 5 && s.food >= 15 && s.gold >= 1; i++) {
                   s.soldiers++;
                   s.iron -= 5;
                   s.food -= 10;
                   s.gold -= 1;
               }
               // Vorräte begrenzen (Lagergröße)
               s.food = Math.min(s.food, 1500);
               s.wood = Math.min(s.wood, 1500);
               s.iron = Math.min(s.iron, 600);
               s.gold = Math.min(s.gold, 300);
           }
           data.setDirty();
       }

       // ------------------------------------------------------------------ Diplomatie

       void diplomacy(ServerLevel level) {
           SettlementData data = SettlementData.get(level);
           List<Settlement> list = data.settlements;
           long now = level.getGameTime();

           for (int i = 0; i < list.size(); i++) {
               for (int j = i + 1; j < list.size(); j++) {
                   Settlement a = list.get(i), b = list.get(j);
                   if (horizDistSq(a.center, b.center) > (double) WAR_RANGE * WAR_RANGE) continue;

                   int delta = rnd.nextInt(5) - 2;                          // Zufallsrauschen
                   int rel = data.getRelation(a, b);
                   delta += rel > 0 ? -1 : (rel < 0 ? 1 : 0);              // Drift Richtung neutral

                   boolean aHungry = a.food < a.population * 3, bHungry = b.food < b.population * 3;
                   boolean aRich = a.food > a.population * 10, bRich = b.food > b.population * 10;
                   if ((aHungry && bRich) || (bHungry && aRich)) delta -= 6; // Neid auf reiche Nachbarn
                   double ratio = Math.max(a.strength(), 1) / Math.max(b.strength(), 1);
                   if (ratio > 2.0 || ratio < 0.5) delta -= 2;               // Machtgefälle
                   if (!aHungry && !bHungry && Math.abs(ratio - 1) < 0.4) delta += 2; // Handel unter Gleichen

                   data.addRelation(a, b, delta);
               }
           }

           // Kriegserklärungen
           for (Settlement a : list) {
               if (data.atWar(a) || now - a.lastWarTick < WAR_COOLDOWN || a.soldiers < 4) continue;
               Settlement target = null;
               int worst = WAR_THRESHOLD;
               for (Settlement b : list) {
                   if (b == a || horizDistSq(a.center, b.center) > (double) WAR_RANGE * WAR_RANGE) continue;
                   int rel = data.getRelation(a, b);
                   if (rel <= worst && a.strength() >= b.strength() * 0.8) {
                       worst = rel;
                       target = b;
                   }
               }
               if (target != null) launch(level, data, a, target);
           }
           data.setDirty();
       }

       public void launch(ServerLevel level, SettlementData data, Settlement from, Settlement to) {
           int count = Math.max(2, Math.min(10, from.soldiers / 2));
           if (from.soldiers < count) return;
           from.soldiers -= count;
           from.lastWarTick = level.getGameTime();
           double dist = Math.sqrt(horizDistSq(from.center, to.center));
           long travel = (long) (dist * TICKS_PER_BLOCK);
           Expedition ex = new Expedition(UUID.randomUUID(), from.id, to.id, count, level.getGameTime(), travel);
           ex.lastPlayerSeen = level.getGameTime();
           data.expeditions.add(ex);
           data.setDirty();

           Component msg = Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                   .append(Component.literal(from.name).withStyle(COLORS[from.colorIndex]))
                   .append(Component.literal(" schickt " + count + " Soldaten gegen ").withStyle(ChatFormatting.GRAY))
                   .append(Component.literal(to.name).withStyle(COLORS[to.colorIndex]))
                   .append(Component.literal(" (Ankunft in ca. " + (travel / 1200) + " Min.)").withStyle(ChatFormatting.GRAY));
           tell(level, from.center, 500, msg);
           tell(level, to.center, 500, msg);
       }

       // ------------------------------------------------------------------ Feldzüge

       void updateExpeditions(ServerLevel level) {
           SettlementData data = SettlementData.get(level);
           long now = level.getGameTime();
           Iterator<Expedition> it = data.expeditions.iterator();
           while (it.hasNext()) {
               Expedition ex = it.next();
               Settlement from = data.byId(ex.from), to = data.byId(ex.to);
               if (from == null || to == null) {
                   discardAll(level, ex);
                   it.remove();
                   data.setDirty();
                   continue;
               }
               boolean playerNear = playerWithin(level, to.center, REAL_BATTLE_RADIUS);
               if (playerNear) ex.lastPlayerSeen = now;

               if (!ex.real) {
                   if (now - ex.startTick >= ex.travelTicks) {
                       if (playerNear) startRealBattle(level, ex, from, to);
                       else {
                           resolve(level, data, ex, from, to, ex.attackers, 0);
                           it.remove();
                           data.setDirty();
                       }
                   }
                   continue;
               }

               // --- echtes Gefecht ---
               int aliveAtk = countAlive(level, ex.atkEntities);
               int aliveDef = countAlive(level, ex.defEntities);

               // Verteidiger erst nachziehen, wenn die Angreifer nah genug sind
               if (ex.defendersDeployed == 0 && aliveAtk > 0 && attackersNear(level, ex, to.center, 70)) {
                   int n = Math.min(8, to.soldiers);
                   if (n > 0) {
                       to.soldiers -= n;
                       spawnTroops(level, ex, to, n, to.center, false);
                       ex.defendersDeployed = n;
                   }
                   else ex.defendersDeployed = -1; // keine Verteidiger vorhanden
               }
               aliveDef = countAlive(level, ex.defEntities);

               boolean noDefenders = ex.defendersDeployed != 0 && aliveDef == 0;
               boolean noAttackers = aliveAtk == 0;
               boolean timeout = now - ex.lastPlayerSeen > 600; // Spieler lange weg -> abstrakt beenden

               if (noAttackers || noDefenders || timeout) {
                   // Überlebende aufräumen und zurückbuchen
                   discardAll(level, ex);
                   int backDef = Math.max(0, aliveDef);
                   if (noAttackers && !timeout) {
                       to.soldiers += backDef;
                       outcome(level, data, ex, from, to, false, 0);
                   } else if (noDefenders && !timeout) {
                       outcome(level, data, ex, from, to, true, aliveAtk);
                   } else {
                       resolve(level, data, ex, from, to, aliveAtk, backDef);
                   }
                   it.remove();
                   data.setDirty();
               }
           }
       }

       /** Abstrakter Kampf ohne Spieler in der Nähe. */
       void resolve(ServerLevel level, SettlementData data, Expedition ex, Settlement from, Settlement to,
                    int attackers, int deployedDefendersBack) {
           to.soldiers += deployedDefendersBack;
           double atk = attackers * (1.0 + Math.min(from.iron, 200) / 200.0) * (0.8 + rnd.nextDouble() * 0.4);
           double def = to.soldiers * 1.2 * (1.0 + Math.min(to.iron, 200) / 200.0) * (0.8 + rnd.nextDouble() * 0.4);
           boolean attackerWins = atk > def;
           int survivors = attackerWins ? Math.max(1, (int) Math.round(attackers * (1.0 - Math.min(0.9, def / (atk + 0.01))))) : 0;
           if (attackerWins) to.soldiers = 0; else to.soldiers = Math.max(0, to.soldiers - (int) Math.round(atk / 1.5));
           outcome(level, data, ex, from, to, attackerWins, survivors);
       }

       void outcome(ServerLevel level, SettlementData data, Expedition ex, Settlement from, Settlement to,
                    boolean attackerWins, int survivors) {
           if (attackerWins) {
               double f = 0.4;
               from.food += to.food * f; to.food *= 1 - f;
               from.wood += to.wood * f; to.wood *= 1 - f;
               from.iron += to.iron * f; to.iron *= 1 - f;
               from.gold += to.gold * f; to.gold *= 1 - f;
               from.soldiers += survivors;
               to.population = Math.max(2, to.population - 2);
               data.setRelation(from, to, -40);
           } else {
               data.setRelation(from, to, -30);
           }
           Component msg = Component.literal("[Dorfkrieg] ").withStyle(ChatFormatting.DARK_RED)
                   .append(Component.literal(attackerWins ? from.name : to.name).withStyle(COLORS[(attackerWins ? from : to).colorIndex]))
                   .append(Component.literal(attackerWins
                           ? " plündert " + to.name + "!"
                           : " wehrt den Angriff von " + from.name + " ab!").withStyle(ChatFormatting.GRAY));
           tell(level, from.center, 500, msg);
           tell(level, to.center, 500, msg);
           data.setDirty();
       }

       // ------------------------------------------------------------------ Echte Truppen

       void startRealBattle(ServerLevel level, Expedition ex, Settlement from, Settlement to) {
           ex.real = true;
           ex.lastPlayerSeen = level.getGameTime();
           // Angreifer erscheinen ca. 60 Blöcke vom Zieldorf entfernt, auf der Seite des Heimatdorfes
           double dx = from.center.getX() - to.center.getX(), dz = from.center.getZ() - to.center.getZ();
           double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
           BlockPos spawn = new BlockPos((int) (to.center.getX() + dx / len * 60), 64, (int) (to.center.getZ() + dz / len * 60));
           if (!level.hasChunkAt(spawn)) spawn = to.center;
           spawnTroops(level, ex, from, ex.attackers, spawn, true);
       }

       void spawnTroops(ServerLevel level, Expedition ex, Settlement owner, int count, BlockPos around, boolean attackers) {
           PlayerTeam team = ensureTeam(level, owner);
           Scoreboard sb = level.getScoreboard();
           for (int i = 0; i < count; i++) {
               ResourceLocation rl = new ResourceLocation("recruits", UNIT_IDS[rnd.nextInt(UNIT_IDS.length)]);
               if (!ForgeRegistries.ENTITY_TYPES.containsKey(rl)) continue; // Recruits nicht geladen?
               EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(rl);
               Entity ent = type.create(level);
               if (!(ent instanceof Mob mob)) continue;
               int x = around.getX() + rnd.nextInt(9) - 4, z = around.getZ() + rnd.nextInt(9) - 4;
               if (!level.hasChunkAt(new BlockPos(x, 64, z))) { x = around.getX(); z = around.getZ(); }
               int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
               mob.moveTo(x + 0.5, y, z + 0.5, rnd.nextFloat() * 360f, 0f);
               mob.getPersistentData().putUUID("vw_exp", ex.id);
               mob.getPersistentData().putUUID("vw_home", owner.id);
               mob.getPersistentData().putBoolean("vw_atk", attackers);
               mob.setPersistenceRequired();
               (attackers ? ex.atkEntities : ex.defEntities).add(mob.getUUID());
               mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), MobSpawnType.EVENT, null, null);
               Equipment.apply(mob, owner, rnd, true);
               level.addFreshEntity(mob);
               sb.addPlayerToTeam(mob.getScoreboardName(), team);
           }
       }

       void steerTroops(ServerLevel level) {
           SettlementData data = SettlementData.get(level);
           for (Expedition ex : data.expeditions) {
               if (!ex.real) continue;
               Settlement to = data.byId(ex.to);
               if (to == null) continue;
               for (UUID id : ex.atkEntities) steer(level, id, ex.defEntities, to.center, true);
               for (UUID id : ex.defEntities) steer(level, id, ex.atkEntities, to.center, false);
           }
       }

       void steer(ServerLevel level, UUID id, List<UUID> enemies, BlockPos objective, boolean attacker) {
           Entity e = level.getEntity(id);
           if (!(e instanceof Mob mob) || !mob.isAlive()) return;

           Mob closest = null;
           double best = 40 * 40;
           for (UUID eid : enemies) {
               Entity en = level.getEntity(eid);
               if (en instanceof Mob m && m.isAlive()) {
                   double d = mob.distanceToSqr(m);
                   if (d < best) {
                       best = d;
                       closest = m;
                   }
               }
           }
           if (closest != null) {
               mob.setTarget(closest);
               return;
           }
           double dist = Math.sqrt(horizDistSq(mob.blockPosition(), objective));
           if (attacker || dist > 20) {
               double step = Math.min(dist, 24);
               double dx = (objective.getX() - mob.getX()) / Math.max(dist, 1), dz = (objective.getZ() - mob.getZ()) / Math.max(dist, 1);
               int tx = (int) (mob.getX() + dx * step), tz = (int) (mob.getZ() + dz * step);
               if (level.hasChunkAt(new BlockPos(tx, 64, tz))) {
                   int ty = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, tx, tz);
                   mob.getNavigation().moveTo(tx + 0.5, ty, tz + 0.5, 1.15);
               }
           }
       }

       // ------------------------------------------------------------------ Helfer

       PlayerTeam ensureTeam(ServerLevel level, Settlement s) {
           Scoreboard sb = level.getScoreboard();
           PlayerTeam team = sb.getPlayerTeam(s.teamName());
           if (team == null) {
               team = sb.addPlayerTeam(s.teamName());
               team.setDisplayName(Component.literal(s.name));
               team.setColor(COLORS[s.colorIndex % COLORS.length]);
               team.setAllowFriendlyFire(false);
           }
           return team;
       }

       int countAlive(ServerLevel level, List<UUID> ids) {
           int n = 0;
           for (UUID id : ids) {
               Entity e = level.getEntity(id);
               if (e != null && e.isAlive()) n++;
           }
           return n;
       }

       boolean attackersNear(ServerLevel level, Expedition ex, BlockPos pos, int radius) {
           for (UUID id : ex.atkEntities) {
               Entity e = level.getEntity(id);
               if (e != null && e.isAlive() && horizDistSq(e.blockPosition(), pos) <= (double) radius * radius) return true;
           }
           return false;
       }

       void discardAll(ServerLevel level, Expedition ex) {
           for (UUID id : ex.atkEntities) { Entity e = level.getEntity(id); if (e != null) e.discard(); }
           for (UUID id : ex.defEntities) { Entity e = level.getEntity(id); if (e != null) e.discard(); }
           ex.atkEntities.clear();
           ex.defEntities.clear();
       }

       static boolean playerWithin(ServerLevel level, BlockPos pos, int radius) {
           for (ServerPlayer p : level.players()) {
               if (horizDistSq(p.blockPosition(), pos) <= (double) radius * radius) return true;
           }
           return false;
       }

       static void tell(ServerLevel level, BlockPos pos, int radius, Component msg) {
           for (ServerPlayer p : level.players()) {
               if (horizDistSq(p.blockPosition(), pos) <= (double) radius * radius) p.sendSystemMessage(msg);
           }
       }
   }
