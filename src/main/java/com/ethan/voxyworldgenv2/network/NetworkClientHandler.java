package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;

public class NetworkClientHandler {
    
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HandshakePayload.TYPE, (payload, context) -> {
            boolean serverHasMod = payload.serverHasMod();
            context.client().execute(() -> {
                NetworkState.setServerConnected(serverHasMod);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LODDataPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> {
                handleLODData(payload);
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void handleLODData(NetworkHandler.LODDataPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        // discard LOD data from a different dimension to prevent cross-dimension rendering artifacts (issue #43)
        if (!level.dimension().equals(payload.dimension())) return;
        
        // calculate approximate payload size
        long bytes = 0;
        for (NetworkHandler.LODDataPayload.SectionData sd : payload.sections()) {
            bytes += sd.states().length;
            bytes += sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        for (NetworkHandler.LODDataPayload.SectionData sectionData : payload.sections()) {
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // recreate section
                LevelChunkSection section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
                
                // we need to read the states and biomes back using RegistryFriendlyByteBuf for palette consistency
                net.minecraft.network.RegistryFriendlyByteBuf statesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(statesRaw), 
                    level.registryAccess()
                );
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);
                
                net.minecraft.network.RegistryFriendlyByteBuf biomesBuf = new net.minecraft.network.RegistryFriendlyByteBuf(
                    new net.minecraft.network.FriendlyByteBuf(biomesRaw), 
                    level.registryAccess()
                );
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);
                
                // ingest into voxy
                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;
                
                VoxyIntegration.rawIngest(level, section, payload.pos().x, sectionData.y(), payload.pos().z, bl, sl);
                
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("failed to handle LOD data for chunk " + payload.pos(), e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }
}
