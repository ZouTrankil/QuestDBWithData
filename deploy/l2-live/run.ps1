param(
    [ValidateSet('plan','start','status','stop','replay','export')][string]$Action = 'status',
    [string]$Symbols = '',
    [string]$CredentialsFile = '',
    [int]$Seconds = 0,
    [switch]$Foreground,
    [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot = Join-Path $workspace 'var/l2-live'
$statusFile = Join-Path $runtimeRoot 'status.json'
if ($Action -eq 'status') {
    if (Test-Path -LiteralPath $statusFile) {
        $state = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
        $alive = $state.processId -and (Get-Process -Id $state.processId -ErrorAction SilentlyContinue)
        $state | Add-Member -NotePropertyName processAlive -NotePropertyValue ([bool]$alive) -Force
        $state | ConvertTo-Json -Depth 8
    } else { '{"producer":"java.aliyun_l2_live","phase":"not_started","processAlive":false}' }
    exit 0
}
if ($Action -eq 'stop') {
    if (!(Test-Path -LiteralPath $statusFile)) { '{"phase":"not_started"}'; exit 0 }
    $state = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$state.processId) -ErrorAction SilentlyContinue
    if (!$process -or $process.Name -notmatch '^java(w)?\.exe$' -or $process.CommandLine -notmatch 'l2-live|AliyunL2LiveMain') {
        '{"phase":"not_running"}'; exit 0
    }
    [IO.File]::WriteAllText((Join-Path $runtimeRoot 'stop.request'), 'stop', [Text.UTF8Encoding]::new($false))
    Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
    if (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue) { throw 'Collector is still draining; inspect its status. No forced termination was performed.' }
    '{"phase":"stopped"}'
    exit 0
}
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
if ($Action -eq 'start') {
    $existing = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -match 'aliyun_l2_gateway'
    })
    if ($existing.Count) { throw 'The Python Level2 collector is active. Stop it through its runtime control before single-account Java takeover.' }
    if (Test-Path -LiteralPath $statusFile) {
        $state = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
        $process = if ($state.processId) { Get-CimInstance Win32_Process -Filter ('ProcessId = ' + [int]$state.processId) -ErrorAction SilentlyContinue }
        if ($process -and $process.Name -match '^java(w)?\.exe$' -and $process.CommandLine -match 'l2-live|AliyunL2LiveMain') {
            throw 'Java Level2 collector is already running.'
        }
    }
}
if (!$NoBuild) {
$priorJavaHome = $env:JAVA_HOME
try {
    $portableJdk = Join-Path $workspace '.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12'
    if (Test-Path -LiteralPath (Join-Path $portableJdk 'bin/java.exe')) { $env:JAVA_HOME = $portableJdk }
    Push-Location $workspace
    try { & (Join-Path $workspace 'gradlew.bat') writeL2LiveRuntime --offline --console=plain; if ($LASTEXITCODE) { throw 'Live collector build failed.' } }
    finally { Pop-Location }
} finally { $env:JAVA_HOME = $priorJavaHome }
}
$javaPath = [IO.File]::ReadAllText((Join-Path $runtimeRoot 'java-path.txt')).Trim()
$classpath = [IO.File]::ReadAllText((Join-Path $runtimeRoot 'runtime-classpath.txt')).Trim()
$command = if ($Action -eq 'start') { 'session' } else { $Action }
$launchArgs = @('-Xms128m','-Xmx1g','-cp',$classpath,'com.zoutrankil.data.l2live.bootstrap.AliyunL2LiveMain',$command,'--root',$runtimeRoot)
if ($Symbols) { $launchArgs += @('--symbols',$Symbols) }
if ($CredentialsFile) { $launchArgs += @('--credentials',[IO.Path]::GetFullPath($CredentialsFile)) }
if ($Seconds) { $launchArgs += @('--seconds',[string]$Seconds) }
if ($Action -eq 'start') { $launchArgs += @('--wait-credentials','true') }
# Use a Java argfile so long Windows classpaths and paths containing spaces remain exact.
$argFile = Join-Path $runtimeRoot ('launch-' + [Guid]::NewGuid().ToString('N') + '.args')
$argText = ($launchArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }) -join [Environment]::NewLine
[IO.File]::WriteAllText($argFile, $argText, [Text.UTF8Encoding]::new($false))
if ($Action -ne 'start' -or $Foreground) {
    & $javaPath ('@' + $argFile)
    exit $LASTEXITCODE
}
$process = Start-Process -FilePath $javaPath -ArgumentList ('"@' + $argFile + '"') -WorkingDirectory $workspace -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $runtimeRoot 'collector.stdout.log') -RedirectStandardError (Join-Path $runtimeRoot 'collector.stderr.log')
$deadline = [DateTime]::UtcNow.AddSeconds(30)
while ([DateTime]::UtcNow -lt $deadline) {
    if ($process.HasExited) {
        if ($process.ExitCode -eq 0 -and (Test-Path -LiteralPath $statusFile)) {
            $final = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json
            if ($final.processId -eq $process.Id -and $final.phase -in @('non_trading_day','session_closed','stopped')) {
                $final | ConvertTo-Json -Depth 8; exit 0
            }
        }
        throw 'Collector exited during startup. Read var/l2-live/status.json for its safe failure code.'
    }
    if (Test-Path -LiteralPath $statusFile) {
        try { $state = Get-Content -LiteralPath $statusFile -Raw | ConvertFrom-Json } catch { $state = $null }
        if ($state -and $state.processId -eq $process.Id -and $state.phase -in @('checking_calendar','waiting_session','awaiting_credentials','connecting','reconnecting','running','waiting_messages','write_retry')) {
            $state | ConvertTo-Json -Depth 8; exit 0
        }
    }
    Start-Sleep -Milliseconds 250
}
throw 'Collector startup is not confirmed yet. Inspect status; no duplicate process was launched.'
