export const meta = {
  name: 'voxy-neoforge-review',
  description: 'Adversarial multi-lens review of the NeoForge 1.21.1 port, refutation verification, then fixes by owners',
  phases: [
    { title: 'Review', detail: 'per-area reviewers + independent whole-port reviewer' },
    { title: 'Verify', detail: '2 skeptics per finding try to refute it against the sources' },
    { title: 'Fix', detail: 'owners fix confirmed findings' },
  ],
}

const BRIEF = 'C:/Users/rhysf/AppData/Local/Temp/claude/c--Users-rhysf-Documents-GitHub-voxy/317ee298-5cee-48e8-b867-a6351ead9927/scratchpad/PORT_BRIEF.md'
const REPO = 'c:/Users/rhysf/Documents/GitHub/voxy'
const SCR = 'C:/Users/rhysf/AppData/Local/Temp/claude/c--Users-rhysf-Documents-GitHub-voxy/317ee298-5cee-48e8-b867-a6351ead9927/scratchpad'
const notes = (args && args.notes) || ''

const FINDINGS = {
  type: 'object',
  properties: {
    area: { type: 'string' },
    findings: { type: 'array', items: { type: 'object', properties: {
      id: { type: 'string' }, file: { type: 'string' }, line: { type: 'integer' }, severity: { type: 'string' },
      category: { type: 'string' }, title: { type: 'string' }, detail: { type: 'string' }, evidence: { type: 'string' }, proposedFix: { type: 'string' },
    }, required: ['file', 'severity', 'category', 'title', 'detail', 'evidence', 'proposedFix'] } },
    coverage: { type: 'string' },
  },
  required: ['area', 'findings', 'coverage'],
}
const VERDICT = {
  type: 'object',
  properties: { refuted: { type: 'boolean' }, confidence: { type: 'string' }, reasoning: { type: 'string' }, correctedFix: { type: 'string' } },
  required: ['refuted', 'confidence', 'reasoning'],
}
const FIXREPORT = {
  type: 'object',
  properties: { group: { type: 'string' }, applied: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, file: { type: 'string' }, change: { type: 'string' } }, required: ['id', 'file', 'change'] } }, rejected: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, reason: { type: 'string' } }, required: ['id', 'reason'] } }, notes: { type: 'string' } },
  required: ['group', 'applied', 'rejected', 'notes'],
}

const COMMON = `You are reviewing the port of the Minecraft mod Voxy (upstream dev = MC 26.2/Fabric/Java 25) to Minecraft 1.21.1 / NeoForge 21.1.248 / Java 21 / Sodium 0.8.13 / Iris 1.8.14-beta.1. Read the brief at ${BRIEF} first (reference source locations, verified facts, contracts). Working tree: ${REPO} (branch neoforge-1.21.1; compare against upstream with 'git diff dev -- <path>' and against the reference trees listed in the brief). The port COMPILES; you are hunting for runtime and semantic defects. Judge every claim against the reference sources (Minecraft 1.21.1 decompiled sources, Sodium/Iris sources and jars, Roxy, the prior port, upstream 12111) and cite file:line evidence. Do NOT edit files. Report only real, concrete problems (crash, wrong behaviour, feature silently lost, thread-safety, resource leak, server-side class loading, mixin target that will not apply at runtime, wrong descriptor, AT entry mismatch, missing registration in mods.toml/mixin json, Java 21 incompatibility, LWJGL 3.3.3 incompatibility); rank by severity (critical/high/medium/low). Be exhaustive within your area; state what you covered in 'coverage'.${notes ? '\nOrchestrator notes: ' + notes : ''}`

const AREAS = [
  { key: 'G1', owner: 'G1', desc: 'lifecycle/platform/config/commands: Voxy.java, PlatformUtil, VoxyCommon, VoxyClient, VoxyClientEvents, ClientSessionEvents, VoxyClientInstance, VoxyCommands, VoxyConfig, Serialization, Logger, CpuLayout, ThreadUtils, GPUSelectorWindows2, taskbar, ClientImportManager, WorldIdentifier, mixins session/* util/* MixinRenderSystem, config screen factory' },
  { key: 'G2', owner: 'G2', desc: 'world/ingest/import: MixinWorld, MixinClientLevel, MixinClientChunkCache, VoxelIngestService, WorldConversionFactory (PalettedContainer AT usage, single volatile snapshot), Mapper, WorldImporter, DHImporter, Chunky mixin + plugin, voxyworldgenv2 reflection ABI (compare with ' + SCR + '/ref/vwg2/out/*.java)' },
  { key: 'G3', owner: 'G3', desc: 'render core + MC glue: VoxyRenderSystem (projection math, bound framebuffer texture lookup, GL state restore), RenderProperties, pipelines, SSAO, RenderResourceReuse, MixinLevelRenderer/IVoxyRenderSystemHolder lifecycle, fog handling (VoxyFogParameters + event/mixin), DebugEntries (DebugText event), Viewport/ViewportSelector, bounding stores, section renderers, LightMapHelper, ShaderLoader, gl/*, accessors' },
  { key: 'G4', owner: 'G4', desc: 'model baking: ModelFactory (RenderType layers, BlockColors tinting, biome dependence, light emission, stairs), SoftwareModelTextureBakery (BakedModel/NeoForge getQuads per RenderType, LiquidBlockRenderer, BlockAndTintGetter impl, atlas readback), ReuseVertexConsumer (1.21.1 VertexConsumer contract + BLOCK vertex layout decode), BakedBlockEntityModel (BER rendering capture + RenderType texture lookup), TextureUtils sRGB, ModelStore mip level' },
  { key: 'G5', owner: 'G5', desc: 'Sodium 0.8.13 mixins: RenderSectionManager (ctor/ingest hooks/setInfo redirect), SectionCollector visible-stream hook + reset point, DefaultChunkRenderer render injection + viewport/fog/texture sourcing, RenderRegionManager fade redirect, ChunkJobQueue semaphore, accessors, SodiumWorldRenderer; VoxyConfigMenu/SodiumConfigBuilder against the 0.8.13 config API (javap the jar)' },
  { key: 'G6', owner: 'G6', desc: 'Iris 1.8.14 integration: every mixin target/descriptor (javap -c the Iris jar), IrisVoxyRenderPipelineData sampler/image/SSBO/uniform wiring, IrisShaderPatch voxy.json parsing (compare with the photon pack file: unzip -p "E:/RMN/Client/shaderpacks/photon_v1.3b-rmnfix.zip" shaders/world1/voxy.json), VoxyUniforms, VoxySamplers, IrisUtil, IrisVoxyRenderPipeline, mixin plugin, iris.voxy.mixins.json' },
  { key: 'G7', owner: 'G7', desc: 'RMN LOD resync (server + client), storage sectionExists across all backends/adaptors, worldgen mixin + plugin, dedicated-server safety of the whole tree (class loading on a server without LWJGL/Sodium/Iris), networking registration, mods.toml/mixin json/AT completeness (G0 files)' },
  { key: 'FULL', owner: 'G0', desc: 'INDEPENDENT WHOLE-PORT REVIEW (hard rule 5): review the entire diff of the working tree against upstream dev ("git diff dev --stat" then file by file), the build script, mods.toml, AT, every mixin json, and the resources. Look for anything the area reviewers might miss: cross-cutting lifecycle bugs (double init/shutdown, ordering between MixinRenderSystem init, Sodium, Iris), features present upstream but silently missing in the port, config field/default mismatches, thread-safety regressions, incorrect exclusions, Java 21 language/API issues, LWJGL 3.3.3 issues, jar-in-jar/module issues for the Linux dedicated server, and any contract (C1-C22) violated.' },
]

phase('Review')
log('Spawning 8 reviewers (7 areas + independent whole-port)')
const reviews = await parallel(AREAS.map(a => () => agent(`${COMMON}\n\nYOUR AREA: ${a.key} - ${a.desc}\nReturn findings with ids prefixed "${a.key}-".`, { label: `review:${a.key}`, phase: 'Review', schema: FINDINGS, effort: a.key === 'FULL' ? 'max' : 'xhigh' })))
const all = []
reviews.filter(Boolean).forEach((r, i) => (r.findings || []).forEach((f, j) => all.push({ ...f, id: f.id || `${AREAS[i].key}-${j + 1}`, area: AREAS[i].key, owner: AREAS[i].owner })))
log(`${all.length} raw findings`)

phase('Verify')
const verified = await parallel(all.map(f => () =>
  parallel(['api-evidence', 'runtime-behaviour'].map(lens => () => agent(`${COMMON}\n\nYou are an adversarial verifier with the "${lens}" lens. Try to REFUTE this finding by checking the actual code and the reference sources. Default to refuted=true if you cannot confirm it with concrete evidence. If it is real but the proposed fix is wrong, say so and give the corrected fix.\n\nFINDING ${f.id} [${f.severity}] ${f.file}:${f.line || '?'} - ${f.title}\n${f.detail}\nEvidence given: ${f.evidence}\nProposed fix: ${f.proposedFix}`, { label: `verify:${f.id}:${lens}`, phase: 'Verify', schema: VERDICT, effort: 'high' })))
    .then(vs => ({ f, votes: vs.filter(Boolean), confirmed: vs.filter(Boolean).filter(v => !v.refuted).length >= 1 && !(vs.filter(Boolean).length === 2 && vs.filter(Boolean).every(v => v.refuted)) }))
))
const confirmed = verified.filter(v => v.confirmed)
log(`${confirmed.length}/${all.length} findings survived verification`)

phase('Fix')
const byOwner = {}
for (const v of confirmed) {
  const o = v.f.owner === 'G0' ? 'G0' : v.f.owner
  if (!byOwner[o]) byOwner[o] = []
  byOwner[o].push(v)
}
const fixOwners = Object.keys(byOwner).filter(o => o !== 'G0')
const fixes = await parallel(fixOwners.map(o => () => {
  const list = byOwner[o].map(v => `- ${v.f.id} [${v.f.severity}] ${v.f.file}:${v.f.line || '?'} ${v.f.title}\n  detail: ${v.f.detail}\n  evidence: ${v.f.evidence}\n  proposed fix: ${v.f.proposedFix}\n  verifier notes: ${v.votes.map(x => (x.correctedFix || x.reasoning)).join(' | ')}`).join('\n')
  return agent(`You are group ${o} of the Voxy -> NeoForge 1.21.1 port (ownership per brief ${BRIEF} section 5; working tree ${REPO}). The review confirmed these defects in your files. Read each affected file fully, verify against the reference sources, apply the correct fix (not necessarily the proposed one), keep upstream behaviour, cite evidence. Do not run gradle. Return the report.\n\n${list}`, { label: `fix:${o}`, phase: 'Fix', schema: FIXREPORT, effort: 'xhigh' })
}))

return {
  rawFindings: all,
  confirmed: confirmed.map(v => ({ ...v.f, votes: v.votes })),
  refuted: verified.filter(v => !v.confirmed).map(v => ({ id: v.f.id, title: v.f.title, reasons: v.votes.map(x => x.reasoning) })),
  g0Findings: (byOwner['G0'] || []).map(v => v.f),
  fixes: fixes.filter(Boolean),
  coverage: reviews.filter(Boolean).map((r, i) => ({ area: AREAS[i].key, coverage: r.coverage })),
}
