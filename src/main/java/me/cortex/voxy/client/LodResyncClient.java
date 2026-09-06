package me.cortex.voxy.client;

/**
 * Client-side state for the LOD resync protocol.
 *
 * DELIBERATELY free of client-only imports: the S2C payload handler in the common
 * registration class references this type, so it must be loadable on a dedicated
 * server (where the handler never runs but the lambda's method reference resolves
 * at registration). The actual verification work lives in LodResyncVerifier,
 * which is only ever touched from Dist.CLIENT event code.
 */
public final class LodResyncClient {
    private LodResyncClient() {}

    private static volatile int serverRadiusChunks = 0;
    /**
     * Chunk positions the server can actually re-serve per second. The client paces
     * its requests to this: the server drains a fixed budget per tick, so a client
     * that fires faster just overflows the server's bounded queue, and because the
     * client counts an attempt on SEND (not on delivery) it then burns through
     * lodResyncMaxAttempts on requests that were silently discarded and abandons the
     * cell forever. Pacing is what makes the retry budget mean something.
     */
    private static volatile int serverServeRatePerSecond = 0;

    /** Called by the payload handler when the server announces resync support. */
    public static void onReady(int radiusChunks, int serveRatePerSecond) {
        serverServeRatePerSecond = Math.max(0, serveRatePerSecond);
        serverRadiusChunks = Math.max(0, radiusChunks);
    }

    public static int serverRadiusChunks() {
        return serverRadiusChunks;
    }

    /** 0 => unknown; callers should fall back to a conservative default. */
    public static int serverServeRatePerSecond() {
        return serverServeRatePerSecond;
    }

    public static boolean isServerReady() {
        return serverRadiusChunks > 0;
    }

    /** Reset on disconnect so a voxy-less server doesn't inherit the last one's flag. */
    public static void reset() {
        serverRadiusChunks = 0;
        serverServeRatePerSecond = 0;
    }
}
