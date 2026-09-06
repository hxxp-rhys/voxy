package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Client game-bus subscriber (brief contract C8/C10/C13).
 *
 * 1.21.1: replaces Fabric's {@code ClientCommandRegistrationCallback} (previously registered from
 * {@code VoxyClient.onInitializeClient}) and drives the RMN LOD-resync verifier lifecycle. Dist-gated so FML never
 * initialises this class (and the client classes it references) on a dedicated server.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClientEvents {
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    /** Drives the LOD resync verifier (starts when the server announces support, stops when leaving the world). */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LodResyncVerifier.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LodResyncVerifier.onDisconnect();
    }
}
