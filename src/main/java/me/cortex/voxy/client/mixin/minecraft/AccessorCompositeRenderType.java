package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//RenderType.CompositeRenderType is package private, its state field is private
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface AccessorCompositeRenderType {
    @Accessor("state")
    RenderType.CompositeState voxy$getState();
}
