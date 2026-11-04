package com.example.villagewars;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Netzwerk: Client schickt Aktionen, Server antwortet mit dem Dorfzustand. */
public class Net {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VillageWars.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void init() {
        CHANNEL.registerMessage(0, ActionMsg.class, ActionMsg::encode, ActionMsg::decode, ActionMsg::handle);
        CHANNEL.registerMessage(1, InfoMsg.class, InfoMsg::encode, InfoMsg::decode, InfoMsg::handle);
    }

    public static void sendInfo(ServerPlayer p, Actions.Result r) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), InfoMsg.build(p, r));
    }

    // ------------------------------------------------------------------ Client -> Server

    /** action 0 = nur Menü öffnen / aktualisieren. */
    public static class ActionMsg {
        public final int action;

        public ActionMsg(int action) {
            this.action = action;
        }

        public static void encode(ActionMsg m, FriendlyByteBuf b) {
            b.writeVarInt(m.action);
        }

        public static ActionMsg decode(FriendlyByteBuf b) {
            return new ActionMsg(b.readVarInt());
        }

        public static void handle(ActionMsg m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer p = ctx.get().getSender();
                if (p == null) return;
                Actions.Result r = m.action == 0 ? null : Actions.run(p, m.action);
                sendInfo(p, r);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ Server -> Client

    public static class Neighbour {
        public String name = "";
        public int rel, dist;
        public boolean war;
    }

    public static class InfoMsg {
        public boolean found;
        public String name = "";
        public int color, population, soldiers, tier;
        public int food, wood, iron, gold;
        public int rep;
        public String owner = "";
        public boolean mine, war;
        public String feedback = "";
        public boolean ok;
        public final List<Neighbour> neighbours = new ArrayList<>();

        public static InfoMsg build(ServerPlayer p, Actions.Result r) {
            SettlementData d = SettlementData.get(p.getServer().overworld());
            Settlement s = Simulation.nearest(d, p.blockPosition(), Actions.RANGE);
            InfoMsg m = new InfoMsg();
            if (r != null) {
                m.feedback = r.msg();
                m.ok = r.ok();
            }
            if (s == null) return m;

            m.found = true;
            m.name = s.name;
            m.color = s.colorIndex;
            m.population = s.population;
            m.soldiers = s.soldiers;
            m.tier = Equipment.tier(s);
            m.food = (int) s.food;
            m.wood = (int) s.wood;
            m.iron = (int) s.iron;
            m.gold = (int) s.gold;
            m.rep = d.getRep(p.getUUID(), s);
            m.owner = s.owner == null ? "" : s.ownerName;
            m.mine = s.owner != null && s.owner.equals(p.getUUID());
            m.war = d.atWar(s);

            for (Settlement o : d.settlements) {
                if (o == s) continue;
                double dist = Math.sqrt(Simulation.horizDistSq(s.center, o.center));
                if (dist > Simulation.WAR_RANGE) continue;
                Neighbour n = new Neighbour();
                n.name = o.name;
                n.rel = d.getRelation(s, o);
                n.dist = (int) dist;
                for (Expedition e : d.expeditions) {
                    if ((e.from.equals(s.id) && e.to.equals(o.id)) || (e.from.equals(o.id) && e.to.equals(s.id))) n.war = true;
                }
                m.neighbours.add(n);
            }
            m.neighbours.sort((a, b) -> Integer.compare(a.dist, b.dist));
            while (m.neighbours.size() > 4) m.neighbours.remove(m.neighbours.size() - 1);
            return m;
        }

        public static void encode(InfoMsg m, FriendlyByteBuf b) {
            b.writeBoolean(m.found);
            b.writeUtf(m.name);
            b.writeVarInt(m.color);
            b.writeVarInt(m.population);
            b.writeVarInt(m.soldiers);
            b.writeVarInt(m.tier);
            b.writeVarInt(m.food);
            b.writeVarInt(m.wood);
            b.writeVarInt(m.iron);
            b.writeVarInt(m.gold);
            b.writeInt(m.rep);
            b.writeUtf(m.owner);
            b.writeBoolean(m.mine);
            b.writeBoolean(m.war);
            b.writeUtf(m.feedback);
            b.writeBoolean(m.ok);
            b.writeVarInt(m.neighbours.size());
            for (Neighbour n : m.neighbours) {
                b.writeUtf(n.name);
                b.writeInt(n.rel);
                b.writeVarInt(n.dist);
                b.writeBoolean(n.war);
            }
        }

        public static InfoMsg decode(FriendlyByteBuf b) {
            InfoMsg m = new InfoMsg();
            m.found = b.readBoolean();
            m.name = b.readUtf();
            m.color = b.readVarInt();
            m.population = b.readVarInt();
            m.soldiers = b.readVarInt();
            m.tier = b.readVarInt();
            m.food = b.readVarInt();
            m.wood = b.readVarInt();
            m.iron = b.readVarInt();
            m.gold = b.readVarInt();
            m.rep = b.readInt();
            m.owner = b.readUtf();
            m.mine = b.readBoolean();
            m.war = b.readBoolean();
            m.feedback = b.readUtf();
            m.ok = b.readBoolean();
            int n = b.readVarInt();
            for (int i = 0; i < n; i++) {
                Neighbour nb = new Neighbour();
                nb.name = b.readUtf();
                nb.rel = b.readInt();
                nb.dist = b.readVarInt();
                nb.war = b.readBoolean();
                m.neighbours.add(nb);
            }
            return m;
        }

        public static void handle(InfoMsg m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.villagewars.client.ClientHooks.show(m)));
            ctx.get().setPacketHandled(true);
        }
    }
}
