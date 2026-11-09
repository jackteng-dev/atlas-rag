import { readFile, writeFile, mkdir, mkdtemp, rm, open } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import os from 'node:os'
import { spawn, spawnSync, execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { inputs, checkpoint, measure, totals, latency, signature, compare, sha256 } from './evaluation-lib.mjs'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
if (args.some((a,i)=> i%2===0 && !['--matrix','--output'].includes(a)) || args.length%2) throw new Error('Usage: node scripts/compare-retrieval.mjs [--matrix path] [--output path]')
const options = Object.fromEntries(Array.from({length:args.length/2},(_,i)=>[args[i*2],args[i*2+1]]))
const matrixBytes = await readFile(resolve(root, options['--matrix'] ?? 'eval/experiment.json'))
const matrix = JSON.parse(matrixBytes)
if (matrix.version !== 1 || !Number.isInteger(matrix.repetitions) || matrix.repetitions<1 || matrix.repetitions>20 ||
    !Number.isInteger(matrix.warmupPasses) || matrix.warmupPasses<1 || matrix.warmupPasses>5 ||
    !Array.isArray(matrix.chunkMaxChars) || !matrix.chunkMaxChars.length || matrix.chunkMaxChars.some(v=>!Number.isInteger(v)||v<32||v>8000) ||
    !Array.isArray(matrix.topK) || !matrix.topK.length || matrix.topK.some(v=>!Number.isInteger(v)||v<1||v>5) ||
    !Array.isArray(matrix.coverageThreshold) || !matrix.coverageThreshold.length || matrix.coverageThreshold.some(v=>!Number.isFinite(v)||v<0||v>1))
  throw new Error('Invalid experiment matrix')
const configurations = matrix.chunkMaxChars.flatMap(chunkMaxChars => matrix.topK.flatMap(topK => matrix.coverageThreshold.map(coverageThreshold => ({chunkMaxChars,topK,coverageThreshold}))))
if (configurations.length > 100 || new Set(configurations.map(c=>JSON.stringify(c))).size !== configurations.length) throw new Error('Matrix must contain 1..100 unique configurations')
const key = c => `${c.chunkMaxChars}/${c.topK}/${c.coverageThreshold}`
const baselineIndex = configurations.findIndex(c=>key(c)===key(matrix.baseline))
if (baselineIndex<0) throw new Error('Baseline must appear in matrix')
configurations.unshift(...configurations.splice(baselineIndex,1))
const input = await inputs(root)
if (key(matrix.baseline) !== key(input.historical.configuration)) throw new Error('Matrix baseline must retain the historical 800/5/0.60 setting')
const jar = `${root}/backend/target/knowledge-assistant-0.1.0.jar`
const output = resolve(root, options['--output'] ?? `work/evaluation/comparison-${Date.now()}.json`)
// Reserve output exclusively before expensive work; never replace archived evidence.
await mkdir(dirname(output), {recursive:true})
const reservation = await open(output,'wx'); await reservation.close()
const runs = []
let cancelled = false, activeChild
const cancel = () => { cancelled = true; activeChild?.kill('SIGTERM') }
process.on('SIGINT', cancel)
process.on('SIGTERM', cancel)
try {
  for (const configuration of configurations) {
    if (cancelled) throw new Error('Experiment cancelled')
    const directory = await mkdtemp(resolve(tmpdir(),'aka-retrieval-'))
    const child = spawn('java',['-jar',jar,'--server.port=0','--knowledge.provider=offline',
      `--knowledge.store=${directory}/store.json`,`--knowledge.chunk-max-chars=${configuration.chunkMaxChars}`,
      `--knowledge.top-k=${configuration.topK}`,`--knowledge.coverage-threshold=${configuration.coverageThreshold}`],
      {cwd:root,env:{...process.env,AKA_PROVIDER:'offline',AKA_ALLOW_LOCAL_MODEL:'false'},stdio:['ignore','pipe','pipe']})
    activeChild = child
    const exited = once(child,'exit').catch(() => undefined)
    let log = '', startupError
    child.on('error',error=>{startupError=error})
    child.stdout.on('data',bytes=>{log+=bytes.toString()})
    child.stderr.on('data',bytes=>{log+=bytes.toString()})
    try {
      const deadline = Date.now()+60000
      let port
      while (!(port=log.match(/Tomcat started on port (\d+)/)?.[1])) {
        if (cancelled) throw new Error('Experiment cancelled')
        if (startupError) throw startupError
        if (child.exitCode !== null || child.signalCode !== null || Date.now()>deadline) throw new Error(`Backend failed to start: ${log.slice(-4000)}`)
        await new Promise(resolve=>setTimeout(resolve,100))
      }
      const base = `http://127.0.0.1:${port}`
      const before = await checkpoint(base,input.corpus,configuration)
      for (let pass=0;pass<matrix.warmupPasses;pass++) for (const item of input.dataset.questions)
        await measure(base,item,configuration,before.status.indexRevision)
      const passes = []
      for (let pass=0;pass<matrix.repetitions;pass++) {
        const results = []
        for (const item of input.dataset.questions) results.push(await measure(base,item,configuration,before.status.indexRevision))
        passes.push(results)
      }
      const after = await checkpoint(base,input.corpus,configuration)
      if (before.status.indexRevision !== after.status.indexRevision) throw new Error('Corpus changed during experiment')
      const results = passes[0]
      if (passes.some(pass=>pass.some((r,i)=>signature(r)!==signature(results[i])))) throw new Error('Non-deterministic logical results across repetitions')
      runs.push({ id:key(configuration), configuration:before.status.configuration, corpus:before.corpus, indexRevision:before.status.indexRevision,
        totals:totals(results), citationChecksAcrossRepetitions:{valid:passes.flat().reduce((n,r)=>n+r.validCitations,0),total:passes.flat().reduce((n,r)=>n+r.citationCount,0)},
        latency:{http:latency(passes.flat().map(r=>r.roundTripMs)),backend:latency(passes.flat().map(r=>r.backendMs))},
        logicalResultsStable:true, results:results.map((r,i)=>({...r, measurements:passes.map(pass=>({requestId:pass[i].requestId,roundTripMs:pass[i].roundTripMs,backendMs:pass[i].backendMs}))})),
        changesFromBaseline:compare(runs[0]?.results ?? results, results) })
      console.log(`${key(configuration)} status=${totals(results).statusPass}/16 recall=${totals(results).referenceHits}/14 refusals=${totals(results).correctRefusals}/4`)
    } finally {
      if (child.exitCode===null) { child.kill('SIGTERM'); const timer=setTimeout(()=>child.kill('SIGKILL'),5000); await exited; clearTimeout(timer) }
      activeChild = undefined
      await rm(directory,{recursive:true,force:true})
    }
  }
  // The historical baseline remains a separate observation; check logical compatibility only.
  const historicalAgreement = runs[0].results.every(r=> {
    const b=input.historical.results.find(b=>b.id===r.id)
    return b && b.actualStatus===r.actualStatus && b.referenceHits===r.referenceHits && b.citationCount===r.citationCount
  })
  if (!historicalAgreement) throw new Error('Current baseline differs logically from milestone-1 evidence')
  const report = { measuredAt:new Date().toISOString(), mode:'OFFLINE_EXTRACTIVE',retrieval:'BM25_KEYWORD',embedding:'NONE',
    environment:{platform:os.platform(),release:os.release(),arch:os.arch(),node:process.version,java:spawnSync('java',['-version'],{encoding:'utf8'}).stderr.trim(),
      gitBase:execFileSync('git',['rev-parse','HEAD'],{cwd:root}).toString().trim(), jarSha256:sha256(await readFile(jar))},
    datasetSha256:input.datasetSha256, matrixSha256:sha256(matrixBytes), matrix, historicalBaselineSha256:sha256(await readFile(`${root}/eval/results.json`)),
    historicalAgreement, procedure:'Sequential configurations; fresh isolated store/JVM per configuration; full-dataset warmup excluded; query HTTP timing excludes citation lookups, startup and warmup; repeated fixed-order passes.',
    runs, limitations:['Small corpus-authored set; no independent holdout or semantic answer accuracy.',
      'Source recall labels document/heading, not exact passage relevance; candidate recall includes refused answers.',
      'Selected-reference recall counts excerpt sections, not entailment. Citation validity proves exact text/current provenance only.',
      'Latency is sequential loopback measurement, not a load benchmark; fixed order and JIT/GC/host noise remain.',
      'No model inference, embeddings, token use or costs. Corpus statements describe the historical baseline even under experimental settings.'] }
  await writeFile(output,JSON.stringify(report,null,2)+'\n')
  console.log(`Saved ${output}`)
} catch(error) { await rm(output,{force:true}); throw error }
finally { process.off('SIGINT',cancel); process.off('SIGTERM',cancel) }
