package com.example.villagewars;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/** Pro Welt gespeicherte Dörfer, Beziehungen und laufende (abstrakte) Feldzüge. */
public class SettlementData extends SavedData {
    private static final String NAME = "villagewars_data";

    public final List<Settlement> settlements = new ArrayList<>();
    /** Beziehung -100 (Todfeind) bis +100 (Freunde), Schlüssel "idA|idB" sortiert. */
    public final Map<String, Integer> relations = new HashMap<>();
    /** Ruf der Spieler bei Dörfern, Schlüssel "spielerUUID|dorfUUID". */
    public final Map<String, Integer> reputation = new HashMap<>();
    public final List<Expedition> expeditions = new ArrayList<>();

    public static SettlementData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SettlementData::load, SettlementData::new, NAME);
    }

    public Settlement byId(UUID id) {
        for (Settlement s : settlements) if (s.id.equals(id)) return s;
        return null;
    }

    private static String key(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    public int getRelation(Settlement a, Settlement b) {
        return relations.getOrDefault(key(a.id, b.id), 0);
    }

    public void setRelation(Settlement a, Settlement b, int v) {
        relations.put(key(a.id, b.id), Math.max(-100, Math.min(100, v)));
        setDirty();
    }

    public void addRelation(Settlement a, Settlement b, int delta) {
        setRelation(a, b, getRelation(a, b) + delta);
    }

    public int getRep(UUID player, Settlement s) {
        return reputation.getOrDefault(player + "|" + s.id, 0);
    }

    public void setRep(UUID player, Settlement s, int v) {
        reputation.put(player + "|" + s.id, Math.max(-100, Math.min(100, v)));
        setDirty();
    }

    public void addRep(UUID player, Settlement s, int delta) {
        setRep(player, s, getRep(player, s) + delta);
    }

    public boolean atWar(Settlement s) {
        for (Expedition e : expeditions) if (e.from.equals(s.id) || e.to.equals(s.id)) return true;
        return false;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Settlement s : settlements) list.add(s.save());
        tag.put("settlements", list);

        CompoundTag rel = new CompoundTag();
        relations.forEach(rel::putInt);
        tag.put("relations", rel);

        CompoundTag rep = new CompoundTag();
        reputation.forEach(rep::putInt);
        tag.put("reputation", rep);

        ListTag ex = new ListTag();
        for (Expedition e : expeditions) ex.add(e.save());
        tag.put("expeditions", ex);
        return tag;
    }

    public static SettlementData load(CompoundTag tag) {
        SettlementData d = new SettlementData();
        for (Tag t : tag.getList("settlements", Tag.TAG_COMPOUND)) d.settlements.add(Settlement.load((CompoundTag) t));
        CompoundTag rel = tag.getCompound("relations");
        for (String k : rel.getAllKeys()) d.relations.put(k, rel.getInt(k));
        CompoundTag rep = tag.getCompound("reputation");
        for (String k : rep.getAllKeys()) d.reputation.put(k, rep.getInt(k));
        for (Tag t : tag.getList("expeditions", Tag.TAG_COMPOUND)) d.expeditions.add(Expedition.load((CompoundTag) t));
        return d;
    }
}
