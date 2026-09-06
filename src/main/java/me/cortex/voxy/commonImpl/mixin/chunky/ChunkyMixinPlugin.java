package me.cortex.voxy.commonImpl.mixin.chunky;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the Chunky compatibility mixin (chunky.voxy.mixins.json) on Chunky actually being installed.
 * <p>
 * The mixin targets {@code org.popcraft.chunky.platform.NeoForgeWorld}, which does not exist when Chunky is
 * absent. Mixin config plugins are consulted during early loading, before {@code ModList} is populated, so this
 * queries {@link LoadingModList} instead (same approach as the Iris/worldgen plugins). Runs on both dists.
 */
public class ChunkyMixinPlugin implements IMixinConfigPlugin {
    private static final String CHUNKY_MOD_ID = "chunky";

    private boolean chunkyPresent;

    @Override
    public void onLoad(String mixinPackage) {
        boolean present;
        try {
            present = LoadingModList.get().getModFileById(CHUNKY_MOD_ID) != null;
        } catch (Throwable t) {
            // Fail closed: skipping the Chunky hook only loses automatic LOD ingest of pregenerated chunks
            present = false;
        }
        this.chunkyPresent = present;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.chunkyPresent;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
