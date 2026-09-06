package me.cortex.voxy.client.lod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Distance-banded, strictly nearest-first traversal plan for LOD verification.
 * <p>
 * DELIBERATELY free of Minecraft imports: all of the ordering, banding, budgeting and
 * resume logic lives here so it can be executed and asserted in a plain JVM test
 * (see LodBandPlanTest). The Minecraft-facing half (probing Voxy's database, sending
 * requests) is injected through the two functional interfaces below.
 * <p>
 * Units: the planner works in <b>cells</b>. One cell is Voxy's finest LOD cell —
 * 32x32 blocks, i.e. a 2x2 chunk footprint — so cellRadius = chunkRadius / 2.
 * Band bounds are supplied in CHUNKS (matching the user-facing config) and converted
 * internally.
 * <p>
 * Guarantees (all asserted by the test):
 *   1. Within a band, cells are visited in non-decreasing Euclidean distance from the
 *      centre. Never a far cell before a near one.
 *   2. Every cell whose centre lies in the band annulus is visited exactly once per
 *      sweep — no gaps, no duplicates.
 *   3. Strict band precedence: a band receives no budget until every band inside it
 *      has no outstanding work.
 *   4. Progress is resumable across budget-limited passes, and survives the player
 *      moving (the cursor restarts, but already-verified cells are never re-probed).
 */
public final class LodBandPlan {

    /** Probe: does this cell already exist in the local LOD database? */
    public interface CellProbe {
        boolean exists(int cellX, int cellZ);
    }

    /**
     * Sink for cells that are missing and should be re-requested from the server.
     *
     * @return true if a request was actually emitted. The caller may decline — it rate
     *         limits to the server's real throughput, and holds newly-missing cells back
     *         for a settle window before believing they are holes. A declined cell still
     *         counts as outstanding (so the band re-sweeps and its arrival is observed)
     *         but must NOT consume the per-pass request budget, which exists to cap
     *         packets on the wire. Charging it would let a settle window stall the cursor
     *         completely: the first {@code requestsPerPass} missing cells would absorb the
     *         whole budget every pass while emitting nothing.
     */
    public interface CellRequest {
        boolean request(int cellX, int cellZ);
    }

    public enum Band { HIGH, MEDIUM, LOW, VERY_LOW }

    /**
     * @param innerChunks inclusive inner bound in chunks (0 for the first band)
     * @param outerChunks exclusive outer bound in chunks
     * @param probesPerPass how many cells this band may probe in one pass
     * @param requestsPerPass how many missing cells this band may request in one pass
     */
    public record BandSpec(Band band, int innerChunks, int outerChunks,
                           int probesPerPass, int requestsPerPass) {
        public BandSpec {
            // Both budgets must be >= 1. A band with requestsPerPass == 0 would hit the
            // "out of request budget" path on its first missing cell, rewind its cursor
            // and break — permanently, since the cursor can then never advance. Strict
            // band precedence would starve every outer band forever.
            if (probesPerPass < 1) probesPerPass = 1;
            if (requestsPerPass < 1) requestsPerPass = 1;
        }
    }

    /** Defaults matching the requested resource tiers. */
    public static BandSpec[] defaultSpecs(int maxChunkRadius) {
        List<BandSpec> out = new ArrayList<>(4);
        addIfNonEmpty(out, new BandSpec(Band.HIGH,     0,   Math.min(128, maxChunkRadius), 512, 64));
        addIfNonEmpty(out, new BandSpec(Band.MEDIUM, 128,   Math.min(256, maxChunkRadius), 256, 32));
        addIfNonEmpty(out, new BandSpec(Band.LOW,    256,   Math.min(512, maxChunkRadius), 128, 16));
        addIfNonEmpty(out, new BandSpec(Band.VERY_LOW, 512, maxChunkRadius,                 64,  8));
        return out.toArray(new BandSpec[0]);
    }

    private static void addIfNonEmpty(List<BandSpec> out, BandSpec s) {
        if (s.outerChunks() > s.innerChunks()) out.add(s);
    }

    private final BandSpec[] specs;
    /** Per band: cell offsets packed as (dx << 32) | (dz & 0xffffffff), sorted by distance. */
    private final long[][] offsets;
    private final int[] cursor;
    /**
     * Per band: cells requested during the current sweep that were still missing when
     * probed. A sweep that requested anything must run again once the data has had time
     * to arrive — without this the cursor walks past a missing cell exactly once and its
     * arrival is never observed, so it stays a permanent hole. (Caught by
     * LodBandPlanTest check 6, which failed with 0 verified before this existed.)
     */
    private final int[] requestedThisSweep;

    private int centerCellX = Integer.MIN_VALUE;
    private int centerCellZ = Integer.MIN_VALUE;

    /**
     * How far (in cells) the player must move before the traversal re-centres.
     * <p>
     * Re-centring rewinds every cursor, so re-centring on every 32-block step means a
     * moving player restarts the innermost band continuously and — under strict band
     * precedence — no outer band ever receives budget. 4 cells is 128 blocks: two
     * orders of magnitude inside the narrowest band (128 chunks = 2048 blocks), so the
     * ordering error it admits is immaterial, while a sprinting or flying player still
     * gets several complete passes between re-centres.
     */
    static final int RECENTER_CELL_THRESHOLD = 4;
    /**
     * Clamped to >= 1. A threshold of 0 makes the re-centre test true even for a
     * STATIONARY player (|0| >= 0), so every pass rewinds every cursor and no band can
     * ever finish — the plan stops converging entirely. Clamping turns that edit into a
     * clean test failure rather than an unbounded loop.
     */
    private static final int RECENTER_MIN_CELLS = Math.max(1, RECENTER_CELL_THRESHOLD);

    public LodBandPlan(BandSpec[] specs) {
        this.specs = specs.clone();
        this.offsets = new long[specs.length][];
        this.cursor = new int[specs.length];
        this.requestedThisSweep = new int[specs.length];
        for (int i = 0; i < specs.length; i++) {
            this.offsets[i] = buildOffsets(specs[i].innerChunks() >> 1, specs[i].outerChunks() >> 1);
        }
    }

    /**
     * Cell offsets whose centre distance falls in [innerCells, outerCells), ordered by
     * true (Euclidean) squared distance — not Chebyshev rings, which would visit a
     * corner cell before nearer edge cells of the next ring.
     */
    static long[] buildOffsets(int innerCells, int outerCells) {
        if (outerCells <= innerCells) return new long[0];
        long innerSq = (long) innerCells * innerCells;
        long outerSq = (long) outerCells * outerCells;
        List<long[]> tmp = new ArrayList<>();
        for (int dx = -outerCells; dx <= outerCells; dx++) {
            for (int dz = -outerCells; dz <= outerCells; dz++) {
                long d2 = (long) dx * dx + (long) dz * dz;
                if (d2 >= innerSq && d2 < outerSq) tmp.add(new long[]{d2, pack(dx, dz)});
            }
        }
        tmp.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] out = new long[tmp.size()];
        for (int i = 0; i < out.length; i++) out[i] = tmp.get(i)[1];
        return out;
    }

    static long pack(int dx, int dz) {
        return ((long) dx << 32) | (dz & 0xffffffffL);
    }

    static int unpackX(long p) { return (int) (p >> 32); }
    static int unpackZ(long p) { return (int) p; }

    /** Total cells this plan will sweep (all bands). */
    public int totalCells() {
        int n = 0;
        for (long[] o : offsets) n += o.length;
        return n;
    }

    public BandSpec[] specs() { return specs.clone(); }

    /**
     * True only when every band has walked its full offset list AND that final walk
     * requested nothing — i.e. everything in range is confirmed present locally.
     */
    public boolean sweepComplete() {
        for (int i = 0; i < offsets.length; i++) {
            if (cursor[i] < offsets[i].length) return false;
            if (requestedThisSweep[i] > 0) return false;
        }
        return true;
    }

    /**
     * Rewind every band to the start of its offset list and forget the in-flight sweep.
     * <p>
     * Needed whenever the meaning of "already verified" changes underneath the plan:
     *   - a pass aborted part-way (the engine went away mid-probe), so cells after the
     *     abort point were skipped without being probed;
     *   - the client changed dimension, which clears the caller's verified set. Without
     *     this the cursors keep their mid-sweep position over a freshly empty set, and if
     *     the remaining tail requests nothing the band reports sweepComplete() while every
     *     cell the cursor already walked in the OLD dimension goes unprobed in the new one.
     */
    public void resetSweep() {
        Arrays.fill(this.cursor, 0);
        Arrays.fill(this.requestedThisSweep, 0);
    }

    /** Cells requested in the in-flight sweep, summed across bands (diagnostics). */
    public int outstanding() {
        int n = 0;
        for (int v : requestedThisSweep) n += v;
        return n;
    }

    /**
     * Run one pass. Bands are processed in order and a band is only given budget once
     * every inner band has finished its sweep (strict precedence).
     *
     * @param verified predicate-backed set of cells already confirmed present; the plan
     *                 never re-probes these, which is what makes repeated passes cheap
     *                 after the player moves.
     * @return number of cells probed this pass (0 == nothing left to do right now)
     */
    public int runPass(int centerCellX, int centerCellZ, VerifiedSet verified,
                       CellProbe probe, CellRequest request) {
        boolean first = this.centerCellX == Integer.MIN_VALUE;
        long ddx = (long) centerCellX - this.centerCellX;
        long ddz = (long) centerCellZ - this.centerCellZ;
        if (first || Math.abs(ddx) >= RECENTER_MIN_CELLS || Math.abs(ddz) >= RECENTER_MIN_CELLS) {
            this.centerCellX = centerCellX;
            this.centerCellZ = centerCellZ;
            Arrays.fill(this.cursor, 0); // re-sweep from the new centre; verified cells are skipped
            Arrays.fill(this.requestedThisSweep, 0);
        } else {
            // Inside the hysteresis band: keep sweeping around the previous centre so
            // cursors survive. Offsets below are applied to the stored centre.
            centerCellX = this.centerCellX;
            centerCellZ = this.centerCellZ;
        }

        int probed = 0;
        for (int b = 0; b < specs.length; b++) {
            long[] off = offsets[b];
            if (cursor[b] >= off.length) continue; // band swept; fall through to the next

            int probeBudget = specs[b].probesPerPass();
            int requestBudget = specs[b].requestsPerPass();
            int localProbes = 0;

            while (cursor[b] < off.length && localProbes < probeBudget) {
                long p = off[cursor[b]];
                int cx = centerCellX + unpackX(p);
                int cz = centerCellZ + unpackZ(p);
                cursor[b]++;

                if (verified.contains(cx, cz)) continue; // already confirmed — free to skip
                localProbes++;
                probed++;

                if (probe.exists(cx, cz)) {
                    verified.add(cx, cz);
                } else if (requestBudget > 0) {
                    // Outstanding either way — that is what forces the re-sweep that
                    // observes the data arriving. Only an emitted request is charged.
                    requestedThisSweep[b]++;
                    if (request.request(cx, cz)) requestBudget--;
                } else {
                    cursor[b]--; // out of request budget: retry this cell next pass
                    break;
                }
            }

            if (cursor[b] >= off.length && requestedThisSweep[b] > 0) {
                // Band walked to the end but some cells were still missing. Re-sweep so
                // their arrival is observed. Verified cells are skipped, so a re-sweep
                // costs only the cells that are genuinely still absent. Callers cap
                // retries by marking a cell verified from their CellRequest handler
                // after N attempts (all-air columns the server legitimately never sends).
                cursor[b] = 0;
                requestedThisSweep[b] = 0;
            }

            // strict precedence: stop here unless this band is fully swept and clean
            if (cursor[b] < off.length || requestedThisSweep[b] > 0) break;
        }
        return probed;
    }

    /** Minimal set abstraction so the real impl can use a primitive long set. */
    public interface VerifiedSet {
        boolean contains(int cellX, int cellZ);
        void add(int cellX, int cellZ);
    }
}
