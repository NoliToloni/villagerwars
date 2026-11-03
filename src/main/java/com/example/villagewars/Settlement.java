   package com.example.villagewars;

   import net.minecraft.core.BlockPos;
   import net.minecraft.nbt.CompoundTag;

   import java.util.UUID;

   /** Ein abstrakt simuliertes Dorf mit Wirtschaft und Armee. */
   public class Settlement {
       public static final int MAX_SOLDIERS = 40;

       public final UUID id;
       public String name;
       public BlockPos center;
       public int colorIndex;

       public double food = 60, wood = 20, iron = 10, gold = 3;
       public int population;
       public int soldiers;
       public long lastWarTick = -100000;

       public Settlement(UUID id, String name, BlockPos center, int population, int colorIndex) {
           this.id = id;
           this.name = name;
           this.center = center;
           this.population = population;
           this.colorIndex = colorIndex;
       }

       public String teamName() {
           return "vw_" + id.toString().substring(0, 8);
       }

       /** Kampfkraft: Soldaten, leicht verstärkt durch Eisenvorrat (bessere Ausrüstung). */
       public double strength() {
           return soldiers * (1.0 + Math.min(iron, 200) / 200.0);
       }

       public CompoundTag save() {
           CompoundTag t = new CompoundTag();
           t.putUUID("id", id);
           t.putString("name", name);
           t.putLong("pos", center.asLong());
           t.putInt("color", colorIndex);
           t.putDouble("food", food);
           t.putDouble("wood", wood);
           t.putDouble("iron", iron);
           t.putDouble("gold", gold);
           t.putInt("pop", population);
           t.putInt("soldiers", soldiers);
           t.putLong("lastWar", lastWarTick);
           return t;
       }

       public static Settlement load(CompoundTag t) {
           Settlement s = new Settlement(t.getUUID("id"), t.getString("name"),
                   BlockPos.of(t.getLong("pos")), t.getInt("pop"), t.getInt("color"));
           s.food = t.getDouble("food");
           s.wood = t.getDouble("wood");
           s.iron = t.getDouble("iron");
           s.gold = t.getDouble("gold");
           s.soldiers = t.getInt("soldiers");
           s.lastWarTick = t.getLong("lastWar");
           return s;
       }
   }
