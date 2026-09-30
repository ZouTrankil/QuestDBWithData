param(
    [string[]] $CliArgs,
    [Parameter(Mandatory = $true)] [string] $LogName,
    [string[]] $TestClasses,
    [string] $InitScript,
    [string] $ProjectRoot
)

$projectRoot = if ($ProjectRoot) { (Resolve-Path -LiteralPath $ProjectRoot).Path }
    else { (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path }
$sourceEnv = 'D:\work\fund_2\back-monitor\.env'
$credentialValues = @{}
foreach ($line in Get-Content -LiteralPath $sourceEnv) {
    if ($line -match '^\s*(QUESTDB_USER|QUESTDB_PASSWORD)\s*=\s*(.*)\s*$') {
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $credentialValues[$matches[1]] = $value
    }
}
if (!$credentialValues.ContainsKey('QUESTDB_USER') -or !$credentialValues.ContainsKey('QUESTDB_PASSWORD')) {
    throw 'QuestDB credentials are absent from the source project environment file.'
}

$env:APP_QUESTDB_HOST = 'localhost'
$env:APP_QUESTDB_PGPORT = '8812'
$env:APP_QUESTDB_QWPPORT = '9000'
$env:APP_QUESTDB_USERNAME = $credentialValues['QUESTDB_USER']
$env:APP_QUESTDB_PASSWORD = $credentialValues['QUESTDB_PASSWORD']
$env:APP_QUESTDB_DATABASE = 'qdb'
$env:APP_SYNC_L2MANIFEST_TARGETTABLE = 'java_d085_l2_dataset_manifest_acceptance_20260930'
$env:APP_SYNC_LEDGERPATH = 'var/d085-l2-manifest-ledger.sqlite3'

Push-Location $projectRoot
try {
    if ($TestClasses -and $TestClasses.Count -gt 0) {
        $env:D085_LIVE_ACCEPTANCE = 'true'
        $testArguments = @('test')
        foreach ($testClass in $TestClasses) { $testArguments += @('--tests', $testClass) }
        if ($InitScript) { $testArguments += @('--init-script', $InitScript) }
        $outputLines = & .\gradlew.bat @testArguments 2>&1
    } else {
        if (!$CliArgs -or $CliArgs.Count -eq 0) { throw 'A finite CLI command is required.' }
        $outputLines = & .\gradlew.bat run --args ($CliArgs -join ' ') 2>&1
    }
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

$safeOutput = $outputLines -join [Environment]::NewLine
$safeOutput = $safeOutput -replace '(?i)(username\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safeOutput = $safeOutput -replace '(?i)(password\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$safeOutput = $safeOutput -replace '(?i)(token\s*[=:]\s*)[^;\s,]+', '$1[REDACTED]'
$logPath = Join-Path $PSScriptRoot $LogName
[System.IO.File]::WriteAllText($logPath, $safeOutput)
Write-Output $safeOutput
exit $exitCode
