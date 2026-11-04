package com.example.villagewars.client;

import com.example.villagewars.Net;
import net.minecraft.client.Minecraft;

/** Wird nur auf dem Client aufgerufen. */
public class ClientHooks {
    public static void show(Net.InfoMsg m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof VillageScreen vs) vs.update(m);
        else mc.setScreen(new VillageScreen(m));
    }
}
