package com.example.villagewars.client;

import com.example.villagewars.Net;
import com.example.villagewars.VillageWars;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Taste V öffnet das Dorf-Menü. */
public class ClientSetup {
    public static final KeyMapping OPEN_KEY = new KeyMapping("key.villagewars.open", GLFW.GLFW_KEY_V, "key.categories.misc");

    @Mod.EventBusSubscriber(modid = VillageWars.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEvents {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent e) {
            e.register(OPEN_KEY);
        }
    }

    @Mod.EventBusSubscriber(modid = VillageWars.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null) Net.CHANNEL.sendToServer(new Net.ActionMsg(0));
            }
        }
    }
}
