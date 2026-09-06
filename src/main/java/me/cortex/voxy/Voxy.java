package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyConfigScreenFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.PlatformUtil;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge mod entry point (replaces the fabric.mod.json "main"/"client"/"modmenu" entrypoints).
 *
 * Voxy's real initialisation is driven by mixins and self-registering event subscribers:
 *  - {@code MixinRenderSystem.initRenderer} -> {@link me.cortex.voxy.client.VoxyClient#initVoxyClient()} (client)
 *  - {@link me.cortex.voxy.client.VoxyClientEvents} (client game bus: commands, LOD resync ticking)
 *  - {@code commonImpl.network.VoxyNetworkRegistration} (mod bus) and {@code LodResyncServer} (game bus) are
 *    {@code @EventBusSubscriber} classes and register themselves; nothing is wired from here.
 *
 * There is deliberately NO NeoForge TOML config: voxy keeps its own {@code voxy-config.json} + Sodium video
 * settings page (brief contract C17). The mod-list "Config" button opens that Sodium page through
 * {@link VoxyConfigScreenFactory}, exactly like the ModMenu integration did upstream.
 */
@Mod(PlatformUtil.MOD_ID)
public class Voxy {
    public Voxy(IEventBus modEventBus, ModContainer container) {
        Logger.info("Voxy " + VoxyCommon.MOD_VERSION + " constructing (" + FMLLoader.getDist() + ")");
        if (FMLLoader.getDist() == Dist.CLIENT) {
            registerClientExtensions(container);
        }
    }

    //Kept in a separate method so that the client-only extension classes are only resolved on the client dist
    private static void registerClientExtensions(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, new VoxyConfigScreenFactory());
    }
}
