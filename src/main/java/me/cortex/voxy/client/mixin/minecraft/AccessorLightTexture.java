package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.21.1 (contract C18): exposes the private {@code DynamicTexture lightTexture} field of {@link LightTexture}
 * (ref LightTexture.java:23) so {@code LightMapHelper} can bind the light map by GL id (same as the prior port).
 */
@Mixin(LightTexture.class)
public interface AccessorLightTexture {
    @Accessor("lightTexture")
    DynamicTexture voxy$getLightTexture();
}
