package me.cortex.voxy.client.compat;

import java.nio.file.Path;

/**
 * Flashback replay integration.
 *
 * 1.21.1/NeoForge (brief contract C16): Flashback is a Fabric-only mod with no NeoForge 1.21.1 build on the user's
 * client, so the mixins that stored voxy's storage path in the replay metadata ({@code mixin/flashback/*}) and the
 * {@code IFlashbackMeta} duck interface are removed. The API surface used by {@code VoxyClientInstance} is kept:
 * {@link #getReplayStoragePath()} always returns null, which means "use the normal per-server storage path and keep
 * ingest enabled", exactly what upstream does when Flashback is not installed.
 */
public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = false;

    public static Path getReplayStoragePath() {
        return null;
    }
}
