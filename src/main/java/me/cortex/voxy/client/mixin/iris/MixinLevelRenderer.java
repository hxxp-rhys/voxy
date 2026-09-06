package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.glViewport;

/**
 * Captures the viewport parameters that the iris pipeline applies in {@code IrisRenderingPipeline.beginLevelRendering}
 * (see {@link MixinIrisRenderingPipeline}).
 * <p>
 * 1.21.1 notes:
 * <ul>
 *   <li>{@code LevelRenderer.renderLevel(DeltaTracker, boolean, Camera, GameRenderer, LightTexture, Matrix4f frustumMatrix,
 *       Matrix4f projectionMatrix)} (ref LevelRenderer.java:921-923) replaces 26.2's {@code render(...)}: there is no
 *       {@code CameraRenderState}, no {@code GpuBufferSlice} fog buffer and no {@code GraphicsResourceAllocator}.
 *       {@code frustumMatrix} is the model view (camera rotation) matrix and {@code projectionMatrix} the projection, the
 *       same pair Iris feeds to its gbuffer matrices (iris MixinLevelRenderer.java:89-91); {@code camera.getPosition()} is
 *       the camera position. Width/height are those of the main render target, which is the framebuffer bound for drawing
 *       at this point (Iris only rebinds its gbuffers in beginLevelRendering, after RenderSystem.clear at :957).</li>
 *   <li>Sodium 0.8.13 has no {@code GameRendererStorage}/{@code FogParameters}: fog is read from {@code RenderSystem} through
 *       {@code VoxyFogParameters.current()} (contract C1). On 1.21.1 the terrain fog is only computed INSIDE renderLevel
 *       ({@code FogRenderer.setupFog(FOG_TERRAIN)}, ref LevelRenderer.java:966, i.e. after {@code RenderSystem.clear} (:957)
 *       where Iris runs {@code beginLevelRendering}), and the previous frame ended with {@code FogRenderer.setupNoFog()}
 *       (ref LevelRenderer.java:1246, fog start = {@code Float.MAX_VALUE}). The value captured at HEAD is therefore only a
 *       "no fog" placeholder so the viewport is never created with a null fog; the real terrain fog - the one left on the
 *       render system after NeoForge's {@code ViewportEvent.RenderFog} (ref FogRenderer.java:282 -> ClientHooks.java:447-459)
 *       and therefore after {@code VoxyFogEvents} pushed the vanilla fog wall away (contract C2), i.e. the value dev read from
 *       {@code sodium$getFogParameters()} - is written into the already applied viewport right after that setup and before
 *       any terrain (and thus before Voxy's opaque pass in the CUTOUT layer) is rendered.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
    private void voxy$injectIrisCompat(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var renderer = IVoxyRenderSystemHolder.getNullableHolder();
            if (renderer != null) {
                //Fixthe fucking viewport dims, fuck iris
                var mainTarget = Minecraft.getInstance().getMainRenderTarget();
                glViewport(0,0, mainTarget.width, mainTarget.height);

                var pos = camera.getPosition();
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(new ChunkRenderMatrices(projectionMatrix, frustumMatrix), VoxyFogParameters.current(), mainTarget.width, mainTarget.height, pos.x, pos.y, pos.z);
            }
        }
    }

    // 1.21.1: the FOG_TERRAIN setup (ref LevelRenderer.java:966) is immediately followed by profiler.popPush("terrain_setup")
    // (:967, the only use of that string in LevelRenderer), so the "terrain_setup" string constant is the first instruction
    // executed once this frame's terrain fog is on the render system. It is anchored on the constant rather than on the
    // setupFog invoke (FOG_SKY at :960 would be ordinal 0, the renderSky lambda at :963 is a synthetic method, FOG_TERRAIN
    // ordinal 1) because iris.voxy.mixins.json applies with defaultRequire=1: a third-party @Redirect/@WrapOperation of
    // either setupFog call would change the invoke ordinals and turn a miss into a startup crash, whereas no injector
    // rewrites an LDC. Iris anchors on a profiler constant in this very method the same way (iris MixinLevelRenderer.java:290,
    // "translucent"). Once here the RenderSystem holds this frame's terrain fog, which is what Voxy's normal-pipeline
    // fallback (a pack without voxy.json, NormalRenderPipeline.finish) consumes.
    @Inject(method = "renderLevel", at = @At(value = "CONSTANT", args = "stringValue=terrain_setup"))
    private void voxy$injectIrisFogCapture(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (IrisUtil.USED_IRIS_VIEWPORT) {//Only when the iris pipeline applied the captured viewport this frame
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                var viewport = renderer.getViewport();
                if (viewport != null) {
                    viewport.setFogParameters(VoxyFogParameters.current());
                }
            }
        }
    }
}
