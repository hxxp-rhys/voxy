package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyFogEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Companion of {@link VoxyFogEvents} (contract C2 on 1.21.1).
 * <p>
 * HEAD: reports the parameters of every {@code FogRenderer.setupFog} call so the event handler can recognise the level
 * renderer's terrain call (ref LevelRenderer.java:966) and its far plane.
 * <p>
 * TAIL, mixin priority 1500: Mixin applies mixins in ascending priority order and inserts each TAIL callback directly
 * before the method's return, so this callback runs after the default-priority (1000) TAIL injectors of other mods -
 * in particular sodium-extra's {@code fog.MixinFogRenderer.sodiumExtra$applyFog} (sodium-extra 0.9.3, default
 * priority), which rewrites {@code RenderSystem} fog start/end from its "fog distance" option AFTER NeoForge's
 * {@code ViewportEvent.RenderFog} applied Voxy's push. Re-asserting the push here keeps the render-distance fog wall
 * away from the LODs regardless of that setting.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public class MixinFogRenderer {
    // ref FogRenderer.java:220 public static void setupFog(Camera, FogMode, float farPlaneDistance, boolean shouldCreateFog, float partialTick)
    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", at = @At("HEAD"))
    private static void voxy$beginSetupFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        VoxyFogEvents.beginSetupFog(fogMode, farPlaneDistance);
    }

    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V", at = @At("TAIL"))
    private static void voxy$endSetupFog(Camera camera, FogRenderer.FogMode fogMode, float farPlaneDistance, boolean shouldCreateFog, float partialTick, CallbackInfo ci) {
        VoxyFogEvents.endSetupFog();
    }
}
