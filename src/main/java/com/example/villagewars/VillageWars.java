   package com.example.villagewars;

   import net.minecraftforge.common.MinecraftForge;
   import net.minecraftforge.fml.common.Mod;

   @Mod(VillageWars.MODID)
   public class VillageWars {
       public static final String MODID = "villagewars";

       public VillageWars() {
           Net.init();
           MinecraftForge.EVENT_BUS.register(new Simulation());
           MinecraftForge.EVENT_BUS.register(new WarCommands());
           MinecraftForge.EVENT_BUS.register(new PlayerCommands());
       }
   }
