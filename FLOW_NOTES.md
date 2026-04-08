# Voxy World Gen V2 Flow Notes

Source of truth for this note:
- Current repo code in `src/main/java/com/ethan/voxyworldgenv2/`
- Old server logs from `C:\Users\szabo\Downloads\nagyon_logs`

This note only records behavior that is visible in code or logs. It does not assume any hidden runtime behavior.

## Observed startup flow

1. Mod initialization runs in [`VoxyWorldGenV2`](src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2.java).
2. The mod logs `voxy world gen v2 initializing` and initializes networking.
3. Server lifecycle handlers are registered:
   - server started
   - server stopping
   - player join
   - player disconnect
   - server tick
   - chunk load
4. In the old server logs, this sequence appears:
   - `voxy world gen v2 initializing`
   - `voxy networking initialized`
   - `server started, initializing manager`
   - `voxy world gen initialized`
   - `loaded 0 chunks from voxy generation cache for ResourceKey[minecraft:dimension / minecraft:overworld]`
   - `tellus integration hub initialized (2 workers)`

## Observed server-side state

- Player tracking is managed by [`PlayerTracker`](src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java).
- Completed chunk positions are stored per dimension in [`ChunkGenerationManager`](src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java).
- Persistence reads and writes completed chunk positions through [`ChunkPersistence`](src/main/java/com/ethan/voxyworldgenv2/core/ChunkPersistence.java).

The old server logs show:
- `loaded 0 chunks from voxy generation cache` for the overworld on that run.
- A shutdown later with `server stopping, shutting down manager`.

## Observed generation flow

From [`ChunkGenerationManager`](src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java):

1. The worker loop checks whether the mod is enabled, whether the server exists, whether TPS is throttled, and whether the pause check is true.
2. It then looks at connected players from `PlayerTracker`.
3. For each player, it tries to find generation work in range using the dimension's distance graph.
4. If generation work exists:
   - chunks may be queued for generation
   - Tellus chunks use `TellusIntegration.enqueueGenerate(...)`
   - non-Tellus chunks use the server chunk pipeline
   - generated chunks are ingested into Voxy with `VoxyIntegration.ingestChunk(...)`
   - LOD data is broadcast with `NetworkHandler.broadcastLODData(...)`
5. If no generation work exists, the manager tries to catch up on syncing completed chunks to players.
6. When a completed chunk is present but not loaded, the manager queues a ticket, loads the chunk, and then sends LOD data.

## Observed chunk-load sync flow

[`ServerEventHandler.onChunkLoad`](src/main/java/com/ethan/voxyworldgenv2/event/ServerEventHandler.java) does two things when a chunk loads:

1. It records the chunk as completed in the generation manager.
2. It sends LOD data for that chunk to every tracked player in the same dimension.

That means chunk load is a separate sync path from the background generation worker.

## Observed client-side flow

From [`VoxyWorldGenV2Client`](src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2Client.java):

1. Client network handlers are registered.
2. Client disconnect resets the connection state.
3. Client tick updates received-network stats.
4. `NetworkClientHandler` accepts:
   - handshake payloads
   - LOD data payloads
5. LOD payloads are ignored if the client is in a different dimension than the payload.

## What the old logs do and do not show

Shown in logs:
- Mod initialization
- Server manager startup
- Cache load for the overworld
- Tellus integration initialization
- Server shutdown

Not shown in the log slice I checked:
- Any explicit `sendLODData` or `broadcastLODData` log line
- Any explicit worker-loop error line
- Any explicit chunk-generation progress log from this mod

That absence matters because it means the logs I inspected do not directly prove where the distant-LOD path stopped.

## Current working hypothesis

Based on code and the logs together, the flow is:

1. Server starts.
2. Manager initializes.
3. Cached completed chunks are loaded.
4. Player join adds the player to tracking and sends a handshake.
5. The background worker tries to generate chunks when it sees work in range.
6. When a chunk loads, the server also pushes LOD data to players in the same dimension.
7. Completed chunk positions are what drive later resync and persistence.

## Open questions to verify live

1. Whether the background worker is actually finding distant generation work for the affected dimension.
2. Whether chunks are being marked completed and persisted in a way that survives reconnects and multiple clients.
3. Whether the client receives the handshake but never receives the LOD payload, or receives it and discards it.

## Useful files

- [`VoxyWorldGenV2.java`](src/main/java/com/ethan/voxyworldgenv2/VoxyWorldGenV2.java)
- [`ServerEventHandler.java`](src/main/java/com/ethan/voxyworldgenv2/event/ServerEventHandler.java)
- [`ChunkGenerationManager.java`](src/main/java/com/ethan/voxyworldgenv2/core/ChunkGenerationManager.java)
- [`ChunkPersistence.java`](src/main/java/com/ethan/voxyworldgenv2/core/ChunkPersistence.java)
- [`NetworkHandler.java`](src/main/java/com/ethan/voxyworldgenv2/network/NetworkHandler.java)
- [`NetworkClientHandler.java`](src/main/java/com/ethan/voxyworldgenv2/network/NetworkClientHandler.java)
- [`PlayerTracker.java`](src/main/java/com/ethan/voxyworldgenv2/core/PlayerTracker.java)
