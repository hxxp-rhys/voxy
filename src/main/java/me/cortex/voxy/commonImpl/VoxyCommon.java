package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;

/**
 * Common (client + dedicated server) state of voxy.
 *
 * 1.21.1/NeoForge: no longer a Fabric {@code ModInitializer}; the mod entry point is {@link me.cortex.voxy.Voxy}.
 * This class is initialised very early (from mixin code such as {@code MixinWorld}/{@code WorldIdentifier} or
 * {@code MixinRenderSystem}, i.e. possibly before {@code ModList} exists), so every loader query goes through
 * {@link PlatformUtil} which only relies on {@code LoadingModList}/{@code FMLLoader}. Nothing here may touch a
 * client class: on a dedicated server this class is loaded through {@code Level} construction.
 */
public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        if (!PlatformUtil.isInMinecraft()) {
            IS_IN_MINECRAFT = false;
            Logger.error("Running voxy without minecraft");
            MOD_VERSION = "<UNKNOWN>";
            IS_DEDICATED_SERVER = false;
        } else {
            IS_IN_MINECRAFT = true;
            var version = PlatformUtil.modVersion();
            var commit = PlatformUtil.commitHash();
            MOD_VERSION = version + "-" + (commit.length() > 7 ? commit.substring(0, 7) : commit);
            IS_DEDICATED_SERVER = PlatformUtil.isDedicatedServer();
            Serialization.init();
        }
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            //Logger.info("Voxy factory");
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
