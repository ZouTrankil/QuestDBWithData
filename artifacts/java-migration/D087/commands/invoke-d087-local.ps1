param(
    [string] $ProjectRoot = 'C:\Users\zouqiang\IdeaProjects\QuestDBWithData'
)
$credentialFile = 'D:\work\fund_2\back-monitor\.env'
$credentials = @{}
foreach ($line in Get-Content -LiteralPath $credentialFile) {
    if ($line -match '^\s*(QUESTDB_USER|QUESTDB_PASSWORD)\s*=\s*(.*)\s*$') {
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $credentials[$matches[1]] = $value
    }
}
if (!$credentials.ContainsKey('QUESTDB_USER') -or !$credentials.ContainsKey('QUESTDB_PASSWORD')) {
    throw 'QuestDB credentials are absent from the Python project environment file.'
}
$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$suffix = $stamp + '_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$env:JAVA_HOME = 'C:\Users\zouqiang\.jdks\jdk-24.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:APP_QUESTDB_HOST = 'localhost'
$env:APP_QUESTDB_PGPORT = '8812'
$env:APP_QUESTDB_QWPPORT = '9000'
$env:APP_QUESTDB_USERNAME = $credentials['QUESTDB_USER']
$env:APP_QUESTDB_PASSWORD = $credentials['QUESTDB_PASSWORD']
$env:APP_QUESTDB_DATABASE = 'qdb'
$env:APP_SYNC_L2INTRADAYBARFEATURES_TARGETTABLE = "java_d087_l2_intraday_bar_features_$suffix"
$env:APP_SYNC_LEDGERPATH = "var/d087-live-$suffix.sqlite3"
$env:APP_SYNC_L2INTRADAYBARFEATURES_DATASETROOT = 'D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset'
$env:APP_SYNC_L2INTRADAYBARFEATURES_PYTHONEXECUTABLE = 'D:/work/fund_2/back-monitor/.venv/Scripts/python.exe'
$env:APP_SYNC_L2INTRADAYBARFEATURES_READERSCRIPT = 'tools/read_l2_intraday_bar_features.py'
$env:D087_LIVE_ACCEPTANCE = 'true'
$contextPath = Join-Path $PSScriptRoot "live-run-context-$suffix.json"
$context = [ordered]@{ status = 'RUNNING'; targetTable = $env:APP_SYNC_L2INTRADAYBARFEATURES_TARGETTABLE
    ledgerPath = $env:APP_SYNC_LEDGERPATH; javaHome = $env:JAVA_HOME; startedAt = [DateTimeOffset]::Now.ToString('o') }
[System.IO.File]::WriteAllText($contextPath, (ConvertTo-Json $context -Depth 4) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
Push-Location $ProjectRoot
try {
    $lines = & .\gradlew.bat -x compileJava -x compileTestJava test --rerun-tasks `
        --tests com.zoutrankil.questdbwithdata.mapper.L2IntradayBarFeaturesMappingTest `
        --tests com.zoutrankil.questdbwithdata.service.L2IntradayBarFeaturesLiveAcceptanceTest 2>&1
    $code = $LASTEXITCODE
} finally { Pop-Location }
$safe = $lines -join [Environment]::NewLine
$safe = $safe -replace '(?i)(username\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safe = $safe -replace '(?i)(password\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safe = $safe -replace '(?i)(token\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot "live-acceptance-$suffix.log"),
    $safe + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
$context.status = if ($code -eq 0) { 'COMPLETED' } else { 'FAILED' }
$context.finishedAt = [DateTimeOffset]::Now.ToString('o')
$context.exitCode = $code
[System.IO.File]::WriteAllText($contextPath, (ConvertTo-Json $context -Depth 4) + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false))
Write-Output $safe
if ($code -ne 0) { throw "D087 live acceptance failed with exit code $code; evidence was preserved." }
