package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.PlayerTracker;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class NetworkHandler {
    public static final ResourceLocation HANDSHAKE_ID = ResourceLocation.tryParse(VoxyWorldGenV2.MOD_ID + ":handshake");
    public static final ResourceLocation LOD_DATA_ID = ResourceLocation.tryParse(VoxyWorldGenV2.MOD_ID + ":lod_data");

    // keep individual packets well under Netty's 2MB limit to prevent connection resets on public servers
    private static final int MAX_PACKET_BYTES = 32_768;

    public record HandshakePayload(boolean serverHasMod) {
        public HandshakePayload(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.serverHasMod);
        }
    }

    public record LODDataPayload(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) {

        public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
            public void write(FriendlyByteBuf buf) {
                buf.writeInt(y);
                buf.writeByteArray(states);
                buf.writeByteArray(biomes);
                buf.writeNullable(blockLight, (b, a) -> b.writeByteArray(a));
                buf.writeNullable(skyLight, (b, a) -> b.writeByteArray(a));
            }
            public static SectionData read(FriendlyByteBuf buf) {
                return new SectionData(
                    buf.readInt(),
                    buf.readByteArray(),
                    buf.readByteArray(),
                    buf.readNullable(b -> b.readByteArray()),
                    buf.readNullable(b -> b.readByteArray())
                );
            }
        }

        public LODDataPayload(FriendlyByteBuf buf) {
            this(
                ResourceKey.create(Registries.DIMENSION, Objects.requireNonNull(ResourceLocation.tryParse(buf.readUtf()), "invalid dimension resource location in LOD payload")),
                buf.readChunkPos(),
                buf.readInt(),
                buf.readCollection(ArrayList::new, b -> SectionData.read((FriendlyByteBuf) b))
            );
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(dimension.location().toString());
            buf.writeChunkPos(pos);
            buf.writeInt(minY);
            // cast to avoid ambiguous writeCollection / BiConsumer type issues
            buf.writeCollection(sections, (b, s) -> s.write((FriendlyByteBuf) b));
        }

    }

    public static void init() {
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
        int minY = chunk.getMinBuildHeight();
        List<LODDataPayload.SectionData> sections = buildSections(chunk);
        if (sections.isEmpty()) return;

        double maxDistSq = 4096.0 * 4096.0;

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.level() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, pos, false);
                continue;
            }

            sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight();
        List<LODDataPayload.SectionData> sections = buildSections(chunk);

        if (sections.isEmpty()) {
            setSyncedState(player, pos, false);
            return;
        }

        sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
        setSyncedState(player, pos, true);
    }

    private static List<LODDataPayload.SectionData> buildSections(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight();
        List<LODDataPayload.SectionData> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;

            io.netty.buffer.ByteBuf statesRaw = io.netty.buffer.Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = io.netty.buffer.Unpooled.buffer();
            byte[] states, biomes;
            try {
                FriendlyByteBuf statesBuf = new FriendlyByteBuf(statesRaw);
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                FriendlyByteBuf biomesBuf = new FriendlyByteBuf(biomesRaw);
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

        return sections;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<LODDataPayload.SectionData> sections) {
        List<LODDataPayload.SectionData> batch = new ArrayList<>();
        int batchBytes = 0;

        for (LODDataPayload.SectionData sd : sections) {
            int sectionBytes = sd.states().length + sd.biomes().length
                + (sd.blockLight() != null ? sd.blockLight().length : 0)
                + (sd.skyLight() != null ? sd.skyLight().length : 0);

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                sendLODDataPayload(player, new LODDataPayload(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            sendLODDataPayload(player, new LODDataPayload(dimension, pos, minY, batch));
        }
    }

    private static void sendLODDataPayload(ServerPlayer player, LODDataPayload payload) {
        ByteBuf outRaw = Unpooled.buffer();
        try {
            FriendlyByteBuf outFb = new FriendlyByteBuf(outRaw);
            payload.write(outFb);
            ServerPlayNetworking.send(player, LOD_DATA_ID, new FriendlyByteBuf(outRaw.retainedDuplicate()));
        } finally {
            outRaw.release();
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        ByteBuf outRaw = Unpooled.buffer();
        try {
            FriendlyByteBuf outFb = new FriendlyByteBuf(outRaw);
            outFb.writeBoolean(true);
            ServerPlayNetworking.send(player, HANDSHAKE_ID, new FriendlyByteBuf(outRaw.retainedDuplicate()));
        } finally {
            outRaw.release();
        }
    }
}
