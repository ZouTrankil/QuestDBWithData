[CmdletBinding()]
param(
    [string]$ArchiveRoot = 'D:\BaiduNetdiskDownload\202609',
    [string]$RawRoot = 'D:\l2-native-raw',
    [string]$OutputRoot = 'D:\l2-native-features',
    [string[]]$Dates = @('20260928', '20260929', '20260930'),
    [ValidateRange(1, 32)]
    [int]$Workers = 4,
    [string]$SymbolsFile,
    [switch]$SkipExtract,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$sevenZip = 'C:\Program Files\7-Zip\7z.exe'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$extractReserveBytes = [long]160000000000

function Get-AbsoluteFileSystemPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $providerPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    return [System.IO.Path]::GetFullPath($providerPath)
}

function Test-PathContains {
    param([string]$ParentPath, [string]$ChildPath)
    $parent = $ParentPath.TrimEnd([char[]]'\/')
    $child = $ChildPath.TrimEnd([char[]]'\/')
    return $child.Equals($parent, [System.StringComparison]::OrdinalIgnoreCase) -or
        $child.StartsWith($parent + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-DriveCapacity {
    param([string]$Path)
    $driveRoot = [System.IO.Path]::GetPathRoot($Path)
    try {
        $drive = [System.IO.DriveInfo]::new($driveRoot)
        if (-not $drive.IsReady) { throw "Drive is not ready: $driveRoot" }
        return [ordered]@{ root = $driveRoot; freeBytes = [long]$drive.AvailableFreeSpace; totalBytes = [long]$drive.TotalSize }
    }
    catch {
        throw "Cannot determine free space for '$Path': $($_.Exception.Message)"
    }
}

$ArchiveRoot = Get-AbsoluteFileSystemPath $ArchiveRoot
$RawRoot = Get-AbsoluteFileSystemPath $RawRoot
$OutputRoot = Get-AbsoluteFileSystemPath $OutputRoot
if ((Test-PathContains $RawRoot $OutputRoot) -or (Test-PathContains $OutputRoot $RawRoot)) {
    throw 'RawRoot and OutputRoot must be separate, non-nested directories.'
}
if ($SymbolsFile) {
    $SymbolsFile = Get-AbsoluteFileSystemPath $SymbolsFile
    if (-not (Test-Path -LiteralPath $SymbolsFile -PathType Leaf)) { throw "Missing SymbolsFile: $SymbolsFile" }
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) { throw "Missing Gradle wrapper: $gradleWrapper" }
if (-not $SkipExtract -and -not (Test-Path -LiteralPath $sevenZip -PathType Leaf)) { throw "Missing 7-Zip: $sevenZip" }

$validatedDates = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($day in $Dates) {
    $parsedDate = [datetime]::MinValue
    if ($day -notmatch '^\d{8}$' -or -not [datetime]::TryParseExact($day, 'yyyyMMdd',
            [System.Globalization.CultureInfo]::InvariantCulture, [System.Globalization.DateTimeStyles]::None, [ref]$parsedDate)) {
        throw "Invalid date '$day'; use yyyyMMdd."
    }
    if (-not $validatedDates.Add($day)) { throw "Duplicate date: $day" }
}
if ($validatedDates.Count -eq 0) { throw 'At least one date is required.' }
$Dates = @($Dates | Sort-Object)

$archives = @(
    foreach ($day in $Dates) {
        $archivePath = Join-Path $ArchiveRoot ($day + '.7z')
        $exists = Test-Path -LiteralPath $archivePath -PathType Leaf
        $file = if ($exists) { Get-Item -LiteralPath $archivePath } else { $null }
        [ordered]@{
            date = $day
            path = $archivePath
            exists = $exists
            compressedBytes = if ($file) { [long]$file.Length } else { $null }
            modifiedUtc = if ($file) { $file.LastWriteTimeUtc.ToString('o') } else { $null }
            extractionExecutable = $sevenZip
            extractionArguments = @('x', '-y', $archivePath, ('-o' + $RawRoot), '-bsp0')
            extractedDateDirectory = Join-Path $RawRoot $day
        }
    }
)
$batchArguments = @('--source-root', $RawRoot, '--output-root', $OutputRoot, '--dates', ($Dates -join ','),
                    '--workers', [string]$Workers, '--resume', 'true')
if ($SymbolsFile) { $batchArguments += @('--symbols-file', $SymbolsFile) }
$runId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $OutputRoot ('run-' + $runId)
$argumentsFile = Join-Path $runRoot 'native-arguments.json'
$initFile = Join-Path $runRoot 'native-arguments.init.gradle'
$isolatedBuildRoot = Join-Path $runRoot 'gradle-build'
$projectCacheRoot = Join-Path $runRoot 'gradle-project-cache'
$gradleArguments = @('--no-daemon', '--console=plain', '--project-cache-dir', $projectCacheRoot,
                     '--init-script', $initFile, ('-Pl2CleaningArgumentsFile=' + $argumentsFile),
                     ('-Pl2CleaningBuildDirectory=' + $isolatedBuildRoot), 'l2DailyFeatureBatch')
$rawCapacity = Get-DriveCapacity $RawRoot
$outputCapacity = Get-DriveCapacity $OutputRoot
$plan = [ordered]@{
    planOnly = [bool]$PlanOnly
    skipExtract = [bool]$SkipExtract
    archiveRoot = $ArchiveRoot
    rawRoot = $RawRoot
    outputRoot = $OutputRoot
    dates = $Dates
    workers = $Workers
    archives = $archives
    rawDrive = $rawCapacity
    outputDrive = $outputCapacity
    minimumFreeBytesForExtraction = if ($SkipExtract) { 0 } else { $extractReserveBytes }
    extractionCapacitySufficient = [bool]($SkipExtract -or ($rawCapacity.freeBytes -ge $extractReserveBytes))
    archiveExpandedBytes = $null
    capacityNote = 'Expanded size is not inferred from prior archives. The extraction free-space gate reserves 160 GB; compressed file sizes above are read from the requested archives.'
    nativeTask = 'l2DailyFeatureBatch'
    nativeArguments = $batchArguments
    resume = $true
    nativeInvocation = [ordered]@{
        executable = $gradleWrapper
        workingDirectory = $projectRoot
        task = 'l2DailyFeatureBatch'
        arguments = $gradleArguments
        isolatedBuildDirectory = $isolatedBuildRoot
        projectCacheDirectory = $projectCacheRoot
        argumentsTransport = 'UTF-8 JSON file read by a temporary Gradle init script; no shell command interpolation.'
    }
}
$plan | ConvertTo-Json -Depth 8
if ($PlanOnly) { return }

if (-not $SkipExtract) {
    foreach ($archive in $archives) {
        if (-not $archive.exists) { throw "Missing archive: $($archive.path)" }
    }
    if ($rawCapacity.freeBytes -lt $extractReserveBytes) {
        throw "RawRoot drive has $($rawCapacity.freeBytes) free bytes; extraction requires at least $extractReserveBytes free bytes."
    }
}
else {
    foreach ($day in $Dates) {
        $dateDirectory = Join-Path $RawRoot $day
        if (-not (Test-Path -LiteralPath $dateDirectory -PathType Container)) { throw "Missing extracted date directory: $dateDirectory" }
    }
}
if ($outputCapacity.freeBytes -lt 1000000000) { throw 'OutputRoot drive requires at least 1 GB free space.' }

[System.IO.Directory]::CreateDirectory($OutputRoot) | Out-Null
[System.IO.Directory]::CreateDirectory($runRoot) | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $runRoot 'plan.json'), ($plan | ConvertTo-Json -Depth 8), $utf8)
$extractions = [System.Collections.Generic.List[object]]::new()
if (-not $SkipExtract) {
    [System.IO.Directory]::CreateDirectory($RawRoot) | Out-Null
    foreach ($archive in $archives) {
        $archiveBefore = Get-Item -LiteralPath $archive.path
        $hashStarted = [System.Diagnostics.Stopwatch]::StartNew()
        $archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive.path).Hash.ToLowerInvariant()
        $hashStarted.Stop()
        $extractLog = Join-Path $runRoot ('extract-' + $archive.date + '.log')
        Write-Host "Extracting $($archive.date); log: $extractLog"
        $extractStarted = [System.Diagnostics.Stopwatch]::StartNew()
        $extractArguments = @($archive.extractionArguments)
        & $sevenZip @extractArguments *> $extractLog
        $extractExitCode = $LASTEXITCODE
        $extractStarted.Stop()
        $archiveAfter = Get-Item -LiteralPath $archive.path
        $sourceStable = ($archiveBefore.Length -eq $archiveAfter.Length -and
                         $archiveBefore.LastWriteTimeUtc -eq $archiveAfter.LastWriteTimeUtc)
        $extractions.Add([ordered]@{
            date = $archive.date
            archive = $archive.path
            sourceBytes = [long]$archiveBefore.Length
            sourceModifiedUtc = $archiveBefore.LastWriteTimeUtc.ToString('o')
            sourceSha256 = $archiveHash
            hashElapsedSeconds = $hashStarted.Elapsed.TotalSeconds
            extractElapsedSeconds = $extractStarted.Elapsed.TotalSeconds
            exitCode = $extractExitCode
            sourceMetadataStable = $sourceStable
            log = $extractLog
        })
        [System.IO.File]::WriteAllText((Join-Path $runRoot 'extractions.json'), ($extractions.ToArray() | ConvertTo-Json -Depth 5), $utf8)
        if ($extractExitCode -ne 0) { throw "7-Zip failed for $($archive.date) with exit code $extractExitCode; inspect $extractLog" }
        if (-not $sourceStable) { throw "Archive changed during extraction: $($archive.path)" }
        if (-not (Test-Path -LiteralPath $archive.extractedDateDirectory -PathType Container)) {
            throw "Archive did not create expected date directory: $($archive.extractedDateDirectory)"
        }
        Write-Host "Extracted $($archive.date) in $([math]::Round($extractStarted.Elapsed.TotalSeconds, 2)) seconds."
    }
}

# Use a JSON file for native arguments, preserving spaces and apostrophes in literal paths.
[System.IO.File]::WriteAllText($argumentsFile, (ConvertTo-Json -InputObject $batchArguments), $utf8)
$initSource = @'
gradle.beforeProject { project ->
    def buildRoot = gradle.startParameter.projectProperties.get('l2CleaningBuildDirectory')
    if (!buildRoot) throw new GradleException('Missing l2CleaningBuildDirectory')
    def projectBuild = project.path == ':' ? new File(buildRoot) :
        new File(buildRoot, 'subprojects/' + project.path.substring(1).replace(':', '/'))
    project.layout.buildDirectory.set(projectBuild)
}
gradle.projectsEvaluated {
    def argumentsFile = rootProject.findProperty('l2CleaningArgumentsFile')
    if (!argumentsFile) throw new GradleException('Missing l2CleaningArgumentsFile')
    def nativeArguments = new groovy.json.JsonSlurper().parse(new File(argumentsFile.toString()), 'UTF-8')
    rootProject.tasks.named('l2DailyFeatureBatch', JavaExec).configure {
        setArgs(nativeArguments.collect { it.toString() })
    }
}
'@
[System.IO.File]::WriteAllText($initFile, $initSource, $utf8)
$nativeLog = Join-Path $runRoot 'native-batch.log'
Write-Host "Computing native L2 features for $($Dates -join ',') with $Workers workers; log: $nativeLog"
$nativeStarted = [System.Diagnostics.Stopwatch]::StartNew()
Push-Location -LiteralPath $projectRoot
try {
    & $gradleWrapper @gradleArguments *> $nativeLog
    $nativeExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    $nativeStarted.Stop()
}
$nativeSummary = [ordered]@{
    dates = $Dates
    workers = $Workers
    resume = $true
    elapsedSeconds = $nativeStarted.Elapsed.TotalSeconds
    exitCode = $nativeExitCode
    log = $nativeLog
    argumentsFile = $argumentsFile
    isolatedBuildDirectory = $isolatedBuildRoot
    projectCacheDirectory = $projectCacheRoot
}
[System.IO.File]::WriteAllText((Join-Path $runRoot 'native-summary.json'), ($nativeSummary | ConvertTo-Json -Depth 5), $utf8)
if ($nativeExitCode -ne 0) { throw "Native feature batch failed with exit code $nativeExitCode; inspect $nativeLog and date manifests under $OutputRoot" }
Write-Host "Native cleaning completed; output: $OutputRoot; run log: $runRoot"
