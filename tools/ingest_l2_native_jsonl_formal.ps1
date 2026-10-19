[CmdletBinding()]
param(
    [string]$OutputRoot = 'G:\BaiduNetdiskDownload\202610\_l2_features',
    [string[]]$Dates = @('20261008', '20261009', '20261012', '20261013', '20261014', '20261015'),
    [string]$LogRoot = 'F:\repo\engine\data\var\l2_automation\logs'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'
$querySql = 'SELECT * FROM l2_daily_features ORDER by ts desc;'
$queryUri = 'http://127.0.0.1:9000/exec'
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$reportPath = Join-Path $LogRoot "formal-ingest-$runId.json"
$queryResultPath = Join-Path $LogRoot "l2_daily_features-latest-after-ingest-$runId.json"
$report = [ordered]@{
    runId = $runId
    status = 'RUNNING'
    startedAtLocal = (Get-Date).ToString('o')
    outputRoot = $OutputRoot
    dates = $Dates
    manifestPreflight = @()
    ingestedDays = @()
    querySql = $querySql
    queryResultFile = $queryResultPath
    queryRowsReturned = $null
    queryLatestTimestamp = $null
    queryLatestSymbol = $null
    tableLatestTimestamp = $null
    tableTotalRows = $null
    failure = $null
}

function Save-Report {
    $json = $script:report | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($script:reportPath, $json, [System.Text.UTF8Encoding]::new($false))
}

try {
    [System.IO.Directory]::CreateDirectory($LogRoot) | Out-Null
    if (-not (Test-Path -LiteralPath $repoRoot -PathType Container)) { throw "Missing repository root: $repoRoot" }
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) { throw "Missing Gradle wrapper: $gradleWrapper" }
    if (-not (Test-Path -LiteralPath $OutputRoot -PathType Container)) { throw "Missing L2 output root: $OutputRoot" }

    $uniqueDates = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($date in $Dates) {
        $parsed = [datetime]::MinValue
        if ($date -notmatch '^\d{8}$' -or -not [datetime]::TryParseExact($date, 'yyyyMMdd',
                [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::None, [ref]$parsed)) {
            throw "Invalid date '$date'; expected yyyyMMdd."
        }
        if (-not $uniqueDates.Add($date)) { throw "Duplicate date: $date" }
    }
    if ($Dates.Count -eq 0) { throw 'At least one date is required.' }

    # Preflight the whole requested set before the first formal-table write.
    foreach ($date in $Dates) {
        $dayRoot = Join-Path $OutputRoot $date
        $manifestPath = Join-Path $dayRoot 'manifest.json'
        $aggregatePath = Join-Path $dayRoot 'l2_daily_features.jsonl'
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "Missing completion manifest: $manifestPath" }
        if (-not (Test-Path -LiteralPath $aggregatePath -PathType Leaf)) { throw "Missing daily aggregate: $aggregatePath" }
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($manifest.date -ne $date -or $manifest.status -ne 'COMPLETE' -or -not $manifest.published -or
                [long]$manifest.counts.failed -ne 0 -or [long]$manifest.counts.success -lt 1 -or
                @($manifest.selection.requestedNotPresent).Count -gt 0) {
            throw "L2 feature manifest is not complete for $date; inspect $manifestPath"
        }
        $aggregateHash = (Get-FileHash -LiteralPath $aggregatePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($aggregateHash -ne [string]$manifest.aggregateSha256) { throw "Daily aggregate SHA-256 mismatch for $date" }
        $report.manifestPreflight += [pscustomobject]@{
            date = $date
            status = $manifest.status
            successfulSymbols = [long]$manifest.counts.success
            emptySymbols = [long]$manifest.counts.empty
            failedSymbols = [long]$manifest.counts.failed
            aggregateSha256 = $aggregateHash
            manifestPath = $manifestPath
        }
    }

    $jdkRoot = @(
        Get-ChildItem -LiteralPath 'D:\' -Directory |
            ForEach-Object { Join-Path $_.FullName 'JAVA\temurin24-install\jdk-24.0.2+12' } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Container }
    )[0]
    if (-not $jdkRoot) { throw 'Could not find the installed Java 24 toolchain under a top-level D: directory.' }
    $env:JAVA_HOME = $jdkRoot
    $env:Path = (Join-Path $jdkRoot 'bin') + ';' + $env:Path

    foreach ($date in $Dates) {
        $isoDate = [datetime]::ParseExact($date, 'yyyyMMdd', [System.Globalization.CultureInfo]::InvariantCulture).ToString('yyyy-MM-dd')
        $dayLog = Join-Path $LogRoot "formal-ingest-$date-$runId.log"
        $appArguments = "ingest-formal-l2-jsonl-day --date $isoDate --source-root `"$OutputRoot`""
        $gradleArguments = @('--no-daemon', '--console=plain', ':markets:cn:data-app:run', '--args', $appArguments)
        Write-Host "Ingesting verified L2 JSONL for $date into formal l2_daily_features; log: $dayLog"
        $previousErrorActionPreference = $ErrorActionPreference
        Push-Location -LiteralPath $repoRoot
        try {
            # Javac may emit informational stderr notes; rely on the native exit code.
            $ErrorActionPreference = 'Continue'
            & $gradleWrapper @gradleArguments *> $dayLog
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
            Pop-Location
        }
        $logText = Get-Content -LiteralPath $dayLog -Raw
        $jsonMatch = [regex]::Match($logText, '(?ms)^\{.*?^\}')
        if ($exitCode -ne 0) { throw "Formal L2 ingestion failed for $date (exit $exitCode); inspect $dayLog" }
        if (-not $jsonMatch.Success) { throw "Formal L2 ingestion produced no JSON evidence for $date; inspect $dayLog" }
        $cliResult = $jsonMatch.Value | ConvertFrom-Json
        if ($cliResult.operation -ne 'ingest-formal-l2-jsonl-day' -or $cliResult.evidence.day -ne $isoDate -or
                $cliResult.evidence.readerVersion -ne 'java-native-batch-jsonl-v1' -or
                [long]$cliResult.evidence.verifiedFields -ne [long]$cliResult.evidence.selectedRows * 110L) {
            throw "Formal L2 evidence did not match the requested date/schema for $date; inspect $dayLog"
        }
        $report.ingestedDays += [pscustomobject]@{
            date = $date
            expectedRows = [long]$cliResult.evidence.selectedRows
            insertedRows = [long]$cliResult.evidence.insertedRows
            verifiedFields = [long]$cliResult.evidence.verifiedFields
            targetIdentity = $cliResult.evidence.targetIdentity
            targetVersion = $cliResult.evidence.targetVersion
            readbackDigest = $cliResult.evidence.readbackDigest
            sourceFingerprint = $cliResult.evidence.sourceFingerprint
            logPath = $dayLog
        }
        Save-Report
    }

    $curl = (Get-Command curl.exe -ErrorAction Stop).Source
    $querySummary = & $curl --silent --show-error --fail --get `
        --data-urlencode "query=$querySql" `
        --data-urlencode 'limit=1000' `
        --output $queryResultPath `
        --write-out '%{http_code}|%{size_download}|%{time_total}' `
        $queryUri
    if ($LASTEXITCODE -ne 0) { throw "Post-ingestion QuestDB SELECT failed; curl summary: $querySummary" }
    $queryFields = ([string]$querySummary).Split('|')
    if ($queryFields.Count -lt 3 -or $queryFields[0] -ne '200') { throw "Unexpected QuestDB SELECT response: $querySummary" }
    $queryDocument = Get-Content -LiteralPath $queryResultPath -Raw | ConvertFrom-Json
    if ($queryDocument.query -ne $querySql -or @($queryDocument.dataset).Count -eq 0) {
        throw 'Post-ingestion SELECT returned no rows or a different SQL statement.'
    }
    $columnNames = @($queryDocument.columns | ForEach-Object { $_.name })
    $tsIndex = [array]::IndexOf($columnNames, 'ts')
    $symbolIndex = [array]::IndexOf($columnNames, 'symbol')
    if ($tsIndex -lt 0 -or $symbolIndex -lt 0) { throw 'Post-ingestion SELECT is missing ts or symbol.' }
    $report.queryRowsReturned = [int]$queryDocument.count
    $report.queryLatestTimestamp = $queryDocument.dataset[0][$tsIndex]
    $report.queryLatestSymbol = $queryDocument.dataset[0][$symbolIndex]
    $report.queryHttpStatus = [int]$queryFields[0]
    $report.queryResponseBytes = [long]$queryFields[1]
    $report.queryElapsedSeconds = [double]$queryFields[2]

    $summarySql = 'SELECT max(ts) AS max_ts, count() AS row_count FROM l2_daily_features;'
    $summaryUri = $queryUri + '?query=' + [System.Uri]::EscapeDataString($summarySql)
    $summary = Invoke-RestMethod -Uri $summaryUri -TimeoutSec 120
    if (@($summary.dataset).Count -ne 1) { throw 'QuestDB table summary returned an unexpected shape.' }
    $report.tableLatestTimestamp = $summary.dataset[0][0]
    $report.tableTotalRows = [long]$summary.dataset[0][1]
    $report.status = 'COMPLETE'
}
catch {
    $report.status = 'FAILED'
    $report.failure = $_.Exception.Message
}
finally {
    $report.finishedAtLocal = (Get-Date).ToString('o')
    $report.reportPath = $reportPath
    Save-Report
    Write-Host "Formal L2 ingestion report: $reportPath"
    if ($report.queryResultFile) { Write-Host "Formal L2 SELECT result: $queryResultPath" }
}

if ($report.status -ne 'COMPLETE') { exit 1 }
exit 0
