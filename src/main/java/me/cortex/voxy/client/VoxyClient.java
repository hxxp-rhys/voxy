package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;

/**
 * Client bootstrap.
 *
 * 1.21.1/NeoForge: no longer a Fabric {@code ClientModInitializer}. {@link #initVoxyClient()} is still invoked from
 * {@code MixinRenderSystem.initRenderer} (RETURN, order 900 so it runs before Iris). Client command registration
 * moved to {@link VoxyClientEvents} (NeoForge {@code RegisterClientCommandsEvent}); the F3 debug entries are
 * provided by {@link DebugEntries} through NeoForge's {@code CustomizeGuiOverlayEvent.DebugText}.
 */
public class VoxyClient {
    //FREX "flawless frames" is a Fabric entrypoint API ("frex_flawless_frames" in fabric.mod.json); no NeoForge 1.21.1
    // mod provides an equivalent, so this set is never populated on this platform. The machinery
    // (isFrexActive() and its consumers in VoxyRenderSystem) is kept intact so the render code is unchanged.
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    /**
     * Whether a FREX flawless-frames consumer is active. Always false on NeoForge 1.21.1: the
     * {@code frex_flawless_frames} entrypoint is a Fabric-loader entrypoint contract with no NeoForge counterpart
     * (brief contract C16). Kept so the render system's "process everything before presenting" paths still exist.
     */
    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
