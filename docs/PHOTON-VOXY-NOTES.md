# Photon (v1.3b, "rmnfix") x Voxy on 1.21.1 - investigation notes (2026-09-06)

Scope: the RMN client runs Photon `photon_v1.3b-rmnfix.zip` (stock v1.3b + three "RMN fix" edits) with
Voxy through Iris 1.8.14-beta.1. Two requests: make the pack fully compatible with Voxy, and fix
"the water surface and some sprites and/or particles make aspects of entities go transparent".
Everything below was established from the pack/mod/Iris sources and reproduced in the dev client
(NeoForge 21.1.248, Sodium 0.8.13, Iris 1.8.14-beta.1, the ported Voxy, Voxy World Gen V2) with the
scripted screenshot harness in `me.cortex.voxy.devharness.DevHarness` (dev runs only).

## 1. What the user sees, and why

### 1.1 The mechanism (verified with an instrumented copy of the pack)
Photon draws opaque geometry into a gbuffer (deferred, `program/d4_deferred_shading.fsh`) and
translucent geometry forward-shaded into `colortex13`, which `program/c1_blend_layers.fsh`
composites over the scene using `depthtex0` (everything) and `depthtex1` (Iris' copy of the depth
buffer taken at the start of the translucent phase, i.e. before translucent terrain).

Stock Photon v1.3b contains a Voxy-specific guard in `c1_blend_layers.fsh` (added by Photon commit
bdf6ae4 "Fix Voxy translucent layer appearing in front of entities", 2026-01-24):

    if (front_depth_lod < 1.0 && z_vanilla < z_lod && front_depth == back_depth) translucent_color = vec4(0.0);

It zeroes the whole forward layer for a texel when (a) Voxy has any LOD surface there
(`vxDepthTexTrans < 1`), (b) the vanilla surface is closer than that LOD surface, and (c) nothing
translucent was drawn after Iris' depth copy (`depthtex0 == depthtex1`). Its intent was to remove
Voxy's LOD water from texels covered by entities (Voxy draws its translucent LODs during the cutout
terrain pass, before entities exist, so LOD water was composited over entities).

Two things satisfy that condition that were not intended:

1. Every forward-layer draw made BEFORE Iris' depth copy that has a Voxy LOD behind it. With
   `separateEntityDraws` off, Iris flushes all entity batches before the copy, so any entity render
   type routed to `gbuffers_entities_translucent` is erased wherever distant LOD terrain or LOD water
   is behind it. On 1.21.1 those render types are: player skins (`PlayerModel` uses
   `RenderType::entityTranslucent`), dropped/held items and items in frames
   (`entityTranslucentCull` / `itemEntityTranslucentCull`), armor stands, slime outer layers, horse
   markings, allays/vexes/breezes, skulls, wolf armor, shulker bullets, and nametags. Armor and most
   mob bodies are cutout types (deferred) and are not affected, which is why only "aspects" of an
   entity vanish. Verified: with the instrumented pack the player's own torso texels, the armor
   stand and the items are flagged exactly where the LOD sea is behind them, while their parts
   against the sky or the platform are untouched (`run/client/screenshots/s4_dbg_e_back.png`,
   `s4_dbg_c_pitch6.png`).
2. Every texel where the vanilla depth is the sky (`depthtex0 == depthtex1 == 1`) and the LOD lies
   beyond the vanilla far plane (`render distance * 64` blocks): `z_vanilla` is then the far-plane
   distance, which is smaller than the LOD distance, so the LOD water's own forward layer is erased.
   The distant sea therefore loses its water surface and shows as a bright haze band
   (`s3_rmnfix_a_first.png` versus the shaders-off control `s3_rmnfix_0_noshaders.png`, where the
   same sea is plain Voxy LOD water). This is what the user perceives as "the water surface".

Photon's own maintainer removed this guard on the main branch (commit 1fac270, 2026-07-04,
"Fix Voxy blocks removing glint (#609)") and replaced it with a discard inside
`voxy_translucent.glsl` (`if (texelFetch(depthtex1, ...) < 1.0) discard;`). v1.3b (the
`v1.3-maintenance` branch) never received that change.

### 1.2 Why entities are in the forward layer at all on this client
Stock v1.3b disables `gbuffers_entities_translucent` (and the particle/block translucent programs)
for Minecraft < 26.1 (commit 65704f6, 2026-04-13, which removed the v1.3 "Use Separate Entity
Draws" option; v1.3 shipped it ON for every Iris version: `separateEntityDraws = true`, the four
programs enabled, `USE_SEPARATE_ENTITY_DRAWS` defined). Disabling the program broke nametags
(Photon issue #569, still open; PR #570 restores the v1.3 configuration). "RMN fix 1" re-enabled the
program but not `separateEntityDraws`, so translucent entity draws became forward-layer draws that
happen BEFORE Iris' depth copy - the worst of both: they are wipe-eligible (1.1 case 1) and the
deferred pass shades the background at their depth (documented in the fix's own comment).

Iris 1.8.14 with `separateEntityDraws = true` (Iris `batchedentityrendering/mixin/MixinLevelRenderer.java`)
flushes only OPAQUE / OPAQUE_DECAL / WATER_MASK entity batches before the copy and draws the
GENERAL_TRANSPARENT / DECAL / LINES batches after the translucent terrain, and switches particles to
"mixed" ordering. That is the configuration Photon v1.3 was written for.

### 1.3 Particles and "sprites"
With the rmnfix pack particles are drawn after the depth copy through `gbuffers_textured` (all
particle programs disabled -> Iris falls back), so they write only `depthtex0` and are never wiped
themselves. What the user sees as particles/sprites "making" parts of entities transparent is the
same mechanism as 1.1: the see-through parts are entity parts whose forward-layer texels were erased;
dropped items and nametags ("sprites") are themselves forward-layer draws and vanish against LOD.
(Mod survey of the client's entity/particle mods: see section 4.)

## 2. Fixes

### 2.1 Pack side (delivered as `photon_v1.3b-rmnfix2*.zip`, built from the rmnfix pack)
Variants tested with the harness (scene 3, same platform/entities/LOD sea):
* A  - keep RMN fix 1; delete the c1 guard; add Photon main's discard to `voxy_translucent.glsl`.
* B  - revert RMN fix 1 to the full v1.3 configuration: `separateEntityDraws = true`,
       `USE_SEPARATE_ENTITY_DRAWS` defined for Iris on every version, the four translucent programs
       enabled, glint written to colortex13 whenever separate draws are active (the v1.2a/v1.3 intent).
* B2 - B + the c1 guard deleted + main's discard.
* D  - B2 + `"deferTranslucentRendering": true` in voxy.json (needs the ported Voxy, section 2.2) +
       Photon main commit 77d677f backported (deferred programs read the opaque LOD depth
       `lod_depth_tex_shading`, so they no longer depend on the translucent LOD depth that is only
       written later in the frame).
RESULTS (scene 3, `run/client/screenshots/s3_<variant>_*.png`):
* rmnfix (baseline): player torso, armor stand, items, slime skin erased where the LOD sea is behind
  them (instrumented run `s4_dbg_*`), LOD sea shows as a bright band.
* A: entities intact in every view (first person, zoomed, third person back/front, with flame and
  cloud particles); nametags drawn (RMN fix 1 path).
* B2: entities intact in every view; slime skin, horse markings, allay, items, player skin all
  correct; particles fine.
* D: identical to B2 visually; the deferred Voxy pass ran without GL or Voxy errors.
The LOD sea beyond the vanilla water still looks like a flat light band with A, B2 and D - see 3.

### 2.2 Voxy side (ported Voxy, opt-in)
`IrisShaderPatch` now honours `"deferTranslucentRendering": true` (only together with
`excludeLodsFromVanillaDepth`). When set, `AbstractRenderPipeline.runPipeline` skips the translucent
LOD pass during the cutout terrain pass and `VoxyRenderSystem.renderDeferredTranslucent()` runs it at
the start of Sodium's TRANSLUCENT pass (`MixinDefaultChunkRenderer`, HEAD of `render`): after
entities/block entities and after Iris' depth copy and deferred programs. `IrisVoxyRenderPipeline`
re-marks the stencil/depth of `fbTranslucent` from the CURRENT vanilla depth (entities included)
without clearing the opaque LOD depth, draws the translucent LODs where nothing vanilla occludes
them, then restores the `vxDepthTexTrans` contract (far plane where vanilla geometry is in front).
Net effect: Voxy's LOD water can no longer appear in front of entities, so the pack no longer needs
any guard for it. Packs that do not opt in are unaffected.

Independent review (project rule 5) of the Voxy change: no functional defect for the shipped pack;
one latent issue for other packs was fixed afterwards (a deferred pass must write/sample the buffer
copies Iris leaves current AFTER its deferred programs - now resolved with Iris'
`getFlippedAfterTranslucent()` / `isBeforeTranslucent`), plus a frame stamp on the pending viewport,
a `<= 0` depth-texture guard and a corrected comment. Noted, not changed: the deferred pass is not
included in Voxy's own GPU timing statistics; hand and opaque-particle pixels now count as occupied
for the LOD water (consistent with the documented contract); the frame order, depth/stencil logic and
GL state restoration were verified from the Iris 1.8.14 / Sodium 0.8.13 / Minecraft 1.21.1 sources and
the final dev run.

## 3. Compatibility audit (Photon rmnfix x ported Voxy)
* voxy.json/uniforms/samplers/colortex16: resolve on Iris 1.8.14 (no "could not be found" / "Did not
  find all requested samplers" in the dev or live logs); `colortex16` is provided by
  `MixinPackRenderTargetDirectives`. `VOXY` define reaches the pack (Iris shader dump shows the Voxy
  branches compiled into composite1).
* `0f` float literals (Photon main #594): not present in v1.3b's Voxy-reachable GLSL.
* A harmless "error: #endif without #if" INFO line from Iris' preprocessor listener appears twice per
  pack load (also on the live client); origin not established, no visible effect.
* LOD water: a second instrumented pack (`photon_v1.3b-rmnfix-DBG2.zip`, colours Photon's distant-water
  path) shows the whole LOD sea taking the water path with valid colortex16 data
  (`run/client/screenshots/s5_dbg2_*.png`), i.e. Voxy's water gbuffer reaches Photon correctly on Iris
  1.8.14. The lighter tone of LOD water compared with vanilla water is Photon's distant-water shading
  under aerial fog and an overcast sky, the same approximation it uses for Distant Horizons.
* Photon main commits after v1.3b that touch Voxy: 1fac270 (#609, guard removal + discard),
  77d677f (#639 distant glass artifacts), 82fe60e (#594). The first two are folded into variants A/B2/D.

## 4. Client mod survey (bytecode-level, `E:/RMN/Client/mods`)
Which installed mods put extra geometry into the forward layer, and whether anything changes particle
ordering or depth writes (full per-mod table with class names and bytecode offsets in the session notes,
`scratchpad/photon/notes/Q5/Q5_notes.md`):
* Entity Texture Features 7.1 (`emissiveRenderMode: DULL`): every emissive overlay is re-drawn with
  `entityTranslucent(Cull)`; the Fresh Animations packs in use ship ~90 `_e.png` emissive textures
  (spiders, creepers, wolves, piglins, guardians, drowned, zombie villagers, bats, hoglins, golems,
  ghasts, strays). These overlay sprites on otherwise-cutout mobs are the most frequent "aspect of an
  entity" that vanished.
* Players: `PlayerModel` (entityTranslucent), FA+Player's EMF model (same type), 3D Skin Layers
  (`entityTranslucent(skin, true)` within 14 blocks), ETF player features: the whole player is a
  forward-layer draw.
* Nametags (also the ones Entity Culling keeps drawing for culled mobs), dropped/held/framed items,
  Create/Catnip translucent outline faces (glue, selection boxes, schematic holograms; no depth write).
* Wakes 1.4.1 draws its ripple quads with the entity-translucent-cull shader at the water plane around
  swimming players/mobs/items/boats (forward layer, after the depth copy, depth writes on).
* Particles: neither AsyncParticles (its render overwrite keeps the vanilla `depthMask(true)` and the
  opaque/translucent split), particle_core, Veil (blend factor only) nor any other mod changes particle
  ordering or depth writes. Particles are therefore never erased; where a particle covers an erased entity
  texel that texel is exempt (depth differs), so the entity colour reappears under the particle - which
  reads as "particles cut holes in / reveal a see-through entity".
* Flywheel/Iris-Flywheel-Compat (Create kinetics), Sable sub-levels: rendered through Photon's opaque
  block/terrain programs, never the forward layer; not affected.
* Prism 1.0.11 is a text-colour library, not a particle library; Lodestone is present but unused by any
  installed mod.

## 5. Deliverables and installation
* Shader pack: `build/photon/photon_v1.3b-rmnfix2.zip` (+ `photon_v1.3b-rmnfix2.zip.txt`, a copy of
  the current option file so the user's settings carry over). Source of truth for the edits:
  `docs/port-session/photon-rmnfix2.diff` (unified diff against the rmnfix pack, 13 files). It works
  with the ported Voxy (deferred pass active) and with the current Voxy 0.2.16 + Roxy client (the extra
  json key is ignored by older Voxy; the guard removal and the v1.3 entity configuration apply either way).
  Install: drop both files into `shaderpacks/`, select `photon_v1.3b-rmnfix2.zip` in Iris, remove the old
  `photon_v1.3b-rmnfix.zip` when satisfied.
* Voxy: branch `neoforge-1.21.1` (uncommitted): deferred translucent pass (opt-in), dev harness.
  Rebuild with `./gradlew build slimJar`.
Verified in the dev client (scene 3 / 6 / 7 screenshots): player skin, armor stand, slime skin, horse
markings, allay, dropped items, nametag, particles, third-person views, LOD sea and LOD coast behind them.
Verified on 2026-09-07 in a production-style launch of the full RMN client mod set (309 jars incl.
Connector, Continuity, ETF 7.2.1 + EMF 3.3.5 with the Fresh Animations packs, 3D Skin Layers,
ImmediatelyFast, Entity Culling, AsyncParticles; see `docs/PORT-STATUS.md`, "Full RMN mod-set runs"):
`run/clientprod/screenshots/s11_prod_*.png` show the same result as the dev client. Not verified:
non-overworld dimensions.

### 5.1 Nametags are faint under Photon (not a Voxy issue)
With the rmnfix2 pack (and Photon v1.3's configuration in general) a custom nametag reads as faint
white text with a barely visible box against the sky, while with shaders off it has the usual dark box
and bright text (`run/clientprod/screenshots/s12_1_gui_zoom_shaders_on.png` vs `s12_3_gui_zoom_shaders_off.png`).
Established on 2026-09-07:
* it is identical in the dev client (`run/client/screenshots/s6_D_nametag_zoom_gui.png`) and with Voxy
  rendering disabled (`s12dev_novoxy_1_gui_zoom_shaders_on.png`), so it has nothing to do with Voxy,
  the deferred pass or the 300-mod set;
* both text passes are drawn: an instrumented pack (`scratchpad/photon/variants/photon_v1.3b-rmnfix2-DBG3`,
  opaque-alpha fragments of `gbuffers_entities_translucent` painted magenta) shows the depth-tested
  `Font.DisplayMode.NORMAL` pass in full (`s12dbg3_1_gui_zoom_shaders_on.png`); Iris maps
  `text`/`text_see_through`/`text_background` to the same program (ShaderKey TEXT/TEXT_BG ->
  EntitiesTrans) and colortex13 already uses premultiplied over-blending, so batching order is not it;
* the cause is shading: Photon lights text like any translucent entity surface (no text detection in
  the pack, none in Photon main at 15458c0 either), so white text under an overcast sky comes out at the
  brightness of the clouds behind it and the 25%-black background box is the only contrast left.
A pack-side fix would need to identify text draws inside `gbuffers_entities_translucent`; probed on
2026-09-07 with `photon_v1.3b-rmnfix2-DBG4` (`s12dbg4_1_gui_zoom_shaders_on.png`): Iris supplies a
vertex normal for glyph quads (no "normal-less" signal) and `atlasSize <= 256` also matches the slime,
allay and horse textures, so neither is usable. Left as a Photon/Iris limitation (would need an Iris
render-stage or text program hook); not Voxy's.

## 6. Dev harness
`./gradlew runClient -Pharness=<script> -PharnessWorld=<save>[:<seed>]` runs
`me.cortex.voxy.devharness.DevHarness` (excluded from the shipped jar). Scripts used:
`scratchpad/harness_scene3*.txt`, `harness_scene4_dbg.txt`, `harness_scene11_prod.txt` (full mod set),
`harness_scene12_*.txt` (nametag, shaders on/off); screenshots land in `run/client/screenshots` (dev) or
`run/clientprod/screenshots`. The harness opens its world only after NeoForge fired
`RegisterPayloadHandlersEvent` (see PORT-STATUS.md on rrls/quick play). The instrumented pack `photon_v1.3b-rmnfix-DBG.zip` paints magenta where
the c1 guard fires, yellow where a pre-copy forward texel sits over LOD, green where LOD is behind.

Known dev-only hazard: one run crashed on rejoin because a file named
`MANIFEST-000005 (# Name clash 2026-09-06 eftmatC #)` had appeared in the world's Voxy RocksDB
store (`saves/<world>/voxy/<hash>/storage`), leaving `CURRENT` pointing at a stale manifest.
Nothing in Minecraft/Voxy writes such names; some other program touched the run directory. If it
recurs, move `run/` out of any synced/scanned folder.
