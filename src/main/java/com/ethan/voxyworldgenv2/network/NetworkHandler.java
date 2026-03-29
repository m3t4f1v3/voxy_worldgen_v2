package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;

public class NetworkHandler {
    public static final ResourceLocation HANDSHAKE_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":handshake");
    public static final ResourceLocation LOD_DATA_ID = ResourceLocation.parse(VoxyWorldGenV2.MOD_ID + ":lod_data");

    public record HandshakePayload(boolean serverHasMod) implements CustomPacketPayload {
        public static final Type<HandshakePayload> TYPE = new Type<>(HANDSHAKE_ID);
        public static final StreamCodec<FriendlyByteBuf, HandshakePayload> CODEC = CustomPacketPayload.codec(HandshakePayload::write, HandshakePayload::new);

        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LODDataPayload(ChunkPos pos, int minY, List<SectionData> sections) implements CustomPacketPayload {
        public static final Type<LODDataPayload> TYPE = new Type<>(LOD_DATA_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, LODDataPayload> CODEC = CustomPacketPayload.codec(LODDataPayload::write, LODDataPayload::new);

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(RegistryFriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }

            public static SectionData read(RegistryFriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(), 
                    buf.readByteArray(), 
                    buf.readByteArray(), 
                    buf.readNullable(b -> b.readByteArray()), 
                    buf.readNullable(b -> b.readByteArray())
                );
            }
        }

        public LODDataPayload(RegistryFriendlyByteBuf buf) {
            this(buf.readChunkPos(), buf.readInt(), buf.readCollection(ArrayList::new, b -> SectionData.read((RegistryFriendlyByteBuf) b)));
        }

        public void write(RegistryFriendlyByteBuf buf) {
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((RegistryFriendlyByteBuf) b));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HandshakePayload.TYPE, HandshakePayload.CODEC);
        
        PayloadTypeRegistry.playS2C().register(LODDataPayload.TYPE, LODDataPayload.CODEC);
        
        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    private static void setSyncedState(ServerPlayer player, ChunkPos pos, boolean isSynced) {
        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
        if (synced != null) {
            if (isSynced) {
                synced.add(pos.toLong());
            } else {
                synced.remove(pos.toLong());
            }
        }
    }

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        
        var lightEngine = chunk.getLevel().getLightEngine();
        
        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;
            
            // serialize section
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            byte[] states, biomes;
            try {
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);
                
                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
            
            // light
            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);
            
            sections.add(new LODDataPayload.SectionData(
                minY + i, 
                states, 
                biomes, 
                bl != null ? bl.getData().clone() : null, 
                sl != null ? sl.getData().clone() : null
            ));
        }
        
        if (sections.isEmpty()) return;

        LODDataPayload payload = new LODDataPayload(pos, minY, sections);
        
        double maxDistSq = 4096.0 * 4096.0;
        
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());
            
            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, pos, false);
                continue;
            }
            
            ServerPlayNetworking.send(player, payload);
                
            // // mark as synced for this player
            // var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
            // if (synced != null) {
            //     synced.add(pos.toLong());
            // }
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinSection();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        
        var lightEngine = chunk.getLevel().getLightEngine();
        
        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;
            
            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            byte[] states, biomes;
            try {
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(statesRaw), chunk.getLevel().registryAccess());
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);
                
                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(new FriendlyByteBuf(biomesRaw), chunk.getLevel().registryAccess());
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
            
            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);
            
            sections.add(new LODDataPayload.SectionData(
                minY + i, 
                states, 
                biomes, 
                bl != null ? bl.getData().clone() : null, 
                sl != null ? sl.getData().clone() : null
            ));
        }
        
        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }
        
        ServerPlayNetworking.send(player, new LODDataPayload(pos, minY, sections));
        // double check, could be deleted.
        setSyncedState(player, pos, true);
    }

    public static void sendHandshake(ServerPlayer player) {
        ServerPlayNetworking.send(player, new HandshakePayload(true));
    }
}
