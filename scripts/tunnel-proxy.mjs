// Tunnelmole 2.4.0 does not expose WebSocket agent/storage options, so adapt its project-local install.
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
const root = path.resolve(new URL('..', import.meta.url).pathname)
process.chdir(root)
if (!process.env.HTTPS_PROXY) throw new Error('This managed demo requires the inherited HTTPS proxy')
const packageRoot = path.join(root, 'work/tools/tunnel/node_modules/tunnelmole')
const metadata = JSON.parse(fs.readFileSync(path.join(packageRoot, 'package.json'), 'utf8'))
if (metadata.version !== '2.4.0') throw new Error('Tunnel adapter requires the tested Tunnelmole 2.4.0')
const connectPath = path.join(packageRoot, 'dist/src/websocket/connect.js')
let connect = fs.readFileSync(connectPath, 'utf8')
if (!connect.includes("import { HttpsProxyAgent }")) connect = "import { HttpsProxyAgent } from 'https-proxy-agent';\n" + connect
connect = connect.replace('new HostipWebSocket(config.hostip.endpoint);', 'new HostipWebSocket(config.hostip.endpoint, { agent: new HttpsProxyAgent(process.env.HTTPS_PROXY), handshakeTimeout: 20000 });')
if (!connect.includes('new HttpsProxyAgent(process.env.HTTPS_PROXY)')) throw new Error('Unknown Tunnelmole connection implementation')
if (!connect.includes('let heartbeat;')) connect = connect.replace('    const websocketIsReady = websocket.readyState === 1;', "    let heartbeat;\n    websocket.on('open', () => { heartbeat = setInterval(() => { if (websocket.readyState === 1) websocket.ping(); }, 20000); });\n    websocket.on('close', () => { clearInterval(heartbeat); process.exit(1); });\n    const websocketIsReady = websocket.readyState === 1;")
fs.writeFileSync(connectPath, connect)
const storagePath = path.join(packageRoot, 'dist/src/node-persist/storage.js')
const storage = fs.readFileSync(storagePath, 'utf8').replace("const dir = homedir + '/' + '.tmole.sh';", 'const dir = process.env.AKA_TUNNEL_STORAGE;')
if (!storage.includes('const dir = process.env.AKA_TUNNEL_STORAGE;')) throw new Error('Unknown Tunnelmole storage implementation')
fs.writeFileSync(storagePath, storage)
process.env.AKA_TUNNEL_STORAGE = path.join(root, 'work/tunnel-storage')
process.env.TUNNELMOLE_DEBUG = '0'
process.env.TUNNELMOLE_TELEMETRY = '0'
process.env.TUNNELMOLE_QUIET_MODE = '1'
const { default: tunnelmole } = await import(pathToFileURL(path.join(packageRoot, 'dist/src/tunnelmole.js')).href)
const url = await tunnelmole({ port: Number(process.env.AKA_FRONTEND_PORT ?? 4175) })
fs.writeFileSync('work/public-url.txt', url + '\n')
console.log('Public HTTPS URL saved to work/public-url.txt; verify it before sharing.')
