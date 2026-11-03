   package com.example.villagewars;

   import net.minecraft.nbt.CompoundTag;

   import java.util.ArrayList;
   import java.util.List;
   import java.util.UUID;

   /** Ein Feldzug von einem Dorf zu einem anderen. */
   public class Expedition {
       public final UUID id;
       public final UUID from;
       public final UUID to;
       public int attackers;          // abstrakte Anzahl der ausgesandten Soldaten
       public final long startTick;   // Spielzeit (Level#getGameTime)
       public final long travelTicks;

       // --- nur zur Laufzeit, wird nicht gespeichert ---
       public boolean real;
       public final List<UUID> atkEntities = new ArrayList<>();
       public final List<UUID> defEntities = new ArrayList<>();
       public int defendersDeployed;
       public long lastPlayerSeen;

       public Expedition(UUID id, UUID from, UUID to, int attackers, long startTick, long travelTicks) {
           this.id = id;
           this.from = from;
           this.to = to;
           this.attackers = attackers;
           this.startTick = startTick;
           this.travelTicks = travelTicks;
       }

       public CompoundTag save() {
           CompoundTag t = new CompoundTag();
           t.putUUID("id", id);
           t.putUUID("from", from);
           t.putUUID("to", to);
           t.putInt("att", attackers);
           t.putLong("start", startTick);
           t.putLong("travel", travelTicks);
           return t;
       }

       public static Expedition load(CompoundTag t) {
           return new Expedition(t.getUUID("id"), t.getUUID("from"), t.getUUID("to"),
                   t.getInt("att"), t.getLong("start"), t.getLong("travel"));
       }
   }
