package me.cortex.voxy.client.mixin.minecraft.session;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    //1.21.1: Minecraft has disconnect(), disconnect(Screen) and disconnect(Screen, boolean keepResourcePacks); the
    // first two delegate to the 2-arg overload which does the real teardown, so only that one is targeted
    // (ref/mc/net/minecraft/client/Minecraft.java:2138-2146).
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void voxy$injectWorldClose(CallbackInfo ci) {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
