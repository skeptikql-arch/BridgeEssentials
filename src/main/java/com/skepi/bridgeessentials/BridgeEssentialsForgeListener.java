package com.skepi.bridgeessentials;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Forge-bus events are bridged to the tracker; client ticks stay on the FML bus. */
public class BridgeEssentialsForgeListener {
    private final BridgeEssentialsListener tracker;

    public BridgeEssentialsForgeListener(BridgeEssentialsListener tracker) {
        this.tracker = tracker;
    }

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.PlaceEvent event) {
        tracker.onBlockPlaced(event);
    }

    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        tracker.onPlayerInteract(event);
    }

    @SubscribeEvent
    public void onLivingJump(LivingEvent.LivingJumpEvent event) {
        tracker.onPlayerJump(event.entity);
    }

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            // The settings menu draws its own live preview of the HUD.
            if (Minecraft.getMinecraft().currentScreen instanceof BridgeSettingsGui) {
                return;
            }
            tracker.renderHud();
        }
    }
}
