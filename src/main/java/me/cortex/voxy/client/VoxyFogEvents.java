package me.cortex.voxy.client;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.rendering.util.VoxyFogParameters;
import net.minecraft.client.renderer.FogRenderer;
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
 *   <li>the normal terrain fog is {@code start = far - clamp(far/10, 4, 64), end = far} where
 *       {@code far = max(gameRenderer.getRenderDistance(), 32)} (ref FogRenderer.java:266-270, LevelRenderer.java:966):
 *       this is the fog <b>wall</b> at the render distance edge, i.e. the equivalent of 26.2's render distance fog;</li>
 *   <li>"thick" fog (nether / {@code DimensionSpecialEffects.isFoggyAt} / boss world fog):
 *       {@code start = far*0.05, end = min(far,192)*0.5} (ref FogRenderer.java:258-260), mob effect fogs
 *       (blindness/darkness, ref FogRenderer.java:243-248) and fluid fogs (lava/water/powder snow): these are real
 *       atmospheric fogs and are the equivalent of 26.2's environmental fog.</li>
 * </ul>
 * The two are told apart numerically: the wall fog is the only FOG_TERRAIN fog (with no fluid in the camera) whose
 * end equals the far plane distance passed by the level renderer; every other case ends closer.
 * <p>
 * NeoForge fires {@link ViewportEvent.RenderFog} at the end of {@code FogRenderer.setupFog} with the vanilla-computed
 * start/end (ref FogRenderer.java:275 -> ClientHooks.java:447-459) and applies the event values to the render system
 * only when the event is cancelled. Sodium 0.8.13 (FogRendererMixin only redirects the colour sampler in setupColor)
 * and Iris 1.8.14 (MixinFogRenderer only records the water fog density / fog colour) do not alter the fog range, so
 * there is nothing to fight here; the prior NeoForge port (VoxyClientEvents.onRenderFog) used the same event.
 * <p>
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

    private VoxyFogEvents() {}

    //Run last so that fog changes by other mods are captured, and see cancelled events so that a mod that already
    // replaced the fog range does not hide it from us (ClientHooks applies the event values only when cancelled)
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
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
        if (event.getType() != FogType.NONE) return;//Camera is in a fluid, keep the fluid fog (Roxy rule)

        //LevelRenderer.renderLevel passes max(gameRenderer.getRenderDistance(), 32) as the far plane distance
        // (ref LevelRenderer.java:966); the normal terrain fog ends exactly there (ref FogRenderer.java:268-269)
        float farPlane = Math.max(event.getRenderer().getRenderDistance(), 32.0f);
        boolean isRenderDistanceFog = end >= farPlane - 1e-3f;
        boolean fogIsDamnClose = end < DAMN_CLOSE_FOG_END;

        var mode = config.getFogMode();
        boolean push = isRenderDistanceFog || (mode.removesVanillaEnvFog && !fogIsDamnClose);
        if (!push) {
            return;
        }
        event.setNearPlaneDistance(NO_FOG);
        event.setFarPlaneDistance(NO_FOG);
        event.setCanceled(true);//Required for the new plane distances to be applied (ref ClientHooks.java:454-458)
    }
}
