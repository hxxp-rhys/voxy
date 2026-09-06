package me.cortex.voxy.client.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates the voxyworldgenv2 compatibility mixins on that mod actually being installed —
 * same pattern as the Iris config plugin (VoxyIrisMixinPlugin, owned by the Iris integration). The targets are third-party classes
 * (com.ethan.voxyworldgenv2.*) that do not exist otherwise.
 */
public class VoxyWorldgenMixinPlugin implements IMixinConfigPlugin {

    private boolean present;

    @Override
    public void onLoad(String mixinPackage) {
        boolean p;
        try {
            p = LoadingModList.get().getModFileById("voxyworldgenv2") != null;
        } catch (Throwable t) {
            p = false;
        }
        this.present = p;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return this.present;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    /**
     * Verify — in the transformed bytecode — that the queue redirects actually bound.
     * <p>
     * Both are declared {@code require = 0} so a voxyworldgenv2 update that renames or
     * restructures those methods degrades instead of crashing the game. The cost of that
     * choice is silence: a redirect that matches nothing looks exactly like one that
     * worked. This port has already shipped four separate defects of that shape, so the
     * fail-soft path pays for itself with a log line. After a successful apply, no
     * {@code ArrayDeque.pollFirst} call should remain in the target — each is replaced by
     * an invocation of the redirect handler.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!targetClassName.endsWith("NetworkClientHandler")) return;
        int remaining = 0, redirected = 0;
        for (MethodNode m : targetClass.methods) {
            if (m.instructions == null) continue;
            for (var insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode min)) continue;
                if ("java/util/ArrayDeque".equals(min.owner) && "pollFirst".equals(min.name)) remaining++;
                else if (min.name.contains("voxy$drop")) redirected++; // Mixin merges @Redirect handlers as redirect$<hash>$voxy$drop...
            }
        }
        System.out.println("[voxy] worldgen queue redirects applied to " + targetClassName
                + ": " + redirected + " bound, " + remaining + " unredirected pollFirst remaining"
                + (remaining == 0 && redirected == 2 ? " (OK)"
                   : " (UNEXPECTED - voxyworldgenv2 may have changed; near-player LOD holes will not be mitigated)"));
    }
}
