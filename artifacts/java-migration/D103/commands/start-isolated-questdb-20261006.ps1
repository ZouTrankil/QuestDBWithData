$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$dataPath = Join-Path $repoPath 'var/d103-isolated-questdb'
$receiptPath = Join-Path $PSScriptRoot 'private-server-start-20261006.json'
if (Test-Path -LiteralPath $receiptPath) { throw 'D103 start receipt already exists; inspect the actual server before any restart' }
if (Test-Path -LiteralPath $dataPath) { throw 'D103 root already exists; refuse reuse during initial setup' }
$listeners = @(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in @(19030,18842,19033,19039) })
if ($listeners.Count -ne 0) { throw 'D103 candidate ports are already occupied' }
$resolvedDataPath = [System.IO.Path]::GetFullPath($dataPath)
if (-not $resolvedDataPath.StartsWith($repoPath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'D103 target escapes workspace' }
New-Item -ItemType Directory -Path (Join-Path $dataPath 'conf') | Out-Null
@'
http.net.bind.to=127.0.0.1:19030
http.min.net.bind.to=127.0.0.1:19033
pg.net.bind.to=127.0.0.1:18842
line.tcp.net.bind.to=127.0.0.1:19039
line.udp.net.bind.to=127.0.0.1:19039
shared.worker.count=2
'@ | Set-Content -LiteralPath (Join-Path $dataPath 'conf/server.conf') -Encoding utf8NoBOM
$serverArgs = @('-XX:+UnlockExperimentalVMOptions','-XX:+AlwaysPreTouch','-XX:+UseParallelGC',
    '--sun-misc-unsafe-memory-access=allow','--enable-native-access=io.questdb',
    '--add-opens=java.base/java.lang=io.questdb','--add-opens=java.base/java.lang.reflect=io.questdb',
    '--add-opens=java.base/java.nio=io.questdb','--add-opens=java.base/java.time.zone=io.questdb',
    '--add-exports=java.base/jdk.internal.vm=io.questdb','-m','io.questdb/io.questdb.ServerMain','-d',$dataPath)
$process = Start-Process -FilePath 'D:/tool/questdb/db/bin/java.exe' -ArgumentList $serverArgs -WindowStyle Hidden -PassThru `
    -WorkingDirectory $repoPath -RedirectStandardOutput (Join-Path $PSScriptRoot 'private-server-stdout.log') `
    -RedirectStandardError (Join-Path $PSScriptRoot 'private-server-stderr.log')
$native = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)"
@{ task_id='D103'; status='STARTED_AWAITING_HEALTH'; pid=$process.Id; birth_utc=$native.CreationDate.ToUniversalTime().ToString('o');
   data_root=$dataPath; http_port=19030; pg_port=18842; arguments=$serverArgs;
   formal_mutated=$false; reference_project_mutated=$false; started_at=[DateTimeOffset]::UtcNow.ToString('o') } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
Write-Output ('D103 private server started; PID=' + $process.Id)
