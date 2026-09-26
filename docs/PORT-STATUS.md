# NeoForge 1.21.1 port - status / handoff (2026-09-07)

Read `docs/PORTING-NOTES.md` first (what the port is, how to build, how to install).
This file records where the work stopped so it can be resumed.

## State of the branch `neoforge-1.21.1`
* Compiles cleanly (`./gradlew build slimJar`, JDK 21). Deliverable:
  `build/libs/voxy-0.2.19-beta-neoforge-1.21.1-slim.jar` (use on client AND server).
* Dev smoke tests passed (see PORTING-NOTES "Verification performed"): dedicated server with
  Lithium + Chunky + Voxy World Gen V2; client with Sodium 0.8.13 + Iris 1.8.14-beta.1 +
  Lithium + World Gen V2, both without shaders and with `photon_v1.3b-rmnfix.zip`.
  The dev client did NOT have sodium-extra, farsight, distant-thunders or the other ~300
  RMN client mods installed.
* The LOD-resync server half was replaced by the newer build recovered from the deployed
  `voxy-0.2.9-alpha-neoforge-server.jar` (decompiled with Vineflower, cleaned, re-verified by
  a dev server run).
* NOT yet installed on `E:/RMN/Client` / `E:/RMN/Server` (copies) or the live profiles.

## Review status (project rule 5)
All 8 adversarial review areas ran to completion (G1, G2, G3, G4, G5, G6, G7 and the
independent whole-port review "FULL"); their raw output is in
`docs/port-session/review-findings.json`. The workflow was stopped before its
verification and fix phases, so every finding below is unverified reviewer output. Fixed
during the session: G1 CpuLayout error log, G1/FULL-05 PreparedState ThreadLocal leak
(`PREPARED.remove()` right after `get()`), G7 slim jar (server-bootable artifact).

## Photon shader work (2026-09-06, second task) - see `docs/PHOTON-VOXY-NOTES.md`
* Root cause of "entities go transparent" found and reproduced (Photon v1.3b's Voxy guard in
  `c1_blend_layers.fsh` erasing forward-layer entity texels and distant LOD water); fixed pack
  `build/photon/photon_v1.3b-rmnfix2.zip` (diff in `docs/port-session/photon-rmnfix2.diff`).
* Voxy gained an opt-in deferred translucent LOD pass (`"deferTranslucentRendering": true` in a
  pack's voxy.json) and a dev-only screenshot harness (`me.cortex.voxy.devharness`, excluded from
  the jar; `./gradlew runClient -Pharness=<script> -PharnessWorld=<save>[:<seed>]`).
* Both are uncommitted on `neoforge-1.21.1`; the independent review of the Voxy change is in the
  session transcript / notes.

## Fixed on 2026-09-07 (verified in the dev client)
* FULL-01 sodium-extra fog conflict: `VoxyFogEvents` now identifies the level renderer's terrain fog call
  by call site (`minecraft.MixinLevelRenderer`, "fog"/"terrain_setup" profiler constants) and a new
  `minecraft.MixinFogRenderer` (priority 1500, TAIL of `FogRenderer.setupFog`) re-applies the fog removal
  after any later TAIL injector; tested with sodium-extra 0.9.3 and fog distance 3 chunks (log:
  "Another mod rewrote the terrain fog ... re-applying", LODs unfogged with shaders off).
* G3-2 / FULL-06 (fog): modded-fluid fog is kept (camera-in-fluid test mirrors ClientHooks; a fog already
  changed before the event is left alone); FOG_TERRAIN calls made by mods with the terrain's arguments
  (sodium-extra cloud fog) are no longer treated as the render-distance wall.
* G3-1 / FULL-02: `getBoundFramebufferTextures` uses the main render target only when it (or the default
  framebuffer) is bound; any other framebuffer without sampleable depth/colour textures skips the LOD pass
  for that draw (one-time warning) instead of mixing targets.
* FULL-03: `BakedBlockEntityModel` restored to upstream's commented-out state; the three RenderType
  accessor mixins were removed from `client.voxy.mixins.json` (the 1.21.1 rewrite is in commit 6c0f9bc0
  if the block-entity baking path is ever enabled).
* G2 (Chunky): `ChunkyMixinPlugin.postApply` now logs whether the hook bound ("(OK)" / "(UNEXPECTED ...)")
  so a silent disable is visible in the log; `defaultRequire` stays 0 on purpose (a Chunky update must not
  abort the game).

## Full RMN mod-set runs (2026-09-07)
Two directories, both gitignored:
* `run/clientfull` - dev launch (`./gradlew runClient -PgameDir=run/clientfull -Pharness=<script>
  -PharnessWorld=fullworld:12345`). Sinytra Connector cannot run under ModDevGradle ("Could not determine
  clean minecraft artifact path") so it, Continuity, Numismatics and GlitchCore/Serene Seasons sit in
  `mods_excluded` there. Dev-only limitations, see below.
* `run/clientprod` - **production-style launch**: `scratchpad/prod_launch.py` rebuilds the Modrinth App's
  real command line from `%APPDATA%/ModrinthApp/meta/versions/1.21.1-21.1.248/1.21.1-21.1.248.json`
  (its own Zulu 21 JRE, module path, `include_in_classpath` honoured, Java @argfile, offline "Dev"
  account) against a copy of the *current* `E:/RMN/Client` (`mods`, `config`, `options.txt`,
  resource packs, `shaderpacks`, `saves/fullworld`). The mods folder is the reference folder minus
  `roxy-1.21.1-NeoForge-0.2.0.jar`, `voxy-0.2.16-beta+1.21.11.jar`, `rmn-lodresync-1.0.0.jar` and
  `gaboulibs-neoforge-1.8.3.jar` (309 jars), plus the port built with `-PincludeHarness=true` as
  `voxy-0.2.19-beta-neoforge-1.21.1-HARNESSTEST.jar`.

**Result (production-style launch, 309 mods incl. Connector + Continuity + Numismatics + GlitchCore +
Serene Seasons + fieldguide 1.16.0, Photon rmnfix2, harness scene 11 = entities on an ocean platform):**
no crash, no Voxy mixin failure, no Voxy error other than the LOD-resync back-pressure messages that
the previous production port also prints. Voxy constructed, applied the Photon patch, took the
`IrisVoxyRenderPipeline` with the deferred translucent pass ("shader pack opted in"), the world-gen v2
queue redirect and the LOD resync bridge (radius 128). The nine frames
(`run/clientprod/screenshots/s11_prod_*.png`) show every entity fully opaque against the LOD sea,
the translucent slime, dropped items, third-person player skin, flame particles over LOD water and the
LOD coast/islands - the same result as the dev-environment verification of the Photon fix. Voxy's render
system is (re)created three times within ~25 s of joining (Iris pipeline re-creation after the join-time
resource reloads of other mods); the user's real log with Voxy 0.2.16 shows the same three creations.

**Things that looked like port bugs and are not (evidence in `scratchpad/prod*_client.log`):**
* "This server does not support vanilla clients as it has mandatory registry data maps" and
  "Failed encoding custom payload glitchcore:sync_config: ClassCastException ... DiscardedPayload"
  (preceded by NeoForge's "No registration for payload glitchcore:sync_config"): both happen when the
  client joins a singleplayer world **before NeoForge has fired `RegisterPayloadHandlersEvent`**. rrls
  (Remove Reloading Screen) calls `Minecraft.onResourceLoadFinished` from `Minecraft.<init>`, so the
  title screen appears while the initial resource reload - in which `CommonModLoader.finish` runs the
  "Network registry lock" task - is still going; `--quickPlaySingleplayer` (dev `-PquickPlay`, and my
  first production-style launches) joins from that early point, and so did the harness until it was
  taught to wait for the event (`DevHarness.ModBusHooks`). In every failing log the mods' "registering
  payload handlers" lines come 7-20 s *after* the join; in the user's real log they come ~30 s *before*
  "Connecting to rmn.hxxp.io". Reproduced without Voxy installed (`scratchpad/runB_client.log`).
  The earlier note that GlitchCore fails "in the dev environment only" was wrong - it was this.
* "Dev lost connection: Authentication rejected." - `gaboulibs-neoforge-1.8.3.jar`
  (`net.Gabou.gaboulibs.auth.ServerAuth`/`MinecraftAccountVerifier`) kicks players whose profile is an
  offline (UUID version 3) profile unless `Platform.isDevelopmentEnvironment()`. My launch uses an
  offline "Dev" account; the user's real account passes. No other jar references gaboulibs and it has
  no config, so it is set aside for the test runs only.
* fieldguide 1.16.0's EMF/ETF requirement is satisfied now: the reference folder was updated by the user
  on 2026-09-06 (entity_model_features 3.3.5, entity_texture_features 7.2.1, Sodium 0.8.12 instead of
  0.8.13, JEI 19.44, AsyncParticles 21.1.4.2, ImmediatelyFast 1.6.13, punchy 2.7e, animalgarden bison and
  culpeofox, create-collision-fix, rmn-lodresync 1.0.0, no quick-pack). **Re-diff `E:/RMN/Client/mods`
  against the test folder before every run; the user re-syncs it from the live profile
  `%APPDATA%/ModrinthApp/profiles/RMN Client`.**
* Numismatics, Connector and Continuity load normally in the production-style launch (their failures
  were ModDevGradle artefacts).

**Still open from these runs:**
* Nametags under Photon are faint (only vanilla's see-through pass is visible; the depth-tested pass is
  missing) - present in the dev-environment frames too (`run/client/screenshots/s6_D_nametag_zoom_gui.png`),
  so not a full-mod-set regression; see the nametag entry under "Remaining work".
* One run (`scratchpad/prodA4_client.log`) lingered for 5 minutes after "Stopping!" with "Saving worlds"
  as the last line (fastquit waits for the save); the identical next run exited within 90 s. Not
  reproduced; the monitor scripts now take a `jcmd Thread.print` when a run lingers >90 s after stop.
* `rmn-lodresync-1.0.0.jar` registers the same `voxy:lod_resync_ready` / `voxy:lod_resync_request`
  payload ids as the port (`NetworkRegistry.register` throws "already registered"), so it must be
  removed together with roxy and voxy-0.2.16 when the port is installed (G7).

## Remaining work (in priority order)
1. ~~FULL-01~~ (fixed, see above) **FULL-01 (medium, applies to this client).** `VoxyFogEvents` pushes the fog wall to 1e9
   from NeoForge's `ViewportEvent.RenderFog`, which `FogRenderer.setupFog` applies as its last
   statement. sodium-extra 0.9.3 (installed: `E:/RMN/Client/mods/sodium-extra-neoforge-0.9.3+mc1.21.1.jar`)
   injects at TAIL of the same method and, when its "Fog distance" is not 0, rewrites
   fog start/end after Voxy's push. `MixinDefaultChunkRenderer` then reads those values via
   `VoxyFogParameters.current()`, and every LOD beyond the sodium-extra fog distance is fogged
   out. It works today only because the RMN sodium-extra config has `distance_chunks: 0`.
   Proposed fix (from the reviewer): record Voxy's decision + intended LOD fog in
   `VoxyFogParameters` and have `MixinDefaultChunkRenderer` / the Iris capture use that instead
   of `RenderSystem.getShaderFog*`; and/or a Voxy mixin at `FogRenderer.setupFog` TAIL with
   mixin priority > 1000 that re-asserts the push. Test with sodium-extra in `run/client/mods`
   and a non-zero fog distance.
2. Run the verification pass on the other findings (all low), then fix the ones confirmed:
   * G3-1 / FULL-02: `VoxyRenderSystem.getBoundFramebufferTextures()` silently degrades when
     the bound FBO has renderbuffer attachments (third-party FBOs only; vanilla/Iris use textures).
   * G3-2: modded-fluid fog (camera inside a fluid with `FogType.NONE`) is treated as the
     render-distance wall and removed.
   * FULL-06: any other `FOG_TERRAIN` `setupFog` call (sodium-extra cloud fog, other fog mods)
     is classified as the wall and pushed.
   * FULL-03: `BakedBlockEntityModel` (commented out upstream) is compiled and pulls in three
     RenderType accessor mixins for an unused path; consider re-commenting and dropping the
     accessors from `client.voxy.mixins.json`.
   * FULL-04: the upstream JMH benchmark source set (`src/jmh`) is no longer built.
   * G2: Chunky hook is `require=0` (silent disable on a Chunky bytecode change);
     `ChunkyMixinPlugin` sits under the common mixin package prefix; `Mapper`/`WorldImporter`/
     `DHImporter` reference `org.lwjgl.system.MemoryUtil` (safe: no server-side instance).
   * G4: `ModelFactory.getTintSources` treats every `LiquidBlock` as tinted (cosmetic/perf).
   * G5: `MixinSectionCollector` misses immediate-presentation sections for one frame.
   * G7: `rmn-lodresync` must be removed wherever this jar is installed (duplicate payload ids).
3. ~~Real-environment test on the RMN client~~ done as a production-style launch of the full mod set
   (see above). Still to do by the user: install the slim jar on the real client profile (remove roxy,
   voxy-0.2.16 and rmn-lodresync) and on the Linux server (replace `voxy-0.2.9-alpha-neoforge-server.jar`),
   then the usual LOD checks (`/voxy debug gpu true`, F3 Voxy lines, resync status log every 30 s).
3b. **Photon nametags are faint** with the rmnfix2 pack: vanilla draws a nametag twice
   (`Font.DisplayMode.SEE_THROUGH`, alpha 0x20 + background box, no depth test; then `NORMAL`, full
   white, depth-tested). Under Photon only the faint see-through pass is visible
   (`run/clientprod/screenshots/s12_1_gui_zoom_shaders_on.png` vs `s12_3_gui_zoom_shaders_off.png`).
4. Optional: decide whether to keep the previous port's `earthCurveRatio` / `lodBoundaryBuffer`
   features (not ported).

## How the work was organised (for resuming with the same method)
* `docs/port-session/PORT_BRIEF.md` - the engineering brief every implementation agent
  followed: reference-source locations, verified 1.21.1 / NeoForge / Sodium 0.8.13 /
  Iris 1.8.14 API facts, the cross-group contracts C1-C22 and the file-ownership table
  (G1 lifecycle, G2 world/ingest, G3 render core, G4 model baking, G5 Sodium, G6 Iris,
  G7 RMN features/server safety, G0 build/resources).
  The brief's reference paths point at a session scratchpad that no longer exists; recreate
  them with: ModDevGradle `createMinecraftArtifacts` (decompiled 1.21.1 sources land in
  `build/moddev/artifacts/neoforge-21.1.248-minecraft-sources.jar`), `git clone --branch
  mc1.21.1-0.8.13 https://github.com/CaffeineMC/sodium`, `git clone --branch 1.21.1
  https://github.com/IrisShaders/Iris`, `git clone https://github.com/rasanovum/roxy`, the
  prior port at `C:/Users/rhysf/Documents/GitHub/Voxy-Neoforge`, and `javap -p` over the
  jars in `E:/RMN/Client/mods`.
* `docs/port-session/implementation-reports.json` - every group's per-file API mapping with
  evidence citations, exclusions and cross-group requests.
* `docs/port-session/review-findings.json` - the 8 area reviews with evidence and proposed
  fixes. `wf_review.js` / `wf_compile_fix.js` are the workflow scripts used (they expect the
  brief path; edit the constants at the top).

## Things that are easy to get wrong when resuming
* Run Gradle with a JDK 21 (`JAVA_HOME=C:/Users/rhysf/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2`).
* External mods for dev runs go into `run/<client|server>/mods` (copied from `E:/RMN/*/mods`);
  the run directories are git-ignored and must be recreated (`run/server/eula.txt`,
  `run/client/saves/testworld` copied from a server-generated world, `run/client/shaderpacks`).
* Every Voxy mixin config is `required`; a wrong target aborts startup loudly, which is the
  intended safety net - do not lower `defaultRequire` to hide a failure.
* Keep `rmn-lodresync` out of any environment that has this jar (duplicate payload ids).
* Never modify the `E:/RMN/Client` and `E:/RMN/Server` copies; they are the reference.
