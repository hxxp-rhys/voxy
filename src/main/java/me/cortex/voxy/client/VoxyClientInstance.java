package me.cortex.voxy.client;

import me.cortex.voxy.client.compat.FlashbackCompat;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;

    //Java 21: upstream computes the storage config in a prologue that runs before super() (JEP 447 "flexible
    // constructor bodies", Java 22+ preview / Java 25 final). Java 21 requires super() to be the first statement, so
    // the prologue is hoisted into a static factory whose result is delegated through this(...). The VoxyInstance
    // constructor calls shouldCreateInstance() before any field of this class is assigned, so the prepared state is
    // handed to it through a thread local (instances are only ever created on the render thread via
    // VoxyCommon.createInstance()).
    private record PreparedState(Config config, Path basePath, boolean noIngestOverride) {}
    private static final ThreadLocal<PreparedState> PREPARED = new ThreadLocal<>();

    public VoxyClientInstance() {
        this(prepare());
    }

    private VoxyClientInstance(PreparedState state) {
        super();
        PREPARED.remove();
        this.config = state.config;
        this.basePath = state.basePath;
        this.noIngestOverride = state.noIngestOverride;
        this.updateDedicatedThreads();
    }

    private static PreparedState prepare() {
        var path = FlashbackCompat.getReplayStoragePath();
        boolean noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        var basePath = path.normalize();
        var config = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==1&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, basePath);
        var state = new PreparedState(config, basePath, noIngestOverride);
        PREPARED.set(state);
        return state;
    }

    @Override
    protected boolean shouldCreateInstance() {
        //Called from the VoxyInstance constructor, i.e. before this.config is assigned
        var state = PREPARED.get();
        PREPARED.remove(); // consumed here so the thread-local never lingers when the ctor throws DontCreateInstance
        var config = state != null ? state.config : this.config;
        return config == null || !config.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            var swr = SodiumWorldRenderer.instanceNullable();
            if (swr != null) {
                var rsm = ((AccessorSodiumWorldRenderer) swr).getRenderSectionManager();
                if (rsm != null) {
                    this.setNumThreads(Math.max(1, target - rsm.getBuilder().getTotalThreadCount()));
                    return;
                }
            }
        }
        this.setNumThreads(target);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().gameMode;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                //MultiPlayerGameMode.connection is private on 1.21.1, opened by the access transformer
                var info = netHandle.connection.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.ip.replace(":", "_"));
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
