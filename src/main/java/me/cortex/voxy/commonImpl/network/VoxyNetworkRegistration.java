package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.client.LodResyncClient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the voxy:lod_resync_* payloads. MOD-bus, both dists — the payload API
 * is common. Registered .optional() so connections succeed when either side lacks
 * the channel; actual sends are guarded (server only sends ResyncReady after a
 * try/catch probe, the client only sends requests after receiving ResyncReady).
 *
 * Server-safety note (this port's discipline): LodResyncClient is deliberately
 * client-import-free — its handler only stores an int + flag — so referencing it
 * from this common class is safe on a dedicated server.
 */
@EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD)
public final class VoxyNetworkRegistration {
    private VoxyNetworkRegistration() {}

    /** Bump on ANY change to a payload codec below. See onRegisterPayloads. */
    public static final String PROTOCOL_VERSION = "1";

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // NOTE: the argument to registrar() is the CHANNEL VERSION, not a namespace.
        // NetworkComponentNegotiator.validateComponent compares it exactly, and a mismatch
        // fails the whole negotiation — optional() exempts a MISSING channel, not a
        // version-mismatched one. So it must be bumped whenever any payload's codec here
        // changes shape, otherwise two builds negotiate happily and then blow up in
        // PacketDecoder (leftover bytes -> IOException -> disconnect) mid-session. It read
        // "voxy" until ResyncReady gained its second field; bump it again for the next
        // breaking change.
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(LodResyncPayloads.ResyncReady.TYPE, LodResyncPayloads.ResyncReady.CODEC,
                (payload, ctx) -> LodResyncClient.onReady(payload.radiusChunks(), payload.serveRatePerSecond()));
        registrar.playToServer(LodResyncPayloads.ResyncRequest.TYPE, LodResyncPayloads.ResyncRequest.CODEC,
                LodResyncServer::onRequest);
    }
}
