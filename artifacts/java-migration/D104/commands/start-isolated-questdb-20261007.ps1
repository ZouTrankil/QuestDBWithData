$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$dataPath = Join-Path $repoPath 'var/d104-isolated-questdb'
$receiptPath = Join-Path $PSScriptRoot 'private-server-start-20261007.json'
if (Test-Path -LiteralPath $receiptPath) { throw 'D104 start receipt already exists; inspect the actual server before any restart' }
if (Test-Path -LiteralPath $dataPath) { throw 'D104 root already exists; refuse reuse during initial setup' }
$listeners = @(Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in @(19040,18852,19043,19049) })
if ($listeners.Count -ne 0) { throw 'D104 candidate ports are already occupied' }
$resolvedDataPath = [System.IO.Path]::GetFullPath($dataPath)
if (-not $resolvedDataPath.StartsWith($repoPath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'D104 target escapes workspace' }
New-Item -ItemType Directory -Path (Join-Path $dataPath 'conf') | Out-Null
@'
http.net.bind.to=127.0.0.1:19040
http.min.net.bind.to=127.0.0.1:19043
pg.net.bind.to=127.0.0.1:18852
line.tcp.net.bind.to=127.0.0.1:19049
line.udp.net.bind.to=127.0.0.1:19049
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
@{ task_id='D104'; status='STARTED_AWAITING_HEALTH'; pid=$process.Id; birth_utc=$native.CreationDate.ToUniversalTime().ToString('o');
   data_root=$dataPath; http_port=19040; pg_port=18852; arguments=$serverArgs;
   formal_mutated=$false; reference_project_mutated=$false; started_at=[DateTimeOffset]::UtcNow.ToString('o') } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
Write-Output ('D104 private server started; PID=' + $process.Id)
