# Voxy on NeoForge 1.21.1 - porting notes

This branch (`neoforge-1.21.1`) is a native NeoForge port of upstream Voxy `dev`
(0.2.19-beta, written for Minecraft 26.2 / Fabric / Java 25) to:

| Component | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248 (`[21.1,)`) |
| Java | 21 (class file 65) |
| Sodium | 0.8.13+mc1.21.1 (NeoForge), required on the client only |
| Iris | 1.8.14-beta.1+mc1.21.1, optional |
| Lithium | mc1.21.1-0.15.4-neoforge, optional (faster palette path) |
| Chunky | NeoForge 1.4.23, optional (pre-generated chunks are ingested) |
| Voxy World Gen V2 | 2.4.2, optional (`=2.2.2` is declared incompatible, as upstream does) |

Everything upstream ships is kept unless it has no NeoForge 1.21.1 counterpart
(see "Not carried" below). The RMN additions that are in production on the
server (LOD resync protocol, Voxy World Gen V2 queue fix) are included.

## Building

```
./gradlew build slimJar
```

* `build/libs/voxy-<version>-<commit>.jar` - full jar (natives for every platform, 85 MB).
* `build/libs/voxy-<version>-neoforge-1.21.1-slim.jar` - the distribution jar for the
  user's machines: only win64 + linux64 natives (18 MB) and, importantly, the LWJGL
  LMDB/ZSTD binding jars with their JPMS descriptors stripped. Without that stripping a
  dedicated server (which ships no LWJGL core) dies at boot with
  `FindException: Module org.lwjgl not found, required by org.lwjgl.lmdb`. Use the slim jar
  on both the client and the server.

The build is ModDevGradle 2.0.42-beta on Gradle 9.2.1. Run it with a JDK 21
(`JAVA_HOME=<jdk21> ./gradlew ...`); the `foojay` resolver provisions one otherwise.
`validateAccessTransformers = true` fails the build if an AT line matches nothing.

Dev runs: `./gradlew runServer` and `./gradlew runClient [-PquickPlay=<world>]`. External
mods are loaded from `run/client/mods` / `run/server/mods` (ModDevGradle does not discover
mods from the classpath); the bundled libraries are put on the dev classpath explicitly in
`build.gradle` because jar-in-jar entries are not.

## Installing on the RMN client / server

1. Remove `roxy-*.jar`, `rmn-lodresync-*.jar` and every older `voxy-*.jar`
   (`voxy-0.2.16-beta+1.21.11.jar` on the client, `voxy-0.2.9-alpha-neoforge-server.jar`
   on the server). `rmn-lodresync` **must** go: it registers the same
   `voxy:lod_resync_ready` / `voxy:lod_resync_request` payloads and the same world-gen
   queue redirects that are now inside Voxy; keeping it would fail payload registration.
2. Drop `voxy-<version>-neoforge-1.21.1-slim.jar` into `mods/` on both sides.
3. Keep Sodium 0.8.13, Iris 1.8.14-beta.1, Lithium, Chunky and Voxy World Gen V2 as they are.
4. Existing data and configuration keep working: `config/voxy-config.json`
   (upstream JSON format; the four new `lod_resync_*` keys get default values),
   `config/voxy-lod-resync.json` on the server, and the client LOD database under
   `.voxy/saves/<server>/...` (RocksDB + ZSTD, same storage format as upstream dev).
   `config/voxy-client.toml` from the previous port is no longer read.

## What the port changes (by area)

### Loader / lifecycle
* `me.cortex.voxy.Voxy` is the `@Mod("voxy")` entry point. Client commands are registered
  through `RegisterClientCommandsEvent`, per-tick and logout hooks through
  `ClientTickEvent.Post` / `ClientPlayerNetworkEvent.LoggingOut` (`VoxyClientEvents`).
  `VoxyClient.initVoxyClient()` still runs from the `RenderSystem.initRenderer` mixin so
  Voxy initialises before Iris, exactly like upstream.
* `commonImpl.PlatformUtil` replaces every `FabricLoader` use (mod presence via
  `LoadingModList` early / `ModList` late, config dir, mod root path, dist, version and
  commit from the generated `voxy-build.properties`).
* The mod list "Config" button opens Voxy's page inside Sodium's video settings
  (`IConfigScreenFactory`), which is what the ModMenu entry point did on Fabric. The
  Sodium page itself is contributed through `[modproperties.voxy] "sodium:config_api_user"`.
* Session start/end, the login-packet crash guard and the GL debug stack-trace helper are
  ported; `BlockableEventLoop` no longer has `isNonRecoverable`, so the crash guard wraps the
  `LOGGER.error` call inside `doRunTask`'s catch block instead.

### Rendering
* `com.mojang.blaze3d.opengl.*` (Minecraft 1.21.5+ GPU abstraction) does not exist on
  1.21.1: `GlStateManager`/`GlConst` come from `com.mojang.blaze3d.platform`, depth is the
  classic -1..1 range with no reverse-Z (`RenderProperties(false,false,false)`), and the
  depth/colour textures Voxy composites against are read from the framebuffer that is
  bound when Sodium's CUTOUT pass runs (`VoxyRenderSystem.getBoundFramebufferTextures`),
  the same approach upstream used on 1.21.11.
* Sodium 0.8.13 has no `FogParameters`; `VoxyFogParameters` reads the vanilla fog from
  `RenderSystem.getShaderFog*`. 1.21.1 has a single fog range, so the render-distance fog
  wall is pushed away through NeoForge's `ViewportEvent.RenderFog` (`VoxyFogEvents`) while
  close fluid/effect fogs are left alone; Voxy's own `fogMode` options (fog / fade / off)
  keep their upstream meaning. Fog under Iris is captured after `setupFog(FOG_TERRAIN)`.
* `LevelRenderer.setLevel/allChanged/close` drive the render system lifecycle (no
  `LevelExtractor` on 1.21.1). The F3 entries use `CustomizeGuiOverlayEvent.DebugText`; the
  GPU-timing toggle that upstream tied to the F3 entry list is `/voxy debug gpu <bool>` or
  `-Dvoxy.gpuDebug=true`.
* Light map / atlas mip level are read through accessor mixins (`LightTexture.lightTexture`,
  `TextureAtlas.mipLevel`).

### Model baking (software rasteriser)
* `ChunkSectionLayer` -> `RenderType`; NeoForge multi-layer models are baked through
  `BakedModel.getRenderTypes` + `getQuads(..., ModelData, RenderType)`; quads are decoded
  from the 1.21.1 `DefaultVertexFormat.BLOCK` layout; fluids go through
  `LiquidBlockRenderer`; tint providers come from `BlockColors` (AT-opened map) and NeoForge's
  `IClientFluidTypeExtensions`; sRGB conversion is local (`ARGB` does not exist).
* Block-entity model capture renders through `BlockEntityRenderer` + a capturing
  `MultiBufferSource` (upstream keeps this path disabled; it is ported, not enabled).

### Sodium 0.8.13
* Render hooks target the 0.8.13 signatures (`DefaultChunkRenderer.render(...)` without fog
  or sampler arguments, `RenderSectionManager(ClientLevel,int,SortBehavior,CommandList)`).
* Visible-section streaming hooks `SectionCollector.visit` (0.8.13 has no
  `VisibleChunkCollector`); the section fade-cancel mixin is gone because 0.8.13 has no
  chunk fade-in.
* Config UI uses the 0.8.13 config API (same shape as 0.9 for what Voxy needs).

### Iris 1.8.14-beta.1
* All injection points re-verified against the 1.8.14 bytecode (e.g.
  `beginLevelRendering` anchors on `RenderSystem.activeTexture`, the pipeline ctor on
  `ShaderPrinter.resetPrintState` / `createSetupComputes`); samplers take a plain
  `GlSampler`; `PackRenderTargetDirectives` is widened so packs can address all 16
  colour targets; the Iris mixin set is gated by a config plugin so Voxy loads without Iris.
* Verified with the pack in use on the RMN client (`photon_v1.3b-rmnfix.zip`, which ships
  `voxy.json`): Voxy's opaque/translucent patches apply and the `IrisVoxyRenderPipeline` is
  used.

* Deferred translucent LODs (added 2026-09-06, opt-in): a pack that sets `"deferTranslucentRendering": true`
  in its voxy.json (together with `excludeLodsFromVanillaDepth`) gets Voxy's translucent LOD pass drawn at
  the start of Sodium's TRANSLUCENT terrain pass instead of inside the cutout pass, depth-tested against
  the vanilla depth of that moment (entities and block entities included), so LOD water can no longer be
  composited over entities. The pack must not sample `vxDepthTexTrans` in its deferred programs (it is one
  frame stale there); the `photon_v1.3b-rmnfix2` pack does this (see `docs/PHOTON-VOXY-NOTES.md`).
  Implementation: `IrisShaderPatch` (flag), `AbstractRenderPipeline.runDeferredTranslucent`,
  `IrisVoxyRenderPipeline.setupDeferredTranslucent/finishDeferredTranslucent`,
  `VoxyRenderSystem.renderDeferredTranslucent`, `MixinDefaultChunkRenderer` (TRANSLUCENT pass HEAD).
* Dev-only scripted harness `me.cortex.voxy.devharness.DevHarness` (excluded from the jar, inert without
  `-Dvoxy.devHarness`): `./gradlew runClient -Pharness=<script> -PharnessWorld=<save>[:<seed>]` opens or
  creates a world, runs commands, teleports to an ocean, spawns entities, toggles shaders/camera/FOV and
  saves screenshots - used to reproduce and verify the Photon fixes. It opens the world only after NeoForge
  fired `RegisterPayloadHandlersEvent` (nested `ModBusHooks`): with the RMN mod set, rrls shows the title
  screen while the initial resource reload - in which NeoForge locks its network registry - is still
  running, and a join from that early title screen (vanilla `--quickPlaySingleplayer` included) is rejected
  by the integrated server as an unregistered "vanilla" client (see `docs/PORT-STATUS.md`).

* Fog (contract C2, revised 2026-09-07): the terrain fog call is identified by its call site
  (`minecraft.MixinLevelRenderer` flags the "fog".."terrain_setup" window of `LevelRenderer.renderLevel`),
  `VoxyFogEvents` (NeoForge `ViewportEvent.RenderFog`, lowest priority) removes the render-distance wall
  (and environmental fog for the FADE/OFF modes), keeps fluid fogs including modded fluids, and
  `minecraft.MixinFogRenderer` (priority 1500, TAIL of `FogRenderer.setupFog`) re-applies the removal when a
  later TAIL injector (sodium-extra's fog distance) rewrote it. Other mods' FOG_TERRAIN calls (sodium-extra
  cloud fog) are left untouched.

### World / ingest / import
* `Level` / `ClientLevel` constructor mixins use the 1.21.1 signatures; `PalettedContainer`
  internals are opened by AT and read as a single volatile snapshot; the Lithium palette
  fast path is kept; the NBT/registry/data-version API differences are mapped; the region
  and Distant Horizons importers use the 1.21.1 codecs.
* Chunky integration targets `org.popcraft.chunky.platform.NeoForgeWorld` (config plugin
  gated on the mod being present) and ingests every chunk Chunky generates, as upstream's
  Fabric hook does.
* The reflection surface Voxy World Gen V2 2.4.2 probes (`VoxyCommon.getInstance`,
  `VoxyInstance.getOrCreate`, `WorldIdentifier.of`, `Mapper.*`, `VoxelizedSection.*`,
  `WorldConversionFactory.mipSection`, `WorldUpdater.insertUpdate`,
  `VoxelIngestService.INSTANCE/tryAutoIngestChunk/rawIngest`, `VoxyConfig.isEnabled`) is
  present with compatible shapes.

### RMN additions (from the previous production port)
* LOD resync protocol: `commonImpl.network.{LodResyncServer, LodResyncPayloads,
  LodResyncConfig, VoxyNetworkRegistration}` (server + integrated server; the server half is the
  newer build recovered from the deployed `voxy-0.2.9-alpha-neoforge-server.jar`, which adds serve-rate
  measurement/re-announcement, a status log, `floorChunksPerTick` and `generateMissingChunks`) and
  `client.{LodResyncClient, LodResyncVerifier}`, `client.lod.LodBandPlan` (client). The
  verifier is stopped before every instance shutdown (disconnect, `/voxy reload`, toggling
  Voxy off in the Sodium menu). New `voxy-config.json` keys: `lod_resync_enabled`,
  `lod_resync_max_radius_chunks`, `lod_resync_max_attempts`, `lod_resync_grace_ms`.
* `SectionStorage.sectionExists(long)` key-only probes for every storage backend/adaptor,
  so the verifier never allocates sections.
* Voxy World Gen V2 client queue fix (`MixinWorldgenClientQueue`, drop farthest instead of
  nearest at the 8192 cap), gated on that mod being present.

### Dedicated-server safety
* `CpuLayout` and `ThreadUtils` never fail when LWJGL is absent; `Logger` never touches
  client classes on the server; all client event subscribers are `Dist.CLIENT`; common
  mixins reference only common classes; the slim jar strips the LWJGL module descriptors.

## Not carried (and why)
* Flashback, Nvidium, ModMenu, Vivecraft, FREX flawless frames: no NeoForge 1.21.1 builds /
  Fabric-only APIs. The code paths degrade to "not installed".
* Prior-port-only features (NeoForge TOML config, `earthCurveRatio`, `lodBoundaryBuffer`,
  `MixinLayerLightSectionStorage`): not upstream features; the light-storage hack removed a
  synchronisation Voxy relies on for cross-thread light reads.
* JMH benchmarks (`src/jmh`) are not wired into the ModDevGradle build.

## Verification performed
* `./gradlew build slimJar` on JDK 21: clean compile (only deprecation warnings), AT
  validation passed, jar-in-jar metadata complete.
* Dev dedicated server (NeoForge 21.1.248 + Lithium + Chunky + Voxy World Gen V2): boots
  to "Done", Voxy constructs on `DEDICATED_SERVER`, 13 storage config types register, LOD
  resync resolves the world-gen bridge (radius 128), no errors.
* Dev client (Sodium 0.8.13 + Iris 1.8.14-beta.1 + Lithium + Voxy World Gen V2, RX 9070 XT,
  OpenGL 4.6): joins a world, creates the world engine and render system
  (`NormalRenderPipeline`, then `IrisVoxyRenderPipeline` with the photon pack), renders for
  minutes without Voxy exceptions; all mixins apply (a missing target would abort startup
  because every Voxy mixin config is `required`).
* Adversarial multi-agent review: all 8 area reviews (lifecycle, world/ingest, render core,
  model baking, Sodium, Iris, RMN/server safety, independent whole-port) completed; the
  verification and fix phases did not run. Fixed from the findings: default `jar` not
  server-bootable -> the slim jar is the deliverable; quiet CpuLayout server log; thread-local
  cleanup. Open findings (one medium: sodium-extra's fog hook can overwrite Voxy's fog push
  when its fog distance is non-zero; the rest low) are listed with reviewer evidence and
  proposed fixes in `docs/PORT-STATUS.md` and `docs/port-session/review-findings.json`.

* 2026-09-06/07 (dev harness, `run/client/screenshots`): Photon rmnfix2 pack + ported Voxy: translucent-typed
  entities (player skin, armor stand, slime skin, horse markings, allay, items, nametags) render correctly against
  LOD sea/coast, first and third person, with particles; shaders off/on; sodium-extra 0.9.3 with fog distance 3
  chunks leaves the LODs unfogged (fog removal re-applied, see the Fog bullet above).

## Known behaviour to be aware of
* Under the integrated server the LOD-resync server logs
  "request queue full ... dropping further positions" when the client verifier outruns
  `chunksPerTick`; this is the production code's own back-pressure message.
* Upstream's `ActiveSectionTracker` "section raced to save queue" and `NodeManager`
  "request in flight" messages are unchanged upstream diagnostics.
