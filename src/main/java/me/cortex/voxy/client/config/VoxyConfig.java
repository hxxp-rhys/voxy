package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.PlatformUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * voxy-config.json (Gson). This is THE config on NeoForge too (brief contract C17): no TOML config exists.
 *
 * Server safety (brief contract C11): voxyworldgenv2 reflects this class on a dedicated server (initialising
 * Class.forName + {@link #isEnabled()}), so the static initialiser must not touch client classes or LWJGL
 * ({@link CpuLayout} is Throwable-safe) and {@link #save()} must be a no-op when voxy is unavailable. The
 * {@code NormalRenderPipeline.FogMode}/{@code SSAO.SSAOMode} references only appear in method bodies/signatures
 * and are resolved lazily by the JVM, never during class initialisation.
 */
public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public String fogMode;
    public boolean dontUseSodiumBuilderThreads = false;
    public String ssaoMode;

    //RMN LOD resync (brief contract C13; exact field names used by client.LodResyncVerifier / LodResyncClient).
    // Verifies the local LOD database ring-by-ring outward from the player and re-requests cells lost to
    // voxyworldgenv2's fire-and-forget sends (see commonImpl.network.LodResyncPayloads). Requires voxy on the server.
    public boolean lodResyncEnabled = true;
    /**
     * Hard cap on the verified radius, in CHUNKS, regardless of what the server announces. voxyworldgenv2's wire
     * format sends full-resolution chunk sections at every distance, so cost scales with area (~1.6 GB to 256).
     */
    public int lodResyncMaxRadiusChunks = 256;
    /**
     * Re-request attempts per 32x32 cell before the cell is written off as a genuinely empty column. The server
     * drains a fixed budget per tick and silently drops anything past its queue cap while the client counts an
     * attempt on SEND, so a low cap abandons cells whose requests the server simply discarded.
     */
    public int lodResyncMaxAttempts = 5;
    /**
     * How long (ms) a cell must be continuously absent from the local LOD database before it is treated as a real
     * hole and re-requested; covers the asynchronous save delay after ingest. 0 disables the delay.
     */
    public long lodResyncGraceMs = 15000L;
    // NB: the server-side re-serve budget deliberately does NOT live here. On a dedicated server
    // VoxyCommon.isAvailable() is false, so this file is never read or written there; it lives in
    // config/voxy-lod-resync.json (commonImpl.network.LodResyncConfig) instead.

    public SSAO.SSAOMode getSSAOMode() {
        var DEFAULT = SSAO.SSAOMode.AUTO;
        if (this.ssaoMode == null) return DEFAULT;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }


    public NormalRenderPipeline.FogMode getFogMode() {
        var DEFAULT = NormalRenderPipeline.FogMode.FOG_AND_FADE;
        if (this.fogMode == null) return DEFAULT;
        try {
            return NormalRenderPipeline.FogMode.valueOf(this.fogMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return DEFAULT;}
    }

    public void setFogMode(NormalRenderPipeline.FogMode mode) {
        this.fogMode = mode.name().toLowerCase(Locale.ROOT);
    }


    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not load config", e);
                } catch (JsonParseException e) {
                    Logger.error("Could not parse config", e);
                }
                Logger.info("Error during config loading, creating new");
            } else {
                Logger.info("Config file doesnt exist, creating new");
            }
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    /**
     * Static probe used by third-party integrations (brief contract C11/C12): voxyworldgenv2's VoxyIntegration
     * reflects {@code VoxyConfig.isEnabled()}. Semantics match voxyworldgenv2's own convention ("no usable voxy =>
     * keep generating"): true on a dedicated server / unsupported GL (voxy unavailable), otherwise the user's toggle.
     */
    public static boolean isEnabled() {
        return !VoxyCommon.isAvailable() || (CONFIG != null && CONFIG.enabled);
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        //1.21.1: FabricLoader.getConfigDir() -> FMLPaths.CONFIGDIR (through the loader shim)
        return PlatformUtil.configDir()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
