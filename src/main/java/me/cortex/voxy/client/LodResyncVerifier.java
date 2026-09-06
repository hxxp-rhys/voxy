package me.cortex.voxy.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.lod.LodBandPlan;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.IWorldGetIdentifier;
import me.cortex.voxy.commonImpl.network.LodResyncPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the client's Voxy LOD database strictly nearest-first and re-requests
 * missing cells — closing the permanent holes voxyworldgenv2 leaves when a send
 * loses its race: it commits the chunk's "synced" bit before handing the payload to
 * an async pool whose task then re-tests the player's CURRENT distance and sends
 * nothing if they moved. The bit stays set, the mod's own backfill skips the chunk
 * forever. See LodResyncPayloads for the verified mechanism.
 *
 * All ordering, banding, budgeting and resume logic lives in {@link LodBandPlan},
 * which has no Minecraft dependencies and is covered by an executable test suite
 * (tools/LodBandPlanTest.java).
 *
 * <h2>Probing</h2>
 * The existence oracle is {@link SectionStorage#sectionExists(long)} — a key-only
 * database lookup. It is explicitly NOT {@code WorldEngine.acquireIfExists}: that
 * path allocates a 256 KiB WorldSection, runs the deserializer or an Arrays.fill of
 * the entire array on a miss, inserts a holder into the active-section cache, and
 * on release pushes the result through the bounded LRU secondary cache — so a
 * sweep would evict Voxy's real working set and replace it with air. At the budgets
 * below that was thousands of 256 KiB fills per second.
 *
 * <h2>Grace window</h2>
 * Voxy persists a section shortly after the ingest worker releases it (dirty
 * sections are enqueued to SectionSavingService from ActiveSectionTracker.tryUnload),
 * so a just-delivered column reads as absent from storage for a short window. Cells
 * are therefore only re-requested once they have been observed missing continuously
 * for {@code lodResyncGraceMs} — otherwise a fresh login would re-request the entire
 * incoming flood. Genuine holes never fill, so they always outlive the window.
 *
 * <h2>Granularity</h2>
 * Voxy's finest LOD cell (lvl 0) is 32x32x32 blocks — a 2x2 chunk footprint, exactly
 * the checkerboard cell users observe. A cell counts as covered when ANY vertical
 * section of its column is stored. Missing cells get their 4 chunks re-requested at
 * most {@code lodResyncMaxAttempts} times, so all-air columns (never sent by design)
 * cannot loop forever.
 *
 * Runs on one daemon thread at MIN_PRIORITY. Storage reads are thread-safe (LMDB
 * read transactions, synchronized maps); sends go through PacketDistributor, whose
 * client path ends in Connection.send — netty-safe from any thread.
 */
public final class LodResyncVerifier {

    private static final long PASS_SLEEP_MS = 200L;
    private static final long IDLE_SLEEP_MS = 1000L;

    private static volatile LodResyncVerifier INSTANCE;

    public static void tick() {
        var mc = Minecraft.getInstance();
        int radius = effectiveRadiusChunks();
        boolean shouldRun = mc.level != null
                && radius > 0
                && VoxyConfig.CONFIG != null
                && VoxyConfig.CONFIG.enabled
                && VoxyConfig.CONFIG.lodResyncEnabled;
        var cur = INSTANCE;
        // A dead instance (run() escaped on an unexpected Throwable) must not wedge the
        // feature off for the rest of the session.
        if (cur != null && !cur.thread.isAlive()) { INSTANCE = null; cur = null; }
        if (shouldRun && cur == null) {
            var v = new LodResyncVerifier(radius);
            INSTANCE = v;
            v.thread.start();
        } else if (!shouldRun && cur != null) {
            INSTANCE = null;
            cur.shutdown();
        }
    }

    /**
     * Stop and join the worker before the LOD database is torn down.
     * <p>
     * Called from every client-side {@code VoxyCommon.shutdownInstance()} site. Leaving a
     * world goes through {@code onDisconnect}, but toggling Voxy off in Sodium's settings
     * ({@code VoxyConfigMenu}) and {@code /voxy reload} ({@code VoxyCommands}) tear the
     * instance down directly, on the client thread, without any disconnect event — and
     * that teardown ends in {@code LMDBStorageBackend.close()} while this thread may be
     * inside a read transaction.
     */
    public static void stopForTeardown() {
        var cur = INSTANCE;
        if (cur != null) {
            INSTANCE = null;
            cur.shutdown();
        }
    }

    /**
     * Stop the worker and WAIT for it. Both callers run on the client thread, ahead of
     * the world teardown that frees the LOD database.
     * <p>
     * A bare {@code stop = true} is not enough. {@code run()} evaluates {@code pass()}
     * before re-checking the flag and then sleeps up to a second, so the thread outlives
     * the flag by ~1.2s — during which {@code Minecraft.disconnect} reaches
     * {@code VoxyCommon.shutdownInstance()} and {@code LMDBStorageBackend.close()}.
     * Closing an LMDB environment under a live read transaction is undefined behaviour at
     * the native level: a SIGSEGV, which no {@code catch (Throwable)} can intercept. The
     * same latency also let a fast disable/enable start a SECOND verifier alongside the
     * first. Interrupt aborts the sleep; join covers a pass already in flight.
     */
    private void shutdown() {
        this.stop = true;
        this.thread.interrupt();
        try {
            this.thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Radius to verify, in chunks. Capped by {@code lodResyncMaxRadiusChunks}
     * (default 256): voxyworldgenv2's wire format ships full-resolution chunk
     * sections at every distance with no downsampling, so a 512-chunk sweep is
     * multiple GB per player. Raising the cap is a bandwidth decision, not a
     * correctness one.
     */
    private static int effectiveRadiusChunks() {
        int server = LodResyncClient.serverRadiusChunks();
        if (server <= 0) return 0;
        int cap = VoxyConfig.CONFIG != null ? VoxyConfig.CONFIG.lodResyncMaxRadiusChunks : 256;
        return Math.min(server, Math.max(16, cap));
    }

    public static void onDisconnect() {
        // reset() MUST precede the stop: it is what makes effectiveRadiusChunks() return 0,
        // so the ticks that still run during teardown decline to restart the verifier.
        LodResyncClient.reset();
        var cur = INSTANCE;
        if (cur != null) {
            INSTANCE = null;
            cur.shutdown();
        }
    }

    private final Thread thread;
    private final int radiusChunks;
    private final LodBandPlan plan;
    private volatile boolean stop;
    /** cell -> re-request attempts spent. */
    private final Long2IntOpenHashMap attempts = new Long2IntOpenHashMap();
    /** cell -> epoch millis it was FIRST observed missing (grace-window anchor). */
    private final Long2LongOpenHashMap firstMissingAt = new Long2LongOpenHashMap();
    private final LongOpenHashSet verifiedSet = new LongOpenHashSet();
    private ClientLevel boundLevel;
    private boolean loggedComplete;
    private static final int PRUNE_THRESHOLD_CELLS = 200_000;
    private int lastPruneCellX = Integer.MIN_VALUE, lastPruneCellZ = Integer.MIN_VALUE;
    /** Token bucket pacing requests to the server's announced re-serve throughput. */
    private double tokens;
    private long lastTokenMs = System.currentTimeMillis();
    /** Section-Y scan order, nearest-to-sea-level first (most columns hit on try 1-2). */
    private int[] yOrder = new int[0];
    private int yOrderMin = Integer.MAX_VALUE, yOrderMax = Integer.MIN_VALUE;

    private final LodBandPlan.VerifiedSet verified = new LodBandPlan.VerifiedSet() {
        @Override public boolean contains(int x, int z) { return verifiedSet.contains(ChunkPos.asLong(x, z)); }
        @Override public void add(int x, int z) { verifiedSet.add(ChunkPos.asLong(x, z)); }
    };

    private LodResyncVerifier(int radiusChunks) {
        this.radiusChunks = radiusChunks;
        this.plan = new LodBandPlan(LodBandPlan.defaultSpecs(radiusChunks));
        this.attempts.defaultReturnValue(0);
        this.firstMissingAt.defaultReturnValue(0L);
        this.thread = new Thread(this::run, "Voxy-LOD-Resync-Verifier");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
        var sb = new StringBuilder("LOD resync verifier started: radius ").append(radiusChunks)
                .append(" chunks, ").append(plan.totalCells()).append(" cells, bands");
        for (var s : plan.specs()) {
            sb.append(' ').append(s.band()).append('[').append(s.innerChunks()).append('-')
              .append(s.outerChunks()).append(']');
        }
        Logger.info(sb.toString());
    }

    private void run() {
        try {
            while (!this.stop) {
                Thread.sleep(this.pass() ? PASS_SLEEP_MS : IDLE_SLEEP_MS);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            Logger.error("LOD resync verifier died", t);
        } finally {
            // Let tick() build a fresh one rather than leaving a dead object installed.
            if (INSTANCE == this) INSTANCE = null;
        }
    }

    /**
     * Thrown out of the probe when the LOD database goes away mid-pass, to ABORT the
     * traversal instead of telling it the cell exists.
     * <p>
     * Reporting {@code true} made {@link LodBandPlan} mark the cell verified and keep
     * going — so a single "World is not live" could permanently verify a whole band's
     * probe budget of cells that were never looked at, producing large contiguous holes
     * the verifier then reported as complete. Stackless: it is control flow, not an error.
     */
    private static final class EngineGone extends RuntimeException {
        EngineGone() { super(null, null, false, false); }
    }

    /**
     * Drop per-cell state for cells the plan can no longer reach from this centre.
     * <p>
     * verifiedSet / attempts / firstMissingAt are keyed per cell and were only cleared on
     * a dimension change, so they grew with distance travelled, not with the working set:
     * a player who covers 100 km accumulates on the order of a million entries across the
     * three, tens of MB, none of it reachable again. The plan can never revisit a cell
     * outside the current radius, so evicting it is free. Amortised: only runs when the
     * centre has actually moved, and only when the sets are large enough to matter.
     */
    private void pruneOutOfRange(int centerCellX, int centerCellZ) {
        if (this.verifiedSet.size() < PRUNE_THRESHOLD_CELLS) return;
        if (centerCellX == this.lastPruneCellX && centerCellZ == this.lastPruneCellZ) return;
        this.lastPruneCellX = centerCellX;
        this.lastPruneCellZ = centerCellZ;
        // Generous margin so a cell just outside is not evicted and re-probed repeatedly.
        long keep = (long) (this.radiusChunks / 2) + 64L;
        long keepSq = keep * keep;
        pruneSet(this.verifiedSet, centerCellX, centerCellZ, keepSq);
        pruneKeys(this.attempts.keySet(), centerCellX, centerCellZ, keepSq);
        pruneKeys(this.firstMissingAt.keySet(), centerCellX, centerCellZ, keepSq);
    }

    private static boolean outOfRange(long key, int cx, int cz, long keepSq) {
        long dx = (long) ChunkPos.getX(key) - cx;
        long dz = (long) ChunkPos.getZ(key) - cz;
        return dx * dx + dz * dz > keepSq;
    }

    private static void pruneSet(LongOpenHashSet set, int cx, int cz, long keepSq) {
        var it = set.iterator();
        while (it.hasNext()) if (outOfRange(it.nextLong(), cx, cz, keepSq)) it.remove();
    }

    private static void pruneKeys(it.unimi.dsi.fastutil.longs.LongSet keys, int cx, int cz, long keepSq) {
        var it2 = keys.iterator();
        while (it2.hasNext()) if (outOfRange(it2.nextLong(), cx, cz, keepSq)) it2.remove();
    }

    /** Rebuild the section-Y scan order for this level: sea level outwards. */
    private void ensureYOrder(int yMin, int yMax) {
        if (yMin == this.yOrderMin && yMax == this.yOrderMax) return;
        this.yOrderMin = yMin;
        this.yOrderMax = yMax;
        int n = Math.max(0, yMax - yMin + 1);
        int[] order = new int[n];
        int seed = Math.min(yMax, Math.max(yMin, 64 >> 5)); // y=64 (sea level) -> section 2
        int i = 0;
        for (int d = 0; i < n; d++) {
            int lo = seed - d, hi = seed + d;
            if (d == 0) { order[i++] = seed; continue; }
            if (lo >= yMin && i < n) order[i++] = lo;
            if (hi <= yMax && i < n) order[i++] = hi;
        }
        this.yOrder = order;
    }

    /**
     * Refill the request bucket. One token == one cell == 4 chunk positions.
     * <p>
     * The server drains a fixed budget per tick and silently discards anything past its
     * queue cap. Unpaced, the HIGH band emits 64 cells (256 positions) every 200 ms —
     * roughly 8x what a default server can serve — so the queue saturates in seconds and
     * the overflow is dropped with no NACK, while the client counts those sends as
     * attempts and permanently abandons the cells. Pacing to the announced rate is what
     * makes the retry budget mean anything.
     */
    private void refillTokens() {
        long now = System.currentTimeMillis();
        long dt = Math.max(0L, Math.min(1000L, now - this.lastTokenMs));
        this.lastTokenMs = now;
        int chunksPerSecond = LodResyncClient.serverServeRatePerSecond();
        if (chunksPerSecond <= 0) chunksPerSecond = 160; // conservative default: 8/tick
        double cellsPerSecond = chunksPerSecond / 4.0;   // one cell == 2x2 chunks
        this.tokens = Math.min(cellsPerSecond, this.tokens + cellsPerSecond * (dt / 1000.0));
    }

    /** @return true if work was done, false to idle. */
    private boolean pass() {
        if (this.stop) return false;
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        var player = mc.player;
        // Minecraft.level and Minecraft.player are plain non-volatile fields, and
        // ClientPacketListener.handleRespawn swaps them ~19 lines apart. Reading a
        // half-swapped pair would centre the sweep on the new dimension's coordinates
        // while probing the old dimension's database — every cell reads missing and a
        // burst of wrong-dimension requests goes out, burning their retry budget.
        if (level == null || player == null || player.level() != level) return false;
        if (LodResyncClient.serverRadiusChunks() <= 0) return false;

        if (this.boundLevel != level) { // dimension change: state is per-level
            this.boundLevel = level;
            this.attempts.clear();
            this.firstMissingAt.clear();
            this.verifiedSet.clear();
            this.loggedComplete = false;
            this.yOrderMin = Integer.MAX_VALUE;
            this.yOrderMax = Integer.MIN_VALUE;
            // The plan's cursors are NOT per-level. Landing in a new dimension at the same
            // 32-block cell coordinates would otherwise leave them mid-sweep over a
            // freshly-cleared verified set, and every cell already walked in the old
            // dimension would go unprobed in the new one.
            this.plan.resetSweep();
        }

        WorldEngine engine;
        try {
            var id = ((IWorldGetIdentifier) level).voxy$getIdentifier();
            // getNullable, not getOrCreateEngine: a pure probe must never create engines.
            engine = id == null ? null : id.getNullable();
        } catch (Throwable t) {
            return false;
        }
        if (engine == null) return false;
        final SectionStorage storage = engine.storage;
        if (storage == null) return false;

        this.ensureYOrder(level.getMinBuildHeight() >> 5, (level.getMaxBuildHeight() - 1) >> 5);
        final int[] ys = this.yOrder;
        final var cfg = VoxyConfig.CONFIG;
        final int maxAttempts = Math.max(1, cfg != null ? cfg.lodResyncMaxAttempts : 5);
        final long graceMs = Math.max(0L, cfg != null ? cfg.lodResyncGraceMs : 15000L);
        final long now = System.currentTimeMillis();
        final List<Long> missingChunks = new ArrayList<>();

        LodBandPlan.CellProbe probe = (cx, cz) -> {
            try {
                for (int cy : ys) {
                    if (storage.sectionExists(WorldEngine.getWorldSectionId(0, cx, cy, cz))) return true;
                }
            } catch (Throwable t) {
                throw new EngineGone(); // storage closing mid-probe (disconnect race)
            }
            return false;
        };

        LodBandPlan.CellRequest request = (cx, cz) -> {
            long cellKey = ChunkPos.asLong(cx, cz);

            // Grace window: a column delivered moments ago has not been flushed to
            // storage yet. Only cells missing for the whole window are real holes.
            long first = this.firstMissingAt.get(cellKey);
            if (first == 0L) { this.firstMissingAt.put(cellKey, now); return false; }
            if (now - first < graceMs) return false;

            if (this.tokens < 1.0) return false;   // paced out; re-probed next sweep
            int tries = this.attempts.get(cellKey);
            if (tries >= maxAttempts) {
                // Give up: almost certainly an all-air column the server never sends by
                // design. Marking it verified is what lets the sweep converge.
                this.verified.add(cx, cz);
                return false;
            }
            this.attempts.put(cellKey, tries + 1);
            // Restart this cell's settle window so the NEXT attempt is spaced by graceMs
            // rather than by however fast the sweep happens to come back around. Without
            // it, a band that is mostly verified re-sweeps in a fraction of a second and
            // burns the whole attempt budget long before the server could have answered
            // the first request — the cell is then abandoned as unfillable.
            this.firstMissingAt.put(cellKey, now);
            this.tokens -= 1.0;
            int chunkX = cx << 1, chunkZ = cz << 1;
            missingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
            missingChunks.add(ChunkPos.asLong(chunkX + 1, chunkZ));
            missingChunks.add(ChunkPos.asLong(chunkX, chunkZ + 1));
            missingChunks.add(ChunkPos.asLong(chunkX + 1, chunkZ + 1));
            return true;
        };

        this.refillTokens();

        int centerCellX = ((int) Math.floor(player.getX())) >> 5;
        int centerCellZ = ((int) Math.floor(player.getZ())) >> 5;
        this.pruneOutOfRange(centerCellX, centerCellZ);
        int probed;
        // Hold a reference for the whole probing region. WorldEngine.isWorldUsed() is what
        // VoxyInstance.shutdown() spin-waits on and what cleanIdle() tests, and this thread
        // holds no section between probes, so without a ref the engine (and the LMDB env
        // under it) can be freed mid-pass. acquireRef throws once the world is no longer
        // live, which is the correct outcome here.
        try {
            engine.acquireRef();
        } catch (Throwable t) {
            return false;
        }
        try {
            probed = this.plan.runPass(centerCellX, centerCellZ, this.verified, probe, request);
        } catch (EngineGone e) {
            // Cells after the abort point were skipped without being probed; rewind so the
            // next pass covers them instead of leaving a silent gap.
            this.plan.resetSweep();
            return false;
        } finally {
            try { engine.releaseRef(); } catch (Throwable ignored) {}
        }

        if (!missingChunks.isEmpty()) {
            this.loggedComplete = false;
            int max = LodResyncPayloads.ResyncRequest.MAX_CHUNKS_PER_REQUEST;
            for (int i = 0; i < missingChunks.size(); i += max) {
                int end = Math.min(missingChunks.size(), i + max);
                long[] batch = new long[end - i];
                for (int j = i; j < end; j++) batch[j - i] = missingChunks.get(j);
                try {
                    PacketDistributor.sendToServer(new LodResyncPayloads.ResyncRequest(batch));
                } catch (UnsupportedOperationException e) {
                    // NeoForge's checkPacket throws this when the channel was never
                    // negotiated. Retrying would spin a full probe pass at 5 Hz forever
                    // against a server that will never answer — stand down instead.
                    Logger.info("LOD resync: server does not accept the voxy channel — standing down");
                    LodResyncClient.reset();
                    return false;
                } catch (Throwable t) {
                    return true; // connection gone mid-send — retry next pass
                }
            }
        } else if (this.plan.sweepComplete() && !this.loggedComplete) {
            this.loggedComplete = true;
            Logger.info("LOD resync complete: " + this.verifiedSet.size() + " cells verified within "
                    + this.radiusChunks + " chunks");
        }
        return probed > 0;
    }
}
