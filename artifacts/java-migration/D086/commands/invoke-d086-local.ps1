param(
    [string[]] $CliArgs,
    [string[]] $TestClasses,
    [Parameter(Mandatory = $true)] [string] $LogName,
    [string] $ProjectRoot = 'C:\Users\zouqiang\AppData\Local\Temp\QuestDBWithData-D086-TestIsolation-20260930'
)
$credentialFile = 'D:\work\fund_2\back-monitor\.env'
$credentialValues = @{}
foreach ($line in Get-Content -LiteralPath $credentialFile) {
    if ($line -match '^\s*(QUESTDB_USER|QUESTDB_PASSWORD)\s*=\s*(.*)\s*$') {
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $credentialValues[$matches[1]] = $value
    }
}
if (!$credentialValues.ContainsKey('QUESTDB_USER') -or !$credentialValues.ContainsKey('QUESTDB_PASSWORD')) { throw 'QuestDB credentials are absent from the source project environment file.' }
$env:APP_QUESTDB_HOST = 'localhost'
$env:APP_QUESTDB_PGPORT = '8812'
$env:APP_QUESTDB_QWPPORT = '9000'
$env:APP_QUESTDB_USERNAME = $credentialValues['QUESTDB_USER']
$env:APP_QUESTDB_PASSWORD = $credentialValues['QUESTDB_PASSWORD']
$env:APP_QUESTDB_DATABASE = 'qdb'
$env:APP_SYNC_L2DAILYFEATURES_TARGETTABLE = 'java_d086_l2_daily_features_acceptance_final8_20260930'
$env:APP_SYNC_LEDGERPATH = 'var/d086-l2-daily-features-ledger-final8.sqlite3'
$env:APP_SYNC_L2DAILYFEATURES_DATASETROOT = 'D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset'
$env:APP_SYNC_L2DAILYFEATURES_PYTHONEXECUTABLE = 'D:/work/fund_2/back-monitor/.venv/Scripts/python.exe'
$env:APP_SYNC_L2DAILYFEATURES_READERSCRIPT = 'tools/read_l2_daily_features.py'
$env:D086_LIVE_ACCEPTANCE = 'true'
Push-Location $ProjectRoot
try {
    if ($TestClasses -and $TestClasses.Count -gt 0) {
        $args = @('test')
        foreach ($testClass in $TestClasses) { $args += @('--tests', $testClass) }
        $lines = & .\gradlew.bat @args 2>&1
    } elseif ($CliArgs -and $CliArgs.Count -gt 0) {
        $lines = & .\gradlew.bat run --args ($CliArgs -join ' ') 2>&1
    } else { throw 'A D086 CLI command or test class is required.' }
    $code = $LASTEXITCODE
} finally { Pop-Location }
$safe = $lines -join [Environment]::NewLine
$safe = $safe -replace '(?i)(username\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safe = $safe -replace '(?i)(password\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safe = $safe -replace '(?i)(token\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot $LogName), $safe)
Write-Output $safe
exit $code








