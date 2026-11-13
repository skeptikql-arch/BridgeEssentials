package com.skepi.bridgeessentials;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Opens the settings menu when its keybind is pressed while no other screen is open. */
public class BridgeKeyHandler {
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // Drain queued presses every tick so ones made inside other screens never pile up.
        boolean pressed = false;
        while (BridgeEssentials.openSettingsKey.isPressed()) {
            pressed = true;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (pressed && minecraft.currentScreen == null && minecraft.thePlayer != null) {
            minecraft.displayGuiScreen(new BridgeSettingsGui());
        }
    }
}
