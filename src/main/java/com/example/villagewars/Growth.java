package com.example.villagewars;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Wirtschaftsschwerpunkt (vom Anführer gewählt) und sichtbares Dorfwachstum (neue Häuser).
 * Alles wird in SettlementData.relations abgelegt: "F:id" = Schwerpunkt, "H:id" = gebaute Häuser.
 */
public class Growth {
    public static final int F_NONE = 0, F_FOOD = 1, F_WOOD = 2, F_IRON = 3, F_GOLD = 4;
    public static final String[] FOCUS_NAMES = {"Ausgewogen", "Nahrung", "Holz", "Eisen", "Gold"};

    static final int MAX_HOUSES = 12;
    static final int HOUSE_WOOD = 12;
    static final int SHOW_RANGE = 80;

    private final RandomSource rnd = RandomSource.create();

    public static int getFocus(SettlementData d, Settlement s) {
        return d.relations.getOrDefault("F:" + s.id, 0);
    }

    public static void setFocus(SettlementData d, Settlement s, int f) {
        d.relations.put("F:" + s.id, f);
        d.setDirty();
    }

    public static int getHouses(SettlementData d, Settlement s) {
        return d.relations.getOrDefault("H:" + s.id, 0);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int t = server.getTickCount();
        ServerLevel level = server.overworld();
        if (t % 1200 == 300) bonus(level);
        if (t % 200 == 100) grow(level);
    }

    /** Zusätzliche Produktion im gewählten Schwerpunkt (pro Minute). */
    private void bonus(ServerLevel level) {
        SettlementData d = SettlementData.get(level);
        for (Settlement s : d.settlements) {
            int f = getFocus(d, s);
            if (f == F_NONE) continue;
            double pop = s.population;
            switch (f) {
                case F_FOOD -> s.food += pop * 0.8;
                case F_WOOD -> s.wood += pop * 0.5;
                case F_IRON -> s.iron += pop * 0.25;
                case F_GOLD -> s.gold += pop * 0.12;
                default -> {
                }
            }
        }
        d.setDirty();
    }

    private void grow(ServerLevel level) {
        SettlementData d = SettlementData.get(level);
        for (Settlement s : d.settlements) {
            int houses = getHouses(d, s);
            if (houses >= MAX_HOUSES || s.wood < HOUSE_WOOD || s.population < 6 + houses * 3) continue;
            if (!playerNear(level, s.center)) continue;
            BlockPos site = findSite(level, s, houses);
            if (site == null) continue;
            buildHouse(level, site);
            s.wood -= HOUSE_WOOD;
            d.relations.put("H:" + s.id, houses + 1);
            d.setDirty();
            Simulation.tell(level, s.center, 120, Component.literal("[Dorf] " + s.name
                    + " wächst: ein neues Haus ist entstanden.").withStyle(ChatFormatting.GREEN));
            return; // höchstens ein Haus pro Durchlauf
        }
    }

    private static boolean playerNear(ServerLevel level, BlockPos c) {
        for (ServerPlayer p : level.players()) {
            if (Simulation.horizDistSq(p.blockPosition(), c) <= (double) SHOW_RANGE * SHOW_RANGE) return true;
        }
        return false;
    }

    private static boolean groundOk(ServerLevel level, BlockPos below) {
        BlockState st = level.getBlockState(below);
        return st.is(BlockTags.DIRT) || st.is(BlockTags.SAND) || st.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    /** Sucht eine ebene, freie 5x5-Fläche rund ums Dorfzentrum. */
    private BlockPos findSite(ServerLevel level, Settlement s, int houses) {
        for (int attempt = 0; attempt < 24; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            int r = 12 + Math.min(28, houses * 2) + rnd.nextInt(8);
            int x0 = s.center.getX() + (int) Math.round(Math.cos(ang) * r);
            int z0 = s.center.getZ() + (int) Math.round(Math.sin(ang) * r);
            if (!level.hasChunkAt(new BlockPos(x0, 64, z0)) || !level.hasChunkAt(new BlockPos(x0 + 4, 64, z0 + 4))) continue;
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0, z0);
            boolean ok = true;
            for (int dx = 0; dx < 5 && ok; dx++) {
                for (int dz = 0; dz < 5 && ok; dz++) {
                    int hh = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz);
                    if (hh != h || !groundOk(level, new BlockPos(x0 + dx, hh - 1, z0 + dz))) ok = false;
                    else if (!level.getBlockState(new BlockPos(x0 + dx, hh, z0 + dz)).isAir()) ok = false;
                }
            }
            if (ok) return new BlockPos(x0, h, z0);
        }
        return null;
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState st) {
        level.setBlock(pos, st, Block.UPDATE_ALL);
    }

    /** Kleines Haus 5x5: Holzwände, Dach, Tür (Süden), Fenster, Bett, Fackel. o = Boden-Ebene (erste Luft-Ebene). */
    private void buildHouse(ServerLevel level, BlockPos o) {
        int x0 = o.getX(), y = o.getY(), z0 = o.getZ();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
                BlockPos fl = new BlockPos(x0 + dx, y - 1, z0 + dz);
                put(level, fl, planks);
                boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4;
                boolean corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                for (int dy = 0; dy < 3; dy++) {
                    BlockPos p = new BlockPos(x0 + dx, y + dy, z0 + dz);
                    if (!edge) put(level, p, air);
                    else put(level, p, corner ? log : planks);
                }
                put(level, new BlockPos(x0 + dx, y + 3, z0 + dz), planks);
            }
        }
        // Fenster
        put(level, new BlockPos(x0, y + 1, z0 + 2), Blocks.GLASS_PANE.defaultBlockState());
        put(level, new BlockPos(x0 + 4, y + 1, z0 + 2), Blocks.GLASS_PANE.defaultBlockState());
        put(level, new BlockPos(x0 + 2, y + 1, z0), Blocks.GLASS_PANE.defaultBlockState());
        // Tür im Süden (z0 + 4)
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        put(level, new BlockPos(x0 + 2, y, z0 + 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(level, new BlockPos(x0 + 2, y + 1, z0 + 4), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // Bett (Fuß unten, Kopf nach Norden)
        BlockPos foot = new BlockPos(x0 + 1, y, z0 + 2);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        put(level, foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        put(level, foot.north(), bed.setValue(BedBlock.PART, BedPart.HEAD));
        // Licht
        put(level, new BlockPos(x0 + 3, y, z0 + 1), Blocks.TORCH.defaultBlockState());
    }
}
