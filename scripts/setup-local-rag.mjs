// Explicit, reproducible free-model download. All HTTP uses curl's inherited session proxy and CA trust.
import { readFile, mkdir, stat, rename } from 'node:fs/promises'
import { createReadStream } from 'node:fs'
import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
process.chdir(root)
if (process.platform !== 'linux' || process.arch !== 'x64') throw new Error('The pinned runtime is Linux x64; select compatible upstream binaries on other systems')
const manifest = JSON.parse(await readFile('config/local-rag-artifacts.json', 'utf8'))
const download = process.argv.includes('--download')
const legacy = process.argv.includes('--legacy')
async function digest(path) {
  const hash = createHash('sha256')
  for await (const bytes of createReadStream(path)) hash.update(bytes)
  return hash.digest('hex')
}
function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env: process.env, stdio: 'inherit' })
    child.on('error', reject)
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${command} exited ${code}`)))
  })
}
for (const artifact of manifest.artifacts) {
  if (artifact.profile === 'legacy' && !legacy) continue
  if (artifact.profile === 'current' && legacy) continue
  let valid = false
  try { valid = (await stat(artifact.path)).size === artifact.bytes && await digest(artifact.path) === artifact.sha256 } catch {}
  if (!valid) {
    if (!download) throw new Error(`Missing or mismatched ${artifact.name}; use --download to authorize the pinned free artifact download`)
    await mkdir(dirname(artifact.path), { recursive: true })
    const partial = artifact.path + '.part'
    await run('curl', ['-fL', '--retry', '2', '--continue-at', '-', artifact.url, '-o', partial])
    if ((await stat(partial)).size !== artifact.bytes || await digest(partial) !== artifact.sha256) throw new Error(`Checksum mismatch: ${artifact.name}; unverified artifact was not installed`)
    await rename(partial, artifact.path)
  }
  console.log(`Verified ${artifact.name}`)
}
await mkdir('work/tools/llama', { recursive: true })
await mkdir('work/tools/qdrant', { recursive: true })
for (const [index, archive, directory] of [[0, 'work/tools/llama.tar.gz', 'work/tools/llama'], [1, 'work/tools/qdrant.tar.gz', 'work/tools/qdrant']]) {
  const executable = manifest.executables[index]
  let installed = false
  try { installed = await digest(executable.path) === executable.sha256 } catch {}
  if (!installed) {
    await run('tar', ['-xzf', archive, '-C', directory])
    if (await digest(executable.path) !== executable.sha256) throw new Error(`Runtime executable mismatch: ${executable.path}`)
  }
}
console.log('Free local RAG runtime ready. No inference service was started.')
