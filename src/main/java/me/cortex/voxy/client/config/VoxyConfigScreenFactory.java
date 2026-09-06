package me.cortex.voxy.client.config;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * NeoForge mod-list "Config" button -> Voxy's page inside Sodium's video settings screen.
 *
 * 1.21.1: replaces {@code ModMenuIntegration} (ModMenu is a Fabric-only mod, brief contract C16). The lookup is
 * identical to what ModMenuIntegration did: find the {@code ModOptions} registered under config id "voxy" by
 * {@link VoxyConfigMenu} (Sodium instantiates it via the {@code sodium:config_api_user} mod property) and open
 * {@link VideoSettingsScreen#createScreen(Screen, OptionPage)} on its first page.
 *
 * Returning null is safe: {@code ModListScreen} maps the factory result through an {@code Optional} and simply
 * does nothing when the screen is null (ref/mc/net/neoforged/neoforge/client/gui/ModListScreen.java:299), which
 * mirrors ModMenu's behaviour for the null returned by ModMenuIntegration when voxy is unavailable.
 */
public class VoxyConfigScreenFactory implements IConfigScreenFactory {
    @Override
    public Screen createScreen(ModContainer container, Screen parent) {
        if (!VoxyCommon.isAvailable()) {
            return null;
        }
        try {
            var config = ConfigManager.CONFIG;
            if (config == null) {
                Logger.warn("Sodium config not built yet, cannot open the voxy settings page");
                return null;
            }
            var modOptions = config.getModOptions().stream()
                    .filter(a -> a.configId().equals("voxy"))
                    .findFirst()
                    .orElse(null);
            if (modOptions == null || modOptions.pages().isEmpty()) {
                Logger.warn("Voxy has no registered sodium options page, cannot open the voxy settings page");
                return null;
            }
            if (!(modOptions.pages().get(0) instanceof OptionPage page)) {
                Logger.warn("First voxy sodium page is not an option page, cannot open the voxy settings page");
                return null;
            }
            return VideoSettingsScreen.createScreen(parent, page);
        } catch (Throwable t) {
            //Never let a config-screen lookup crash the mod list
            Logger.error("Failed to open the voxy settings page", t);
            return null;
        }
    }
}
