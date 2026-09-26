package me.cortex.voxy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 1.21.1 replacement for dev's {@code MixinFogRenderer} (contract C2).
 * <p>
 * Minecraft 26.2 has two fogs: an <i>environmental</i> (atmospheric) fog and a <i>render distance</i> fog wall at
 * the edge of the vanilla terrain; dev always pushes the render distance fog away and pushes the environmental fog
 * away only for {@code FogMode.removesVanillaEnvFog} (unless the fog is "damn close", end &lt; 10 blocks).
 * <p>
 * Minecraft 1.21.1 has a single fog range computed by {@code FogRenderer.setupFog} (ref FogRenderer.java:220-275):
 * <ul>
 *   <li>the normal terrain fog is {@code start = far - clamp(far/10, 4, 64), end = far} where {@code far} is the
 *       {@code farPlaneDistance} parameter (ref FogRenderer.java:272-275); the level renderer passes
 *       {@code max(gameRenderer.getRenderDistance(), 32)} for its terrain call (ref LevelRenderer.java:966): this is
 *       the fog <b>wall</b> at the render distance edge, i.e. the equivalent of 26.2's render distance fog;</li>
 *   <li>"thick" fog (nether / {@code DimensionSpecialEffects.isFoggyAt} / boss world fog):
 *       {@code start = far*0.05, end = min(far,192)*0.5} (ref FogRenderer.java:266-267), mob effect fogs
 *       (blindness/darkness, ref FogRenderer.java:243-248) and fluid fogs (lava/water/powder snow): these are real
 *       atmospheric fogs and are the equivalent of 26.2's environmental fog.</li>
 * </ul>
 * The two are told apart numerically: the wall fog is the only fog whose end equals the {@code farPlaneDistance}
 * parameter of the call; every other case ends closer.
 * <p>
 * Which {@code setupFog} call is the terrain call matters: sodium-extra re-enters {@code setupFog(FOG_TERRAIN, ...)}
 * with the very same far plane to set up its cloud fog (sodium-extra MixinLevelRenderer.setupCloudFog), and other mods
 * may do the same, so the call is identified by its call site instead of by its arguments:
 * {@link me.cortex.voxy.client.mixin.minecraft.MixinLevelRenderer} flags the window between the {@code "fog"} and
 * {@code "terrain_setup"} profiler sections of {@code LevelRenderer.renderLevel} (ref LevelRenderer.java:965-967), in
 * which vanilla makes exactly that one call, and {@link me.cortex.voxy.client.mixin.minecraft.MixinFogRenderer}
 * reports the parameters of every {@code setupFog} call ({@link #beginSetupFog}).
 * <p>
 * NeoForge fires {@link ViewportEvent.RenderFog} as the last statement of {@code setupFog} with the vanilla-computed
 * start/end (ref FogRenderer.java:282 -> ClientHooks.java:447-459) and applies the event values to the render system
 * only when the event is cancelled. Two things can still change the fog after this handler decided to push it away:
 * <ul>
 *   <li>a modded fluid: {@code ClientHooks.onFogRender} lets {@code IClientFluidTypeExtensions.modifyFogRender} set
 *       its own fog BEFORE posting the event (with the vanilla values), while {@code event.getType()} only knows the
 *       vanilla fluids - so the camera-in-fluid test is repeated here the way ClientHooks does it, and a fog that is
 *       already different from the event's values is left alone;</li>
 *   <li>a mixin at the TAIL of {@code setupFog} that runs after the event, e.g. sodium-extra's
 *       {@code MixinFogRenderer.sodiumExtra$applyFog} which rewrites start/end from its "fog distance" setting -
 *       {@code MixinFogRenderer} (mixin priority 1500, so it runs after every default-priority TAIL injector) calls
 *       {@link #endSetupFog} and re-applies the push when this handler decided on one.</li>
 * </ul>
 * Effect of {@code VoxyConfig.fogMode} on 1.21.1 (see {@code NormalRenderPipeline.FogMode}):
 * <ul>
 *   <li>the wall fog is always pushed to {@link VoxyFogParameters#NO_FOG_DISTANCE} when Voxy renders, so the vanilla
 *       terrain has no fog wall and the LODs behind it are visible (all modes);</li>
 *   <li>environmental (thick/effect) fog is pushed only for {@code FADE}/{@code OFF} ({@code removesVanillaEnvFog}),
 *       and never when it ends closer than 10 blocks (dev's {@code fogIsDamnClose} rule) or when the camera is in a
 *       fluid (Roxy's rule);</li>
 *   <li>{@code FOG_AND_FADE}/{@code FOG} ({@code hasFog}) fog the LODs with whatever fog is left on the render system
 *       after this handler ran ({@link VoxyFogParameters#current()}): with the wall pushed away that is "no fog" in the
 *       overworld (exactly as in 26.2 where the wall is never fed to the LOD fog), and the thick nether/boss fog when
 *       it is kept - in which case {@code NormalRenderPipeline.finish} detects that the fog covers all rendering and
 *       skips the LOD blit like dev does;</li>
 *   <li>{@code hasFade} modes additionally alpha-fade the LODs at the edge of the Voxy render distance, independent of
 *       the vanilla fog.</li>
 * </ul>
 * With Roxy 0.3.0 the user ran 0.2.16 with {@code useEnvironmentalFog=false}, which is "push everything, no LOD fog":
 * that is {@code OFF} here; the dev default {@code FOG_AND_FADE} keeps the same overworld result (no wall, no LOD fog)
 * and adds the LOD edge fade, while keeping vanilla's thick nether/boss fog - so the dev default is retained.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class VoxyFogEvents {
    private static final float NO_FOG = VoxyFogParameters.NO_FOG_DISTANCE;
    /** Fog that ends closer than this is a close effect fog (blindness etc.) that must not be removed (dev's fogIsDamnClose). */
    private static final float DAMN_CLOSE_FOG_END = 10.0f;

    /** Set by MixinLevelRenderer between the "fog" and "terrain_setup" profiler sections of LevelRenderer.renderLevel. */
    private static boolean terrainFogCallPending;
    /** Parameters of the setupFog call currently executing (MixinFogRenderer HEAD). */
    private static boolean currentCallIsTerrain;
    private static float currentCallFarPlane;
    /** Whether the handler pushed the fog away in the current setupFog call (re-asserted at its TAIL). */
    private static boolean pushedInCurrentCall;
    private static boolean loggedReassert;

    private VoxyFogEvents() {}

    /** MixinLevelRenderer: the level renderer is about to make its FOG_TERRAIN setupFog call. */
    public static void setTerrainFogCallPending(boolean pending) {
        terrainFogCallPending = pending;
    }

    /** MixinFogRenderer, HEAD of FogRenderer.setupFog. */
    public static void beginSetupFog(FogRenderer.FogMode mode, float farPlaneDistance) {
        currentCallIsTerrain = mode == FogRenderer.FogMode.FOG_TERRAIN && terrainFogCallPending;
        currentCallFarPlane = farPlaneDistance;
        pushedInCurrentCall = false;
    }

    /**
     * MixinFogRenderer, TAIL of FogRenderer.setupFog (after NeoForge's event and after every default-priority TAIL
     * injector such as sodium-extra's): if the handler pushed the fog away and something overwrote it since, push again.
     */
    public static void endSetupFog() {
        currentCallIsTerrain = false;
        if (!pushedInCurrentCall) return;
        pushedInCurrentCall = false;
        if (RenderSystem.getShaderFogEnd() < NO_FOG * 0.5f || RenderSystem.getShaderFogStart() < NO_FOG * 0.5f) {
            if (!loggedReassert) {
                loggedReassert = true;
                Logger.info("Another mod rewrote the terrain fog after Voxy removed the render distance fog wall (" +
                        RenderSystem.getShaderFogStart() + " .. " + RenderSystem.getShaderFogEnd() + "); re-applying the removal");
            }
            RenderSystem.setShaderFogStart(NO_FOG);
            RenderSystem.setShaderFogEnd(NO_FOG);
        }
    }

    /** Mirrors ClientHooks.onFogRender's own test (ref ClientHooks.java:449-451): is the camera inside any fluid block? */
    private static boolean cameraInFluid(Camera camera) {
        var entity = camera.getEntity();
        if (entity == null) return false;
        var level = entity.level();
        var pos = camera.getBlockPosition();
        FluidState state = level.getFluidState(pos);
        if (state.isEmpty()) return false;
        return camera.getPosition().y < (double) ((float) pos.getY() + state.getHeight(level, pos));
    }

    //Run last so that fog changes by other mods are captured, and see cancelled events so that a mod that already
    // replaced the fog range does not hide it from us (ClientHooks applies the event values only when cancelled)
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || !currentCallIsTerrain) {
            return;//Sky fog, or a FOG_TERRAIN call made by a mod for its own purposes (sodium-extra cloud fog etc.)
        }
        float start = event.getNearPlaneDistance();
        float end = event.getFarPlaneDistance();

        //Capture the vanilla-computed terrain fog of this frame before it is modified (VoxyFogParameters.lastTerrainFog()).
        // NOTE: the render hooks (MixinDefaultChunkRenderer, iris MixinLevelRenderer) deliberately feed the LODs
        // VoxyFogParameters.current() (post-push) instead, see the VoxyFogParameters class javadoc for the evidence
        VoxyFogParameters.setLastTerrainFog(VoxyFogParameters.of(start, end, RenderSystem.getShaderFogColor()));

        var config = VoxyConfig.CONFIG;
        if (config == null || !config.isRenderingEnabled()) return;
        if (IVoxyRenderSystemHolder.getNullable() == null) return;
        if (event.getType() != FogType.NONE) return;//Camera is in a vanilla fluid, keep the fluid fog (Roxy rule)
        if (cameraInFluid(event.getCamera())) return;//Camera is in a modded fluid whose fog ClientHooks already applied
        if (Math.abs(RenderSystem.getShaderFogEnd() - end) > 1e-3f || Math.abs(RenderSystem.getShaderFogStart() - start) > 1e-3f) {
            return;//Something (a fluid type extension) replaced the fog before the event was posted - keep it
        }

        //The normal terrain fog ends exactly at the far plane distance the level renderer passed (ref FogRenderer.java:274)
        boolean isRenderDistanceFog = end >= currentCallFarPlane - 1e-3f;
        boolean fogIsDamnClose = end < DAMN_CLOSE_FOG_END;

        var mode = config.getFogMode();
        boolean push = isRenderDistanceFog || (mode.removesVanillaEnvFog && !fogIsDamnClose);
        if (!push) {
            return;
        }
        event.setNearPlaneDistance(NO_FOG);
        event.setFarPlaneDistance(NO_FOG);
        event.setCanceled(true);//Required for the new plane distances to be applied (ref ClientHooks.java:454-458)
        pushedInCurrentCall = true;
    }
}
