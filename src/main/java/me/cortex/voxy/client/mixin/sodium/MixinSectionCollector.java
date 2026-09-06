package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.1: replaces MixinVisibleChunkCollector/MixinFallbackVisibleChunkCollector. Sodium 0.8.13 has no
// VisibleChunkCollector/FallbackVisibleChunkCollector, both the occlusion graph search (OcclusionSectionCollector) and
// the out-of-graph tree traversal (TreeSectionCollector) feed every visited section through the private
// SectionCollector.visit(RenderSection, int), as do the incremental visitWithFlags calls for freshly built sections.
@Mixin(value = SectionCollector.class, remap = false)
public class MixinSectionCollector {
    @Inject(method = "visit(Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;I)V", at = @At("HEAD"))
    private void voxy$injectVisibleSectionGather(RenderSection section, int flags, CallbackInfo ci) {
        VoxyRenderSystem vrs;
        // Sodium 0.9's RenderSectionFlags.MASK_IS_BUILT is set for any BuiltSectionInfo (empty sections included) and
        // cleared when the section is unloaded, in 0.8.13 that is exactly RenderSection.isBuilt()
        if (!IrisUtil.irisShadowActive() && section.isBuilt() && (vrs = IVoxyRenderSystemHolder.getNullable()) != null && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.put(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
    }
}
