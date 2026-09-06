package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IGetVoxyPatchData;
import me.cortex.voxy.client.iris.IrisShaderPatch;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline implements IGetVoxyPatchData, IGetIrisVoxyPipelineData {
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Unique IrisShaderPatch patchData;
    @Unique
    IrisVoxyRenderPipelineData pipeline;

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/transform/ShaderPrinter;resetPrintState()V", shift = At.Shift.AFTER))
    private void voxy$injectPatchDataStore(ProgramSet programSet, CallbackInfo ci) {
        if (IrisUtil.SHADER_SUPPORT) {
            this.patchData = ((IGetVoxyPatchData) programSet).voxy$getPatchData();
        }
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;createSetupComputes([Lnet/irisshaders/iris/shaderpack/programs/ComputeSource;Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;Lnet/irisshaders/iris/shaderpack/texture/TextureStage;)[Lnet/irisshaders/iris/gl/program/ComputeProgram;"))
    private void voxy$injectPipeline(ProgramSet programSet, CallbackInfo ci) {
        if (this.patchData != null) {
            this.pipeline = IrisVoxyRenderPipelineData.buildPipeline((IrisRenderingPipeline)(Object)this, this.patchData, this.customUniforms, this.shaderStorageBufferHolder);
        }
    }

    // 1.21.1: com.mojang.blaze3d.opengl does not exist (GlStateManager lives in com.mojang.blaze3d.platform) and Iris 1.8.14's
    // beginLevelRendering never calls GlStateManager directly: the "make sure we're using texture unit 0" call is
    // RenderSystem.activeTexture(GL_TEXTURE0) (IrisRenderingPipeline.java:859, the only such invoke in the method), so that
    // is the injection point. It runs right after RenderSystem.clear in LevelRenderer.renderLevel (iris MixinLevelRenderer).
    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V", shift = At.Shift.BEFORE), remap = false)
    private void voxy$injectViewportSetup(CallbackInfo ci) {
        if (IrisUtil.CAPTURED_VIEWPORT_PARAMETERS != null) {
            var renderer = IVoxyRenderSystemHolder.getNullable();
            if (renderer != null) {
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS.apply(renderer);
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = null;
                IrisUtil.USED_IRIS_VIEWPORT = true;
            }
        }
    }

    @Override
    public IrisShaderPatch voxy$getPatchData() {
        return this.patchData;
    }

    @Override
    public IrisVoxyRenderPipelineData voxy$getPipelineData() {
        return this.pipeline;
    }
}
