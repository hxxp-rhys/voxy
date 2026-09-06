package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableSet;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends the set of colour textures a shader pack may address through voxy.json draw buffers.
 * <p>
 * 1.21.1: upstream dropped this mixin because Iris 1.9+ defaults to 32 render targets, but Iris 1.8.14 still has
 * {@code IrisLimits.MAX_COLOR_BUFFERS = 16}, so {@code BASELINE_SUPPORTED_RENDER_TARGETS} only covers colortex0..15 and
 * {@code RenderTargets.targets} is sized from that map. A pack whose voxy.json writes to a higher target (the user's
 * photon rmnfix uses {@code "translucentDrawBuffers": [13, 16]}) would hit an ArrayIndexOutOfBoundsException in
 * {@code RenderTargets.getOrCreate(16)} when the voxy pipeline data is built. Extending the baseline (16..19, or up to
 * 199 with -Dvoxy.IrisExtremeColourTexOverride=true) is what the prior port and Roxy (20 targets) do on this Iris line;
 * the extra targets are allocated lazily by Iris so unused ones cost nothing.
 */
@Mixin(value = PackRenderTargetDirectives.class, remap = false)
public class MixinPackRenderTargetDirectives {
    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableSet$Builder;build()Lcom/google/common/collect/ImmutableSet;"))
    private static ImmutableSet<Integer> voxy$injectExtraColourTex(ImmutableSet.Builder<Integer> builder) {
        int limit = System.getProperty("voxy.IrisExtremeColourTexOverride", "false").equalsIgnoreCase("true")?200:20;
        for (int i = 16; i < limit; i++) {
            builder.add(i);
        }
        return builder.build();
    }
}
