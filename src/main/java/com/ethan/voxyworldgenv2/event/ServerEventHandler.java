package com.ethan.voxyworldgenv2.event;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ServerEventHandler {
    private ServerEventHandler() {}
    
    public static void onServerStarted(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server started, initializing manager");
        ChunkGenerationManager.getInstance().initialize(server);
    }
    
    public static void onServerStopping(MinecraftServer server) {
        VoxyWorldGenV2.LOGGER.info("server stopping, shutting down manager");
        ChunkGenerationManager.getInstance().shutdown();
        PlayerTracker.getInstance().clear();
    }
    
    public static void onPlayerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        PlayerTracker.getInstance().addPlayer(handler.getPlayer());
        com.ethan.voxyworldgenv2.network.NetworkHandler.sendHandshake(handler.getPlayer());
    }
    
    public static void onPlayerDisconnect(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        PlayerTracker.getInstance().removePlayer(handler.getPlayer());
    }
    
    public static void onServerTick(MinecraftServer server) {
        ChunkGenerationManager.getInstance().tick();
    }

    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        // re-ingest the freshly-loaded chunk so Voxy receives biome data with correct
        // neighbor context (fixes hard snow/biome blend edges on new worlds, issue #40).
        // also handles syncing pre-generated chunks that couldn't be sent at generation
        // time because the player wasn't loaded yet (issue #50).
        ChunkGenerationManager.getInstance().markChunkCompletedFromLoad(level, chunk);
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            if (player.level() == level) {
                NetworkHandler.sendLODData(player, chunk);
            }
        }
    }
}
