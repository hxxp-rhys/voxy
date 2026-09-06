package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.21.1 (contract C7): Minecraft 1.21.1 has no {@code DebugScreenEntry}/{@code DebugScreenEntries}/
 * {@code DebugScreenEntryList} (that API is 1.21.9+), the F3 overlay is two plain {@code List<String>} columns
 * (ref DebugScreenOverlay.java:236 getGameInformation, :514 getSystemInformation) that NeoForge exposes through
 * {@link CustomizeGuiOverlayEvent.DebugText} (ref DebugScreenOverlay.java:141, CustomizeGuiOverlayEvent.java:121-142).
 * <p>
 * Dev registered three entries: {@code voxy:version} (the coloured version line, always on when the overlay is
 * visible), {@code voxy:debug} (instance + render debug groups, {@code VoxyDebugScreenEntry}) and {@code voxy:gpu_debug}
 * (an empty entry whose enabled state toggled GPU timing + render statistics and rebuilt the renderer). The first two
 * are reproduced on the right column below; the third has no per-entry toggle on 1.21.1 and becomes
 * {@link #setGpuDebug(boolean)} (driven by {@code /voxy debug gpu <true|false>} in VoxyCommands) plus the
 * {@code voxy.gpuDebug} system property read at class initialisation.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class DebugEntries {
    private static final String GPU_DEBUG_PROPERTY = "voxy.gpuDebug";

    private static boolean gpuDebugEnabled = false;
    //GPUTiming holds GL objects, only touch it on the render thread once a renderer can exist
    private static boolean gpuDebugPendingApply = false;

    static {
        if (System.getProperty(GPU_DEBUG_PROPERTY, "false").equalsIgnoreCase("true")) {
            gpuDebugEnabled = true;
            RenderStatistics.enabled = true;
            gpuDebugPendingApply = true;
        }
    }

    /** Kept for callers that want to force class initialisation (the property read above); no-op otherwise. */
    public static void init() {
    }

    public static boolean isGpuDebugEnabled() {
        return gpuDebugEnabled;
    }

    /**
     * Toggles GPU timing markers and the render statistics (HTC/HRS/VS/QC lines), then rebuilds the level renderer
     * like dev's {@code onRebuild} did (the traversal/command generation shaders are compiled with HAS_STATISTICS,
     * so the Voxy renderer must be recreated for the change to take effect). Must be called on the render thread.
     */
    public static void setGpuDebug(boolean enabled) {
        if (gpuDebugEnabled == enabled && !gpuDebugPendingApply) {
            return;
        }
        gpuDebugEnabled = enabled;
        gpuDebugPendingApply = false;

        GPUTiming.INSTANCE.setEnabled(enabled);
        RenderStatistics.enabled = enabled;
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) renderer.allChanged();// 1.21.1: LevelRenderer.allChanged() (ref LevelRenderer.java:716), no levelExtractor
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        var right = event.getRight();

        //voxy:version
        right.add("");
        if (!VoxyCommon.isAvailable()) {
            right.add(ChatFormatting.RED + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            right.add(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
            return;
        }
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        //Voxy instance active
        right.add((vrs == null ? ChatFormatting.DARK_GREEN : ChatFormatting.GREEN) + "voxy-" + VoxyCommon.MOD_VERSION);

        //voxy:debug (instance_debug + render_debug groups)
        try {
            List<String> instanceLines = new ArrayList<>();
            instance.addDebug(instanceLines);
            right.addAll(instanceLines);

            if (vrs != null) {
                if (gpuDebugPendingApply) {
                    //Apply the system property now that we are on the render thread with a live renderer
                    gpuDebugPendingApply = false;
                    GPUTiming.INSTANCE.setEnabled(gpuDebugEnabled);
                }
                List<String> renderLines = new ArrayList<>();
                vrs.addDebugInfo(renderLines);
                right.add("");
                right.addAll(renderLines);
            }
        } catch (Throwable t) {
            //The debug overlay must never take the game down with it
            right.add(ChatFormatting.RED + "Voxy: debug info unavailable (" + t.getClass().getSimpleName() + ")");
        }
    }
}
