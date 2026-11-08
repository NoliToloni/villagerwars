package com.example.villagewars;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Nur noch ein Notbehelf: /villagewars menu öffnet das Menü (normal: Taste V). */
public class PlayerCommands {
    @SubscribeEvent
    public void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("villagewars")
                .then(Commands.literal("menu").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Net.sendInfo(p, null);
                    return 1;
                })));
    }
}
