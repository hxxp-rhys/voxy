package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    // 1.21.1: Sodium 0.8.13 ShaderChunkRenderer(RenderDevice, ChunkVertexType)
    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    // 1.21.1: Sodium 0.8.13 render(ChunkRenderMatrices, CommandList, ChunkRenderListIterable, TerrainRenderPass, CameraTransform, boolean)
    // there is no FogParameters/GpuSampler/uniform buffer argument, begin(TerrainRenderPass)/end(TerrainRenderPass)
    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
        }
    }

    // Deferred translucent LODs (voxy.json "deferTranslucentRendering"): drawn at the very start of the TRANSLUCENT pass,
    // before Sodium's begin() binds its program/framebuffer (so nothing of Sodium's setup is disturbed) and before any
    // translucent terrain, i.e. after entities/block entities and, under Iris, after copyPreTranslucentDepth and the
    // deferred programs. The vanilla depth of that moment is the main render target's depth texture, which every Iris
    // gbuffer framebuffer shares (see VoxyRenderSystem.renderDeferredTranslucent).
    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$deferredTranslucent(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        if (renderPass == DefaultTerrainRenderPasses.TRANSLUCENT && !IrisUtil.irisShadowActive()) {
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                renderer.renderDeferredTranslucent();
            }
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci) {
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            // 1.21.1: Iris 1.8.14 renders its shadow pass (ShadowRenderer.ACTIVE) through this very CUTOUT pass BEFORE the
            // main terrain pass (renderShadows runs at the renderSky call, i.e. before FogRenderer.setupFog(FOG_TERRAIN) and
            // before renderSectionLayer(solid)). Voxy never rendered anything here while the shadow pass is active (getViewport()
            // is null then, so renderOpaque returned early), but consuming USED_IRIS_VIEWPORT in that pass would break the iris
            // viewport protocol: the iris MixinLevelRenderer needs the flag to still be set when it writes this frame's terrain
            // fog into the viewport that IrisRenderingPipeline.beginLevelRendering applied, and the main CUTOUT pass must then
            // reuse that viewport instead of rebuilding it. So the shadow pass is skipped entirely (no framebuffer query either).
            if (IrisUtil.irisShadowActive()) {
                return;
            }
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                // 1.21.1: TerrainRenderPass has no render target, the depth/colour textures and the size are taken
                // from the currently bound draw framebuffer (contract C3: {depthTex, colourTex, width, height})
                int[] target = VoxyRenderSystem.getBoundFramebufferTextures();
                if (target == null) {
                    return;//Not a framebuffer the LODs can be composited into (see getBoundFramebufferTextures)
                }
                Viewport<?> viewport = null;
                if (IrisUtil.USED_IRIS_VIEWPORT) {
                    viewport = renderer.getViewport();
                    IrisUtil.USED_IRIS_VIEWPORT = false;
                } else {
                    // 1.21.1: no Sodium FogParameters, the fog is read from RenderSystem.getShaderFog* (contract C1)
                    viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), VoxyFogParameters.current(), target[2], target[3], camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport, target[0], target[1]);
            }
        }
    }
}
