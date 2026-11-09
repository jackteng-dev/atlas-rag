$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $projectRoot
$statePath = Join-Path $projectRoot 'work/local-processes.json'
if (!(Test-Path -LiteralPath $statePath)) { Write-Output 'No managed process record.'; return }
foreach ($record in (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json)) {
    $managedProcess = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if (!$managedProcess) { continue }
    $processInfo = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + $record.pid)
    if ($managedProcess.StartTime.ToUniversalTime() -ne ([datetime]$record.started).ToUniversalTime() -or !$processInfo.CommandLine.Contains($record.marker)) {
        Write-Output ('Skipped PID ' + $record.pid + ': ownership did not match.'); continue
    }
    Stop-Process -Id $record.pid
    Write-Output ('Stopped this project PID ' + $record.pid)
}
