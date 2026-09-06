package me.cortex.voxy.commonImpl.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Voxy's LOD resync protocol: it closes holes left by voxyworldgenv2's delivery path.
 *
 * <h2>What actually drops chunks in voxyworldgenv2 2.4.2 (verified against the
 * decompiled jar, not assumed)</h2>
 * {@code NetworkHandler.sendLODData} commits {@code setSyncedState(player, pos, true)}
 * and only THEN hands the payload to {@code sendAsync}, whose pool task re-tests
 * {@code stillRelevant(player, dim, pos)} — a fresh {@code syncRadiusSq} distance check
 * against the player's CURRENT position. A player who moves between those two moments
 * gets nothing sent, while the chunk stays in {@code PlayerTracker.syncedChunks}. That
 * set is an exclusion set for the mod's own backfill sweep, and
 * {@code pruneSyncedChunks()} only drops entries beyond {@code generationRadius * 2}
 * chunks, so a nearby lost chunk is never retried: a permanent hole. (The pool's
 * rejection policy is {@code CallerRunsPolicy}, so the swallowed
 * {@code RejectedExecutionException} in {@code sendAsync} fires only at shutdown — it is
 * NOT the drop path, despite being the obvious-looking candidate.)
 *
 * <h2>Protocol</h2>
 * S2C {@code ready(radiusChunks, serveRatePerSecond)}: sent on login / dimension change
 * when this server also runs voxyworldgenv2. {@code radiusChunks} is that mod's synced
 * {@code generationRadius} in CHUNKS; {@code serveRatePerSecond} is the server's actual
 * re-serve throughput so the client can pace itself instead of over-running the queue.
 * Radius 0, or never receiving this, leaves the client verifier idle.
 * <p>
 * C2S {@code request(chunkPositions)}: {@code ChunkPos.toLong()} values the client's Voxy
 * database is missing. The server re-serves them through voxyworldgenv2's own public
 * {@code NetworkHandler.sendLODData}, budgeted per tick.
 */
public final class LodResyncPayloads {
    private LodResyncPayloads() {}

    public record ResyncReady(int radiusChunks, int serveRatePerSecond) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ResyncReady> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "lod_resync_ready"));
        public static final StreamCodec<FriendlyByteBuf, ResyncReady> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeVarInt(p.radiusChunks); buf.writeVarInt(p.serveRatePerSecond); },
                buf -> new ResyncReady(buf.readVarInt(), buf.readVarInt()));
        @Override public CustomPacketPayload.Type<ResyncReady> type() { return TYPE; }
    }

    public record ResyncRequest(long[] chunkPositions) implements CustomPacketPayload {
        public static final int MAX_CHUNKS_PER_REQUEST = 64;
        public static final CustomPacketPayload.Type<ResyncRequest> TYPE =
                new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "lod_resync_request"));
        public static final StreamCodec<FriendlyByteBuf, ResyncRequest> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeLongArray(p.chunkPositions),
                // Bounded at DECODE time. The no-arg readLongArray() caps only at
                // readableBytes()/8, and a custom payload may be up to
                // CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH (8 MiB) — i.e. a ~1M-element
                // long[] allocated on the netty thread from a small compressed frame. The
                // bounded overload throws DecoderException above the cap instead.
                buf -> new ResyncRequest(buf.readLongArray(null, MAX_CHUNKS_PER_REQUEST)));
        @Override public CustomPacketPayload.Type<ResyncRequest> type() { return TYPE; }
    }
}
