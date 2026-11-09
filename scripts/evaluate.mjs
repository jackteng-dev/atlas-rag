// A single-server evaluation never overwrites the milestone-1 evidence.
import { mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { inputs, checkpoint, measure, totals, latency } from './evaluation-lib.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const base = process.env.AKA_EVAL_BASE ?? 'http://127.0.0.1:18081'
const input = await inputs(root)
const before = await checkpoint(base, input.corpus)
const configuration = before.status.configuration
const results = []
for (const item of input.dataset.questions) results.push(await measure(base, item, configuration, before.status.indexRevision))
const after = await checkpoint(base, input.corpus, configuration)
if (after.status.indexRevision !== before.status.indexRevision) throw new Error('Index changed during evaluation')
await mkdir(`${root}/work/evaluation`, { recursive: true })
const output = `${root}/work/evaluation/single-${Date.now()}.json`
await writeFile(output, JSON.stringify({ measuredAt:new Date().toISOString(), configuration,
  datasetSha256:input.datasetSha256, corpus:before.corpus, totals:totals(results),
  latency:latency(results.map(r=>r.roundTripMs)), results },null,2)+'\n', {flag:'wx'})
console.log(JSON.stringify({ output, totals:totals(results) },null,2))
