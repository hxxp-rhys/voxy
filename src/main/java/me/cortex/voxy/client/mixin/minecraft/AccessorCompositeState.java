package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//RenderType.CompositeState.textureState is package private
@Mixin(RenderType.CompositeState.class)
public interface AccessorCompositeState {
    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard voxy$getTextureState();
}
