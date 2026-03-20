package com.ethan.voxyworldgenv2;

import com.ethan.voxyworldgenv2.event.ServerEventHandler;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyWorldGenV2 implements ModInitializer {
    public static final String MOD_ID = "voxyworldgenv2";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("voxy world gen v2 initializing");
        com.ethan.voxyworldgenv2.core.Config.load();
        NetworkHandler.init();
        
        // server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(ServerEventHandler::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerEventHandler::onServerStopping);
        
        // player connection events
        ServerPlayConnectionEvents.JOIN.register(ServerEventHandler::onPlayerJoin);
        ServerPlayConnectionEvents.DISCONNECT.register(ServerEventHandler::onPlayerDisconnect);
        
        // server tick event
        ServerTickEvents.END_SERVER_TICK.register(ServerEventHandler::onServerTick);
    }
}
