package me.cortex.voxy.client.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the Iris compatibility mixins (iris.voxy.mixins.json) on Iris actually being installed.
 * <p>
 * The Iris mixins target {@code net.irisshaders.*} classes, which do not exist when Iris is absent; applying them
 * unconditionally would hard-crash startup for anyone running Voxy without shaders. Mixin config plugins are consulted
 * during early loading, before {@code ModList} is populated, so this queries {@link LoadingModList} instead
 * (contract C14 / C9).
 */
public class VoxyIrisMixinPlugin implements IMixinConfigPlugin {

    private static final String IRIS_MOD_ID = "iris";

    private boolean irisPresent;

    @Override
    public void onLoad(String mixinPackage) {
        boolean present;
        try {
            present = LoadingModList.get().getModFileById(IRIS_MOD_ID) != null;
        } catch (Throwable t) {
            // If the loading list is unavailable for any reason, fail closed:
            // skipping shader integration is always survivable, crashing is not.
            present = false;
        }
        this.irisPresent = present;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.irisPresent;
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
