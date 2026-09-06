package me.cortex.voxy.commonImpl.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side knobs of the LOD resync protocol, stored in {@code config/voxy-lod-resync.json}.
 * <p>
 * Read on both dists at {@code ServerStartingEvent} (so the integrated server gets the same behaviour as a dedicated
 * one). This is the version that is in production on the RMN server (recovered from the deployed
 * {@code voxy-0.2.9-alpha-neoforge-server.jar}): compared to the earlier source snapshot it adds
 * {@link Data#floorChunksPerTick} (budget used on ticks that are already behind) and
 * {@link Data#generateMissingChunks} (whether never-generated chunks may be generated just to re-serve them).
 * Uses only Gson 2.10.1 APIs (Minecraft 1.21.1 ships Gson 2.10.1).
 */
public final class LodResyncConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Data DATA = new Data();

    private LodResyncConfig() {}

    public static Data get() {
        return DATA;
    }

    private static Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-lod-resync.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                Data d;
                try (Reader r = Files.newBufferedReader(p)) {
                    d = GSON.fromJson(r, Data.class);
                }
                if (d == null) {
                    Logger.error("voxy-lod-resync.json was unreadable; using defaults");
                    DATA = new Data();
                } else {
                    DATA = sanitize(d);
                }
                return;
            }
            DATA = new Data();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(DATA));
        } catch (RuntimeException | IOException e) {
            Logger.error("Could not load voxy-lod-resync.json; using defaults", e);
            DATA = new Data();
        }
    }

    private static Data sanitize(Data d) {
        d.chunksPerTick = clamp(d.chunksPerTick, 1, 64);
        d.floorChunksPerTick = clamp(d.floorChunksPerTick, 1, d.chunksPerTick);
        d.maxConcurrentLoads = clamp(d.maxConcurrentLoads, 1, 16);
        d.maxPendingPerPlayer = clamp(d.maxPendingPerPlayer, 32, 4096);
        return d;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static final class Data {
        /** Master switch for the server half of the protocol. */
        public boolean enabled = true;
        /** Chunk positions re-served per server tick (shared across all requesting players) when the tick has time left. */
        public int chunksPerTick = 8;
        /** Budget used instead of {@link #chunksPerTick} on ticks that are already behind schedule. */
        public int floorChunksPerTick = 1;
        /** Maximum number of concurrent asynchronous chunk loads triggered by resync requests. */
        public int maxConcurrentLoads = 4;
        /** Maximum queued request positions per player; further positions in a packet are dropped once reached. */
        public int maxPendingPerPlayer = 256;
        /** Whether chunks that are not currently loaded may be loaded (from disk) to be re-served. */
        public boolean loadUnloadedChunks = true;
        /** Whether chunks that were never generated may be generated just to re-serve them (expensive). */
        public boolean generateMissingChunks = false;
    }
}
