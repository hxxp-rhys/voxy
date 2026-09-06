package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import me.cortex.voxy.commonImpl.PlatformUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

import java.io.IOException;

public class IrisUtil {

    // 1.21.1: Sodium 0.8.13 has no FogParameters class, the fog is carried by Voxy's own VoxyFogParameters (contract C1)
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, VoxyFogParameters parameters, int width, int height, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices.projection(), this.matrices.modelView(), this.parameters, this.width, this.height, this.x, this.y, this.z);
        }
    }

    public static boolean USED_IRIS_VIEWPORT;
    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    // 1.21.1: NeoForge has no FabricLoader, mod presence goes through the shared platform helper (contract C9).
    // Every call that actually touches an Iris class lives in a private *0 method so that net.irisshaders.* is never
    // resolved unless Iris is genuinely present.
    public static final boolean IRIS_INSTALLED = PlatformUtil.isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) clearIrisSamplers0();
    }
    public static void reload() {
        if (IRIS_INSTALLED) reload0();
    }

    private static void reload0() {
        try {
            if (IrisApi.getInstance().isShaderPackInUse()||IrisApi.getInstance().getConfig().areShadersEnabled()) {//Only reload if there is a shaderpack
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearIrisSamplers0() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        return Iris.getCurrentPack().isPresent();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }
    private static boolean irisShadersEnabledInConfig0() {
        return !Iris.getCurrentPack().isEmpty();
    }
    public static boolean irisShadersEnabledInConfig() {
        return IRIS_INSTALLED && irisShadersEnabledInConfig0();
    }
    public static void disableIrisShaders() {
        if(IRIS_INSTALLED) disableIrisShaders0();
    }
    private static void disableIrisShaders0() {
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);//Disable shaders
    }
}
