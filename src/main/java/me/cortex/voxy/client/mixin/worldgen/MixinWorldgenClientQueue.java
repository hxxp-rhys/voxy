package me.cortex.voxy.client.mixin.worldgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayDeque;

/**
 * Fixes a data-loss defect in voxyworldgenv2 2.4.2's client ingest queue.
 * <p>
 * NetworkClientHandler.drainIngestQueue() re-queues the unprocessed remainder in
 * nearest-first order (it sorts the snapshot by distance to the player before
 * processing), then trims overflow with {@code pollFirst()} — which discards the
 * NEAREST pending LOD payloads. Under a login flood this is exactly backwards and
 * produces permanent holes right around the player. Redirecting to
 * {@code pollLast()} discards the farthest instead; far chunks are re-covered by
 * the server's ongoing generation sweep, near ones are not.
 * <p>
 * The same inversion exists in handleLODData, and that is the one that actually fires.
 * <p>
 * It rests on one fact about the server, which is verified rather than assumed: the
 * backfill sweep emits <b>nearest-first</b>. {@code ChunkGenerationManager} line 213 calls
 * {@code DistanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced,
 * batch, 8)}, and that method walks a {@code PriorityQueue} ordered by
 * {@code Comparator.comparingDouble(i -> i.distSq)} (DistanceGraph:173). Successive
 * batches march outward as nearer chunks enter the synced set. So payloads reach the
 * client in distance order, INGEST_QUEUE's HEAD is the nearest outstanding payload and its
 * TAIL the farthest — and {@code pollFirst()} at the 8192 cap discards precisely the
 * nearest work. drainIngestQueue reinforces the same ordering: it sorts its snapshot by
 * distance and re-queues the unprocessed remainder in that order.
 * <p>
 * Redirecting to pollLast drops the farthest instead. It cannot wedge the queue:
 * drainIngestQueue runs every client tick and removes up to 96 sections' worth, so the
 * size falls back under the cap within a tick or two and new arrivals are accepted again.
 * <p>
 * (An earlier version of this file asserted handleLODData's queue is "unsorted
 * arrival-order, where dropping the oldest is correct", and left this call site unpatched
 * on that basis. The DistanceGraph evidence above shows the premise was wrong.)
 */
@Mixin(targets = "com.ethan.voxyworldgenv2.network.NetworkClientHandler", remap = false)
public class MixinWorldgenClientQueue {

    @Redirect(
            method = "handleLODData",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayDeque;pollFirst()Ljava/lang/Object;"),
            require = 0
    )
    private static Object voxy$dropNewestNotNearest(ArrayDeque<Object> deque) {
        return deque.pollLast();
    }

    @Redirect(
            method = "drainIngestQueue",
            at = @At(value = "INVOKE", target = "Ljava/util/ArrayDeque;pollFirst()Ljava/lang/Object;"),
            require = 0 // fail soft: without the redirect the mod still works, just with the upstream drop bias
    )
    private static Object voxy$dropFarthestNotNearest(ArrayDeque<Object> deque) {
        return deque.pollLast();
    }
}
