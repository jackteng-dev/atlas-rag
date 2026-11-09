$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $projectRoot
$occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in 18081,4175 }
if ($occupied) { throw '18081 or 4175 is already occupied. Existing processes have not been changed.' }
foreach ($candidatePort in 18081,4175) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$candidatePort)
    try { $probe.Start() } catch { throw ('Port ' + $candidatePort + ' cannot be bound. Check Windows excluded port ranges.') } finally { $probe.Stop() }
}
$jarPath = Join-Path $projectRoot 'backend/target/knowledge-assistant-0.1.0.jar'
$vitePath = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
if (!(Test-Path -LiteralPath $jarPath) -or !(Test-Path -LiteralPath (Join-Path $projectRoot 'frontend/dist/index.html'))) { throw 'Build first: mvn -f backend/pom.xml verify; npm --prefix frontend ci; npm --prefix frontend run build' }
New-Item -ItemType Directory -Force (Join-Path $projectRoot 'work') | Out-Null
$backendProcess = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', ('"' + $jarPath + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'work/backend.stdout.log') -RedirectStandardError (Join-Path $projectRoot 'work/backend.stderr.log')
$frontendRoot = Join-Path $projectRoot 'frontend'
$frontendProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"' + $vitePath + '"'), 'preview', ('"' + $frontendRoot + '"'), '--host','127.0.0.1','--port','4175','--strictPort') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'work/frontend.stdout.log') -RedirectStandardError (Join-Path $projectRoot 'work/frontend.stderr.log')
@(@{pid=$backendProcess.Id; started=$backendProcess.StartTime.ToUniversalTime().ToString('O'); marker=$jarPath}, @{pid=$frontendProcess.Id; started=$frontendProcess.StartTime.ToUniversalTime().ToString('O'); marker=$vitePath}) | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot 'work/local-processes.json') -Encoding utf8
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if ($backendProcess.HasExited -or $frontendProcess.HasExited) { throw 'A process exited during startup. Inspect work/*.log; stop-local.ps1 stops only owned processes.' }
    try {
        $health = Invoke-RestMethod 'http://127.0.0.1:18081/api/status' -TimeoutSec 1
        $preview = Invoke-WebRequest 'http://127.0.0.1:4175' -TimeoutSec 1
        if ($health.status -eq 'READY' -and $preview.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 250 }
}
if (!$ready) { throw 'Startup health check failed. Inspect work/*.log.' }
Write-Output 'Ready: http://127.0.0.1:18081/api/status and http://127.0.0.1:4175. Logs: work/*.log.'
