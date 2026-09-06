package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.1 (contract C18): exposes the private {@code int mipLevel} of {@link TextureAtlas} (ref TextureAtlas.java:42),
 * there is no {@code maxMipLevel}; used by the model baking system (G4) to match the block atlas mip count.
 */
@Mixin(TextureAtlas.class)
public interface AccessorTextureAtlas {
    @Accessor("mipLevel")
    int voxy$getMipLevel();
}
