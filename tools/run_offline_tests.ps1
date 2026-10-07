param([string[]]$Tests = @(), [string]$EvidenceDirectory = '.gradle/refactor-baseline/latest')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
$portableJdk = Join-Path $repoRoot '.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12'
if (Test-Path -LiteralPath $portableJdk) { $env:JAVA_HOME = $portableJdk }
$evidencePath = [IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
New-Item -ItemType Directory -Force -Path $evidencePath | Out-Null
$initPath = Join-Path $evidencePath 'offline.init.gradle'
@'
allprojects {
    tasks.withType(Test).configureEach {
        exclude '**/*Live*', '**/QuestDbProtocolTest*', '**/TemporalQuestDbReadTest*',
            '**/EtfMarketOverviewCacheHitReadOnlyRecoveryTest*', '**/IndexCatalogObservedRecoveryTest*',
            '**/IndexCatalogObservedEarlyRecoveryTest*', '**/StockDetailObservedRunRecoveryTest*',
            '**/TushareSliceAcceptanceTest*', '**/SyncJobCatalogApplicationTest*'
        environment = environment.findAll { key, value ->
            !(key ==~ /(?i).*(LIVE|RECOVERY_LEDGER|EARLY_RECOVERY_LEDGER|JDB_QUESTDB_TEST_URL).*/ ||
              key ==~ /(?i)D\d+_.*/ ||
              key in ['QUESTDB_BOUNDED_READ', 'QUESTDB_DEFINITION_READ', 'QUESTDB_TEMPORAL_READ'])
        }
    }
}
'@ | Set-Content -LiteralPath $initPath -Encoding utf8
$gradleArgs = @('test', '--console=plain', '--init-script', $initPath)
foreach ($testName in $Tests) { $gradleArgs += @('--tests', $testName) }
& (Join-Path $repoRoot 'gradlew.bat') @gradleArgs 2>&1 | Tee-Object -FilePath (Join-Path $evidencePath 'gradle.log')
$testExit = $LASTEXITCODE
if (Test-Path -LiteralPath 'build/test-results/test') {
    $resultFolder = 'test-results-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff')
    $resultPath = Join-Path $evidencePath $resultFolder
    New-Item -ItemType Directory -Path $resultPath | Out-Null
    Get-ChildItem -LiteralPath 'build/test-results/test' -Filter 'TEST-*.xml' -File |
        Copy-Item -Destination $resultPath
    $resultFolder | Set-Content -LiteralPath (Join-Path $evidencePath 'latest-results.txt') -Encoding utf8
}
exit $testExit
