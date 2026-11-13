package com.skepi.bridgeessentials;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
        modid = BridgeEssentials.MODID,
        name = BridgeEssentials.NAME,
        version = BridgeEssentials.VERSION,
        clientSideOnly = true,
        acceptedMinecraftVersions = "[1.8.9]"
)
public class BridgeEssentials {
    public static final String MODID = "bridgeessentials";
    public static final String NAME = "BridgeEssentials";
    public static final String VERSION = "1.0";

    /** Opens the settings menu (rebindable under Options > Controls > BridgeEssentials). */
    public static final KeyBinding openSettingsKey =
            new KeyBinding("Open Settings", Keyboard.KEY_RSHIFT, NAME);

    /** The running tracker; the settings menu uses it to draw a live HUD preview. */
    static BridgeEssentialsListener tracker;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        BridgeConfig.load(event.getSuggestedConfigurationFile());
        // Personal bests live next to the config: <minecraft dir>/config/bridgeessentials-bests.properties
        BridgeBests.load(new java.io.File(event.getModConfigurationDirectory(),
                "bridgeessentials-bests.properties"));
        // <minecraft dir>/config -> <minecraft dir>; the log goes to <minecraft dir>/logs.
        BridgeLog.init(
                event.getModConfigurationDirectory().getParentFile(),
                BridgeConfig.isDebugLogEnabled(),
                BridgeConfig.isDebugLogPerTickEnabled(),
                VERSION
        );
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(openSettingsKey);

        tracker = new BridgeEssentialsListener();
        FMLCommonHandler.instance().bus().register(tracker);
        FMLCommonHandler.instance().bus().register(new BridgeKeyHandler());
        MinecraftForge.EVENT_BUS.register(new BridgeEssentialsForgeListener(tracker));
    }
}
