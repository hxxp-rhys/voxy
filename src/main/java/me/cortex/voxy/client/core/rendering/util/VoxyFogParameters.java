package me.cortex.voxy.client.core.rendering.util;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jetbrains.annotations.Nullable;

/**
 * 1.21.1: replacement for Sodium 0.9's {@code net.caffeinemc.mods.sodium.client.util.FogParameters} (contract C1).
 * <p>
 * Minecraft 1.21.1 only has a single fog range (start/end) that {@code FogRenderer.setupFog} writes into
 * {@code RenderSystem.setShaderFogStart/End} (ref FogRenderer.java:279-280) and a fog colour written by
 * {@code FogRenderer.levelFogColor} (ref FogRenderer.java:285-286). There is no environmental / render-distance split,
 * so the single vanilla range is exposed through the {@code environmentalStart/End} accessors that the rest of the
 * render core (NormalRenderPipeline, IrisUtil) already uses.
 * <p>
 * See {@link me.cortex.voxy.client.VoxyFogEvents} for how the vanilla terrain fog is classified and pushed away on
 * 1.21.1 (contract C2). Which accessor a render hook must use:
 * <ul>
 *   <li>{@link #current()} - the fog that is on the render system NOW. This is what the Sodium (CUTOUT pass,
 *       {@code MixinDefaultChunkRenderer}) and Iris ({@code mixin/iris/MixinLevelRenderer}, right after the FOG_TERRAIN
 *       {@code setupFog}) hooks must hand to {@code VoxyRenderSystem.setupViewport} / {@code Viewport.setFogParameters}.
 *       By then {@code VoxyFogEvents} has already run (the NeoForge event fires at the end of {@code setupFog},
 *       ref FogRenderer.java:282 / ClientHooks.java:453-458, before the terrain layers at LevelRenderer.java:972-976),
 *       so the render-distance fog <b>wall</b> is gone (start = end = {@link #NO_FOG_DISTANCE}) and only a kept
 *       environmental fog (thick nether/boss fog, effect fog) is still present - exactly the "environmental fog"
 *       that dev's {@code MixinFogRenderer} left in {@code FogData.environmentalStart/End} for the LOD fog.</li>
 *   <li>{@link #lastTerrainFog()} - the vanilla-computed values captured BEFORE the push, for diagnostics or for code
 *       that must know the vanilla wall distance. It must NOT be fed to the LOD fog: {@code NormalRenderPipeline.finish}
 *       fogs the LOD blit from {@code environmentalStart} to {@code environmentalEnd} and the blit shader clamps the
 *       fog factor to 1 past {@code environmentalEnd} (blit_texture_depth_cutout.frag:54-58), so the 1.21.1 wall
 *       (start = far - clamp(far/10, 4, 64), end = far, ref FogRenderer.java:272-274) would paint every LOD beyond the
 *       vanilla render distance in solid fog colour. That is precisely what happened to Voxy 0.2.16 under Roxy with
 *       {@code useEnvironmentalFog = true} (Roxy only pushed the fog when it was false, RoxyVoxyFogPatch.java:31-37)
 *       and why Roxy forced that option off (RoxyBytecodeRemapper.patchVoxyConfigDefaults).</li>
 * </ul>
 */
public record VoxyFogParameters(float environmentalStart, float environmentalEnd, float red, float green, float blue, float alpha) {
    /** Distance the vanilla fog is pushed to when Voxy removes it (mirrors Roxy's RoxyVoxyFogPatch.NO_FOG = 1.0E9F). */
    public static final float NO_FOG_DISTANCE = 1.0E9F;

    /** The fog that vanilla computed for the FOG_TERRAIN pass of the current frame, captured before Voxy pushed it away. */
    private static volatile @Nullable VoxyFogParameters LAST_TERRAIN_FOG;

    public static VoxyFogParameters of(float start, float end, @Nullable float[] rgba) {
        if (rgba == null || rgba.length < 3) {
            return new VoxyFogParameters(start, end, 0.0f, 0.0f, 0.0f, 1.0f);
        }
        return new VoxyFogParameters(start, end, rgba[0], rgba[1], rgba[2], rgba.length > 3 ? rgba[3] : 1.0f);
    }

    /**
     * The fog currently set on the render system: {@code RenderSystem.getShaderFogStart()/getShaderFogEnd()/getShaderFogColor()}
     * (ref RenderSystem.java:345,369,386). This is what Sodium's terrain shaders sample as well
     * (Sodium 0.8.13 ChunkShaderFogComponent.java:48-52), so it is the right thing to hand to
     * {@code VoxyRenderSystem.setupViewport} from the Sodium/Iris render hooks (see the class javadoc for why the
     * post-push value is the correct LOD fog input and {@link #lastTerrainFog()} is not).
     */
    public static VoxyFogParameters current() {
        return of(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogColor());
    }

    /**
     * The vanilla-computed FOG_TERRAIN fog of the current frame, recorded by {@code VoxyFogEvents} before Voxy
     * pushed the fog away (i.e. the values {@code FogRenderer.setupFog} wrote at ref FogRenderer.java:279-280, with
     * the colour from {@code FogRenderer.setupColor}). Falls back to {@link #current()} when nothing was captured yet.
     * Not an input for the LOD fog - see the class javadoc.
     */
    public static VoxyFogParameters lastTerrainFog() {
        var last = LAST_TERRAIN_FOG;
        return last != null ? last : current();
    }

    /** Called by {@code VoxyFogEvents} on the render thread. */
    public static void setLastTerrainFog(@Nullable VoxyFogParameters parameters) {
        LAST_TERRAIN_FOG = parameters;
    }

    /** True when this describes a real fog range (not "pushed away"/disabled). */
    public boolean hasFog() {
        return Math.abs(this.environmentalEnd - this.environmentalStart) > 1 && this.environmentalEnd < NO_FOG_DISTANCE * 0.5f;
    }
}
