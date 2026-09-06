export const meta = {
  name: 'voxy-neoforge-compile-fix',
  description: 'Dispatch javac errors of the NeoForge 1.21.1 port to the owning group agents and fix them',
  phases: [{ title: 'Fix', detail: 'one agent per owner group with its error list' }],
}

const BRIEF = 'C:/Users/rhysf/AppData/Local/Temp/claude/c--Users-rhysf-Documents-GitHub-voxy/317ee298-5cee-48e8-b867-a6351ead9927/scratchpad/PORT_BRIEF.md'
const REPO = 'c:/Users/rhysf/Documents/GitHub/voxy'

// args = { round: number, byGroup: { G1: { file: [ {line,msg,context[]} ] }, ... }, extraNotes: string }
const byGroup = (args && args.byGroup) || {}
const round = (args && args.round) || 1
const extra = (args && args.extraNotes) || ''

const SCHEMA = {
  type: 'object',
  properties: {
    group: { type: 'string' },
    fixed: { type: 'array', items: { type: 'object', properties: { file: { type: 'string' }, line: { type: 'integer' }, fix: { type: 'string' }, evidence: { type: 'string' } }, required: ['file', 'fix'] } },
    notFixed: { type: 'array', items: { type: 'object', properties: { file: { type: 'string' }, msg: { type: 'string' }, reason: { type: 'string' }, needsGroup: { type: 'string' } }, required: ['file', 'msg', 'reason'] } },
    newMixinClasses: { type: 'array', items: { type: 'string' } },
    newAccessTransformers: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
  },
  required: ['group', 'fixed', 'notFixed', 'newMixinClasses', 'newAccessTransformers', 'notes'],
}

const groups = Object.keys(byGroup).filter(g => g !== 'G0' && Object.keys(byGroup[g]).length > 0)
phase('Fix')
log(`Compile-fix round ${round}: ${groups.map(g => `${g}(${Object.values(byGroup[g]).reduce((a, b) => a + b.length, 0)})`).join(', ')}`)

const results = await parallel(groups.map(g => () => {
  const files = byGroup[g]
  const list = Object.keys(files).map(f => {
    const errs = files[f].map(e => `  - line ${e.line}: ${e.msg}${e.context && e.context.length ? '\n      ' + e.context.join('\n      ') : ''}`).join('\n')
    return `FILE ${f}\n${errs}`
  }).join('\n\n')
  const prompt = `You are group ${g} of the Voxy -> NeoForge 1.21.1 port. Read the brief at ${BRIEF} first (hard rules, references, contracts, ownership). Working tree: ${REPO}.

The build (javac, Java 21, against Minecraft 1.21.1 + NeoForge 21.1.248 + Sodium 0.8.13 + Iris 1.8.14-beta.1) reported the following errors in files YOU own. Fix every one of them properly: no stubs, no commenting-out, no suppression. For each error open the reference source (brief section 2) to find the correct 1.21.1 API and cite it. If an error is caused by another group's file (e.g. a missing method on a class you do not own), do NOT edit that file: describe the exact signature you need in notFixed with needsGroup. Do not run gradle; the orchestrator recompiles after all groups finish. Read the whole file around each error before editing; keep upstream behaviour.
${extra ? '\nAdditional notes from the orchestrator:\n' + extra + '\n' : ''}
ERRORS (round ${round}):
${list}

When done, return the structured report.`
  return agent(prompt, { label: `fix:${g}:r${round}`, phase: 'Fix', schema: SCHEMA, effort: 'high' })
}))

return { round, results: results.filter(Boolean) }
