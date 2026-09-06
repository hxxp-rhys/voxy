package me.cortex.voxy.commonImpl.network;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import me.cortex.voxy.common.Logger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Server half of the LOD resync protocol. GAME-bus, both dists (every referenced class
 * exists on a dedicated server; on a client this also serves the integrated server).
 * Everything voxyworldgenv2-specific is reached reflectively and fails soft when that
 * mod is absent.
 *
 * <h2>Chunks are fetched asynchronously, never inline</h2>
 * {@code ServerLevel.getChunk(x, z)} resolves to
 * {@code ServerChunkCache.getChunk(x, z, FULL, true)}, which on the server thread runs
 * {@code mainThreadProcessor.managedBlock(future::isDone)}: it BLOCKS the server thread
 * until the chunk is loaded, running the full generation ladder inline when the chunk
 * does not exist. At a budget of 8/tick that is a TPS collapse, not a slowdown. So the
 * tick handler serves only chunks already resident ({@code getChunkNow}, which returns
 * null rather than loading) and hands the rest to a loader thread, where
 * {@code getChunkSource().getChunkFuture(...)} takes its off-main-thread path: it
 * schedules ticket registration onto the server thread and returns immediately, with
 * loading/generation on the chunk pipeline's own workers. The completion hops back onto
 * the server thread before touching the player or the chunk. This mirrors what
 * voxyworldgenv2 itself does for the same job.
 *
 * <h2>Rate matching</h2>
 * The server announces its real throughput in {@code ResyncReady} so the client can pace
 * itself. Without that the client's HIGH band emits ~1280 positions/s against a 160/s
 * drain, the bounded queue saturates in a few seconds, and every excess request is
 * discarded with no NACK — while the client counts those sends as attempts and
 * permanently abandons the cell. Pacing plus a dedup queue is what makes the feature
 * actually close holes rather than appear to.
 */
/*
 * NOTE (port): this is the implementation recovered from the LOD-resync build that is in production on the RMN
 * server (voxy-0.2.9-alpha-neoforge-server.jar, 2026-08-19 17:17). It supersedes the earlier source snapshot:
 * it adds a measured serve rate that is re-announced to clients when it changes materially, a periodic status
 * log, per-player round-robin scheduling with a scan cap, the floorChunksPerTick budget for ticks that are
 * behind schedule, and skipping of never-generated chunks unless generateMissingChunks is set.
 */
@EventBusSubscriber(modid = "voxy")
public final class LodResyncServer {
    private static final int MAX_CHUNK_COORD = 1875000;
    private static final int MAX_SCANNED_PER_PLAYER_PER_TICK = 256;
    private static final Map<UUID, LongLinkedOpenHashSet> PENDING = new ConcurrentHashMap<>();
    private static final Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, LongOpenHashSet> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final AtomicInteger IN_FLIGHT_COUNT = new AtomicInteger();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static volatile ExecutorService LOADER;
    private static int roundRobinCursor;
    private static final TicketType<ChunkPos> RESYNC_TICKET = TicketType.create("voxy_lod_resync", Comparator.comparingLong(ChunkPos::toLong));
    private static long lastFailLogMs;
    private static int servedThisWindow;
    private static long windowStartMs;
    private static volatile int measuredRatePerSecond;
    private static int lastAnnouncedRate;
    private static long lastStatusLogMs;
    private static final AtomicInteger NOT_GENERATED = new AtomicInteger();
    private static long lastSaturatedLogMs;
    private static volatile boolean resolved;
    private static MethodHandle sendLODData;
    private static MethodHandle inSyncRange;
    private static volatile LodResyncServer.IntSource RADIUS_SOURCE = () -> 0;

    private LodResyncServer() {
    }

    private static void measureRate() {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0L) {
            windowStartMs = now;
        } else {
            long elapsed = now - windowStartMs;
            if (elapsed >= 5000L) {
                int rate = (int)((long)servedThisWindow * 1000L / elapsed);
                statusLog(now, rate);
                servedThisWindow = 0;
                windowStartMs = now;
                if (!PENDING.isEmpty() || rate != 0) {
                    measuredRatePerSecond = rate;
                    maybeReannounceRate();
                }
            }
        }
    }

    private static void statusLog(long now, int rate) {
        int queued = 0;

        for (LongLinkedOpenHashSet q : PENDING.values()) {
            synchronized (q) {
                queued += q.size();
            }
        }

        if (queued != 0 || servedThisWindow != 0) {
            if (now - lastStatusLogMs >= 30000L) {
                lastStatusLogMs = now;
                Logger.info(
                        "LOD resync: served "
                            + rate
                            + " chunks/s, "
                            + queued
                            + " queued across "
                            + PENDING.size()
                            + " player(s), "
                            + IN_FLIGHT_COUNT.get()
                            + " loading, "
                            + NOT_GENERATED.getAndSet(0)
                            + " skipped (never generated), advertising "
                            + perClientRate()
                            + " chunks/s per client"
                );
            }
        }
    }

    private static void maybeReannounceRate() {
        int now = perClientRate();
        if (lastAnnouncedRate > 0) {
            int hi = Math.max(now, lastAnnouncedRate);
            int lo = Math.min(now, lastAnnouncedRate);
            if (hi - lo < Math.max(4, hi / 4)) {
                return;
            }
        }

        int radius = radiusChunks();
        if (radius > 0) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (ANNOUNCED.contains(p.getUUID())) {
                        announce(p, radius);
                    }
                }
            }
        }
    }

    private static void resolve() {
        if (!resolved) {
            synchronized (LodResyncServer.class) {
                if (!resolved) {
                    try {
                        if (ModList.get().isLoaded("voxyworldgenv2")) {
                            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                            Class<?> nh = Class.forName("com.ethan.voxyworldgenv2.network.NetworkHandler");
                            sendLODData = lookup.findStatic(nh, "sendLODData", MethodType.methodType(void.class, ServerPlayer.class, LevelChunk.class));
                            inSyncRange = lookup.findStatic(nh, "inSyncRange", MethodType.methodType(boolean.class, ServerPlayer.class, LevelChunk.class));
                            Class<?> cfg = Class.forName("com.ethan.voxyworldgenv2.core.Config");
                            Field dataField = cfg.getField("DATA");
                            Field radiusField = Class.forName("com.ethan.voxyworldgenv2.core.Config$ConfigData").getField("generationRadius");
                            RADIUS_SOURCE = () -> {
                                try {
                                    Object d = dataField.get(null);
                                    return d == null ? 0 : radiusField.getInt(d);
                                } catch (Throwable var3x) {
                                    return 0;
                                }
                            };
                            Logger.info("LOD resync: voxyworldgenv2 bridge resolved (radius " + RADIUS_SOURCE.get() + " chunks)");
                        } else {
                            Logger.info("LOD resync: voxyworldgenv2 not present — resync inactive");
                        }
                    } catch (Throwable var7) {
                        Logger.error("LOD resync: voxyworldgenv2 bridge unavailable — resync disabled", var7);
                        sendLODData = null;
                        inSyncRange = null;
                        RADIUS_SOURCE = () -> 0;
                    }

                    resolved = true;
                }
            }
        }
    }

    private static boolean active() {
        resolve();
        return sendLODData != null && LodResyncConfig.get().enabled;
    }

    private static int radiusChunks() {
        return sendLODData == null ? 0 : Math.max(0, RADIUS_SOURCE.get());
    }

    private static ExecutorService loader() {
        ExecutorService l = LOADER;
        if (l != null && !l.isShutdown()) {
            return l;
        } else {
            synchronized (LodResyncServer.class) {
                if (LOADER == null || LOADER.isShutdown()) {
                    int n = Math.max(1, LodResyncConfig.get().maxConcurrentLoads);
                    AtomicInteger counter = new AtomicInteger();
                    LOADER = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "Voxy-LOD-Resync-Loader-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        t.setPriority(1);
                        return t;
                    });
                }

                return LOADER;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        LodResyncConfig.load();
        resolve();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ExecutorService l = LOADER;
        LOADER = null;
        if (l != null) {
            l.shutdownNow();
        }

        GENERATION.incrementAndGet();
        PENDING.clear();
        IN_FLIGHT.clear();
        ANNOUNCED.clear();
        IN_FLIGHT_COUNT.set(0);
        roundRobinCursor = 0;
        servedThisWindow = 0;
        windowStartMs = 0L;
        measuredRatePerSecond = 0;
        lastAnnouncedRate = 0;
        lastStatusLogMs = 0L;
        NOT_GENERATED.set(0);
    }

    private static int perClientRate() {
        LodResyncConfig.Data cfg = LodResyncConfig.get();
        int nominal = Math.max(1, cfg.chunksPerTick) * 20;
        int measured = measuredRatePerSecond;
        int total = measured > 0 ? Math.min(nominal, measured) : nominal;
        total = Math.max(total, Math.max(1, cfg.floorChunksPerTick) * 20);
        int rate = Math.max(1, total / Math.max(1, ANNOUNCED.size()));
        lastAnnouncedRate = rate;
        return rate;
    }

    private static void sendReady(ServerPlayer player) {
        if (active()) {
            int radius = radiusChunks();
            if (radius > 0) {
                boolean isNew = ANNOUNCED.add(player.getUUID());
                if (!announce(player, radius)) {
                    ANNOUNCED.remove(player.getUUID());
                } else {
                    if (isNew) {
                        reannounceOthers(player.getUUID(), radius);
                    }
                }
            }
        }
    }

    private static boolean announce(ServerPlayer player, int radius) {
        try {
            PacketDistributor.sendToPlayer(player, new LodResyncPayloads.ResyncReady(radius, perClientRate()));
            return true;
        } catch (Throwable var3) {
            return false;
        }
    }

    private static void reannounceOthers(UUID except, int radius) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            for (ServerPlayer other : server.getPlayerList().getPlayers()) {
                if (!other.getUUID().equals(except) && ANNOUNCED.contains(other.getUUID())) {
                    announce(other, radius);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            sendReady(sp);
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            PENDING.remove(sp.getUUID());
            sendReady(sp);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer sp) {
            sendReady(sp);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        PENDING.remove(id);
        IN_FLIGHT.remove(id);
        if (ANNOUNCED.remove(id)) {
            int radius = radiusChunks();
            if (radius > 0) {
                reannounceOthers(id, radius);
            }
        }
    }

    static void onRequest(LodResyncPayloads.ResyncRequest payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) {
            if (!sp.hasDisconnected() && ANNOUNCED.contains(sp.getUUID())) {
                if (active()) {
                    long[] positions = payload.chunkPositions();
                    int limit = Math.min(positions.length, LodResyncPayloads.ResyncRequest.MAX_CHUNKS_PER_REQUEST);
                    int cap = LodResyncConfig.get().maxPendingPerPlayer;
                    LongLinkedOpenHashSet queue = PENDING.computeIfAbsent(sp.getUUID(), u -> new LongLinkedOpenHashSet());
                    synchronized (queue) {
                        for (int i = 0; i < limit; i++) {
                            long pos = positions[i];
                            int cx = ChunkPos.getX(pos);
                            int cz = ChunkPos.getZ(pos);
                            if (Math.abs(cx) <= MAX_CHUNK_COORD && Math.abs(cz) <= MAX_CHUNK_COORD) {
                                if (queue.size() >= cap) {
                                    onSaturated(sp);
                                    break;
                                }

                                queue.add(pos);
                            }
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        measureRate();
        if (!PENDING.isEmpty()) {
            if (active()) {
                int radius = radiusChunks();
                if (radius <= 0) {
                    PENDING.clear();
                } else {
                    MinecraftServer server = event.getServer();
                    List<ServerPlayer> players = server.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        LodResyncConfig.Data cfg = LodResyncConfig.get();
                        int budget = event.hasTime() ? cfg.chunksPerTick : Math.min(cfg.floorChunksPerTick, cfg.chunksPerTick);
                        if (budget > 0) {
                            int demanding = 0;

                            for (ServerPlayer p : players) {
                                LongLinkedOpenHashSet q = PENDING.get(p.getUUID());
                                if (q != null && !q.isEmpty()) {
                                    demanding++;
                                }
                            }

                            if (demanding != 0) {
                                int perPlayer = Math.max(1, budget / demanding);
                                long maxDistBlocks = (long)radius * 16L;
                                double maxDistSq = (double)maxDistBlocks * (double)maxDistBlocks;
                                int n = players.size();
                                int start = Math.floorMod(roundRobinCursor++, n);

                                for (int pi = 0; pi < n; pi++) {
                                    ServerPlayer player = players.get((start + pi) % n);
                                    if (budget <= 0) {
                                        break;
                                    }

                                    UUID id = player.getUUID();
                                    LongLinkedOpenHashSet queue = PENDING.get(id);
                                    if (queue != null) {
                                        int served = 0;
                                        int scanned = 0;

                                        while (budget > 0 && served < perPlayer && scanned < MAX_SCANNED_PER_PLAYER_PER_TICK) {
                                            long pos;
                                            synchronized (queue) {
                                                if (queue.isEmpty()) {
                                                    break;
                                                }

                                                pos = queue.removeFirstLong();
                                            }

                                            scanned++;
                                            int cx = ChunkPos.getX(pos);
                                            int cz = ChunkPos.getZ(pos);
                                            double mx = (double)(((long)cx << 4) + 8L);
                                            double mz = (double)(((long)cz << 4) + 8L);
                                            double dx = player.getX() - mx;
                                            double dz = player.getZ() - mz;
                                            if (!(dx * dx + dz * dz > maxDistSq)) {
                                                ServerLevel level = player.serverLevel();
                                                LevelChunk resident = level.getChunkSource().getChunkNow(cx, cz);
                                                if (resident != null) {
                                                    budget--;
                                                    served++;
                                                    servedThisWindow++;
                                                    send(player, resident);
                                                } else if (cfg.loadUnloadedChunks) {
                                                    if (IN_FLIGHT_COUNT.get() >= cfg.maxConcurrentLoads) {
                                                        synchronized (queue) {
                                                            queue.addAndMoveToFirst(pos);
                                                            break;
                                                        }
                                                    }

                                                    LongOpenHashSet set = IN_FLIGHT.computeIfAbsent(id, u -> new LongOpenHashSet());
                                                    boolean added;
                                                    synchronized (set) {
                                                        added = set.add(pos);
                                                    }

                                                    if (added) {
                                                        IN_FLIGHT_COUNT.incrementAndGet();
                                                        budget--;
                                                        served++;
                                                        servedThisWindow++;
                                                        ChunkPos chunkPos = new ChunkPos(cx, cz);
                                                        ServerChunkCache cache = level.getChunkSource();
                                                        cache.addRegionTicket(RESYNC_TICKET, chunkPos, 0, chunkPos);
                                                        scheduleLoad(server, level, id, chunkPos, pos);
                                                    }
                                                }
                                            }
                                        }

                                        synchronized (queue) {
                                            if (queue.isEmpty()) {
                                                PENDING.remove(id, queue);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void scheduleLoad(MinecraftServer server, ServerLevel level, UUID playerId, ChunkPos chunkPos, long pos) {
        int gen = GENERATION.get();
        Runnable release = () -> {
            releaseTicket(server, level, chunkPos);
            IN_FLIGHT.computeIfPresent(playerId, (u, s) -> {
                synchronized (s) {
                    s.remove(pos);
                    return s.isEmpty() ? null : s;
                }
            });
            if (GENERATION.get() == gen) {
                IN_FLIGHT_COUNT.decrementAndGet();
            }
        };

        try {
            loader().execute(() -> {
                try {
                    if (!LodResyncConfig.get().generateMissingChunks) {
                        Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(chunkPos).get(5L, TimeUnit.SECONDS);
                        if (tag == null || tag.isEmpty()) {
                            NOT_GENERATED.incrementAndGet();
                            release.run();
                            return;
                        }
                    }

                    level.getChunkSource().getChunkFuture(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true).whenComplete((result, err) -> {
                        try {
                            server.execute(() -> {
                                try {
                                    if (err != null || result == null || !result.isSuccess()) {
                                        logLoadFailure(chunkPos, err, result);
                                        return;
                                    }

                                    if (!(result.orElse(null) instanceof LevelChunk chunk)) {
                                        return;
                                    }

                                    ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                                    if (p != null && p.serverLevel() == level) {
                                        send(p, chunk);
                                        return;
                                    }
                                } finally {
                                    release.run();
                                }
                            });
                        } catch (Throwable var8) {
                            release.run();
                        }
                    });
                } catch (Throwable var6x) {
                    Logger.error("LOD resync: async load failed for " + chunkPos, var6x);
                    release.run();
                }
            });
        } catch (Throwable var9) {
            release.run();
        }
    }

    private static void releaseTicket(MinecraftServer server, ServerLevel level, ChunkPos pos) {
        Runnable r = () -> {
            try {
                level.getChunkSource().removeRegionTicket(RESYNC_TICKET, pos, 0, pos);
            } catch (Throwable ignored) {
            }
        };
        if (server.isSameThread()) {
            r.run();
        } else if (server.isRunning()) {
            try {
                server.execute(r);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void onSaturated(ServerPlayer player) {
        int radius = radiusChunks();
        if (radius > 0 && ANNOUNCED.contains(player.getUUID())) {
            announce(player, radius);
        }

        logSaturated(player);
    }

    private static void logSaturated(ServerPlayer player) {
        long now = System.currentTimeMillis();
        if (now - lastSaturatedLogMs >= 30000L) {
            lastSaturatedLogMs = now;
            Logger.error(
                    "LOD resync: request queue full for "
                        + player.getGameProfile().getName()
                        + " — the server is not draining requests as fast as this client sends them. The client has been told the measured rate and should slow down. If this repeats, check server TPS first; only raise chunksPerTick once the server is comfortably keeping up."
            );
        }
    }

    private static void logLoadFailure(ChunkPos pos, Throwable err, Object result) {
        long now = System.currentTimeMillis();
        if (now - lastFailLogMs >= 10000L) {
            lastFailLogMs = now;
            Logger.error(
                    "LOD resync: could not load chunk "
                        + pos
                        + " for re-serve ("
                        + (err != null ? err.toString() : String.valueOf(result))
                        + "); the client will retry within its attempt budget"
            );
        }
    }

    private static void send(ServerPlayer player, LevelChunk chunk) {
        try {
            if (inSyncRange != null && !(boolean) inSyncRange.invoke(player, chunk)) {
                return;
            }

            sendLODData.invoke(player, chunk);
        } catch (Throwable var3) {
            Logger.error("LOD resync: failed to re-serve chunk " + chunk.getPos(), var3);
        }
    }

    private interface IntSource {
        int get();
    }
}
