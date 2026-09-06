# NeoForge 1.21.1 port - status / handoff (2026-09-06)

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

## Remaining work (in priority order)
1. **FULL-01 (medium, applies to this client).** `VoxyFogEvents` pushes the fog wall to 1e9
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
3. Real-environment test on the RMN client profile (all 310 mods) and the Linux server:
   watch for `NoClassDefFoundError`/module errors at boot, then the usual LOD checks
   (`/voxy debug gpu true`, F3 Voxy lines, resync status log every 30 s on the server).
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
