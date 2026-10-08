# Runs isolated offline suites; -Tests preserves the root aggregate selector entry.
[CmdletBinding()]
param([string[]]$Tests = @(), [string]$EvidenceDirectory = '.gradle/refactor-baseline/latest',
      [string]$JavaHome = '', [switch]$DryRun)
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidenceRoot = [IO.Path]::GetFullPath((Join-Path $workspace $EvidenceDirectory))
$allowedEvidence = [IO.Path]::GetFullPath((Join-Path $workspace '.gradle')) + [IO.Path]::DirectorySeparatorChar
if (!$evidenceRoot.StartsWith($allowedEvidence,[StringComparison]::OrdinalIgnoreCase)) { throw 'EvidenceDirectory must be a child of this repository .gradle directory' }
$runName = 'run-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfff') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$runRoot = Join-Path $evidenceRoot $runName
$stages = if ($Tests.Count) {
    $selected = @(':test')
    foreach ($selector in $Tests) { $selected += @('--tests',$selector) }
    @(@{name='selected-root-aggregate';tasks=$selected})
} else { @(
    @{ name='isolated-modules'; tasks=@(':data-core:test',':data-app:test',':batch-app:test') },
    @{ name='root-contracts'; tasks=@(':test','--tests','com.zoutrankil.architecture.*','--tests','com.zoutrankil.batch.DailySemanticsCompatibilityTest') }
) }
$common = @('--offline','--no-daemon','--no-configuration-cache','--console=plain','--rerun-tasks')
foreach ($stage in $stages) {
    [ordered]@{ status='NOT_RUN'; cwd=$workspace; executable='gradlew.bat'; arguments=$common+$stage.tasks } | ConvertTo-Json -Depth 5
}
if ($DryRun) { return }
foreach ($module in @('data-core','data-app','batch-app')) {
    if (!(Test-Path -LiteralPath (Join-Path $workspace "$module/build.gradle") -PathType Leaf)) { throw 'Three physical modules are required' }
}
foreach ($spec in @(
    @{ path='data-app/src/test/java/com/zoutrankil/data/service/SyncJobCatalogApplicationTest.java'; output='artifacts/java-migration/D001/job-definitions.json' },
    @{ path='data-app/src/test/java/com/zoutrankil/data/index/application/IndexMembershipMergeEvidenceTest.java'; output='base.resolve("merge-evidence.json").toFile()' }
)) {
    $body = Get-Content -LiteralPath (Join-Path $workspace $spec.path) -Raw
    if ($body.Contains($spec.output) -or !$body.Contains('TempDir')) { throw ('Required temporary output repair is absent: '+$spec.path) }
}
if (!$JavaHome) { $JavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','Process') }
if (!$JavaHome) { $JavaHome = Join-Path $workspace '.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12' }
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf)) { throw 'Java 24 JDK is required; pass -JavaHome' }
New-Item -ItemType Directory -Path $runRoot | Out-Null
$init = Join-Path $runRoot 'offline.init.gradle'
$policy = @'
import groovy.json.JsonOutput
import org.gradle.api.tasks.testing.Test
gradle.projectsEvaluated {
    def run = new File(gradle.rootProject.findProperty('offline.outputRoot').toString()).canonicalFile
    def allowed = new File(gradle.rootProject.projectDir, '.gradle').canonicalFile
    if (!run.toPath().startsWith(allowed.toPath()) || run == allowed) throw new GradleException('Isolated offline output path required')
    def disabled = ["APP_QUESTDB_PASSWORD", "APP_QUESTDB_USERNAME", "D002_RECOVERY_LEDGER", "D002_RECOVERY_RUN", "D002_RECOVERY_WRITER_PROOF", "D003_EARLY_RECOVERY_LEDGER", "D003_EARLY_RECOVERY_RUN", "D003_EARLY_RECOVERY_TABLE", "D003_EARLY_WRITER_PROOF", "D085_LIVE_ACCEPTANCE", "D086_LIVE_ACCEPTANCE", "D087_LIVE_ACCEPTANCE", "D088_LIVE_ACCEPTANCE", "D089_LIVE_ACCEPTANCE", "D090_LIVE_READ", "D091_LIVE_READ", "D092_LIVE_READ", "D093_LIVE_READ", "D094_LIVE_READ", "D095_LIVE_MATERIALIZE", "D095_LIVE_READ", "D096_LIVE_READ", "D097_LIVE_READ", "D098_LIVE_MATERIALIZE", "D098_LIVE_MATERIALIZE_CONTINUE", "D098_LIVE_READ", "D099_LIVE_READ", "D100_CAPTURE_CURSOR", "D100_LIVE_READ", "D101_HIT_RECOVERY", "D101_LIVE_STAGE", "D101_PROCESS_PREVIEW", "D101_PYTHON_EXECUTABLE", "D102_LIVE_READ", "D103_LIVE_STAGE", "D103_READONLY_RECOVERY", "D104_LIVE_STAGE", "D105_LIVE_READ", "JDB_QUESTDB_TEST_URL", "JDB_SOURCE_CODE", "JDB_SOURCE_DATASETS", "JDB_SOURCE_LIVE", "JDB_TUSHARE_TOKEN", "MARKET_ACCEPTANCE_FROM", "QUESTDB_BOUNDED_READ", "QUESTDB_DEFINITION_READ", "QUESTDB_LIVE_SMOKE", "QUESTDB_TEMPORAL_READ", "QUESTDB_WRITE_LIVE", "RUN_RETAINED_PARITY", "TUSHARE_HTTP_LIVE", "TUSHARE_PAGE_LIVE"] as Set
    def externalPatterns = ["**/*Live*", "**/QuestDbProtocolTest*", "**/EquityStyleMonthlyInitialReadOnlyRecoveryTest*", "**/EtfMarketOverviewCacheHitReadOnlyRecoveryTest*", "**/TemporalQuestDbReadTest*", "**/IndexCatalogObservedEarlyRecoveryTest*", "**/IndexCatalogObservedRecoveryTest*", "**/StockDetailObservedRunRecoveryTest*"]
    def scrub = { env -> env.findAll { key, value ->
        !disabled.any { it.equalsIgnoreCase(key.toString()) } &&
            !(key.toString() ==~ /(?i).*(LIVE|RECOVERY_LEDGER|EARLY_RECOVERY_LEDGER|JDB_QUESTDB_TEST_URL).*/) &&
            !(key.toString() ==~ /(?i)D\d+_.*/)
    } }
    gradle.rootProject.allprojects { project ->
        project.tasks.withType(Test).configureEach { task ->
            useJUnitPlatform { excludeTags 'retained-parity' }
            externalPatterns.each { exclude it }
            filter.excludeTestsMatching('com.zoutrankil.data.service.TushareSliceAcceptanceTest.realCodeSlicesUseSharedSourceAndConsumePagesSerially')
            setEnvironment(scrub(environment))
            workingDir gradle.rootProject.projectDir
            def taskDir = new File(run, task.path.substring(1).replace(':','/'))
            reports.junitXml.outputLocation.set(new File(taskDir, 'xml'))
            reports.html.outputLocation.set(new File(taskDir, 'html'))
            binaryResultsDirectory.set(new File(taskDir, 'binary'))
            systemProperty 'java.io.tmpdir', new File(taskDir, 'tmp').absolutePath
            systemProperty 'logging.file.path', new File(taskDir, 'logs').absolutePath
            systemProperty 'l2.preview.source-root', new File(gradle.rootProject.projectDir, 'batch-app/src/main/java').absolutePath
            doFirst {
                def before = new LinkedHashMap(environment)
                setEnvironment(scrub(before))
                new File(taskDir, 'tmp').mkdirs()
                new File(taskDir, 'policy.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
                    task: task.path, excludedClasses: excludes.toList().sort(), excludedMethods: filter.excludePatterns.toList().sort(),
                    excludedTags: options.excludeTags, removedEnvironmentNames: (before.keySet() - environment.keySet()).toList().sort(),
                    classpath: classpath.files.collect { it.absolutePath }
                ]))
            }
        }
    }
}
'@
[IO.File]::WriteAllText($init,$policy,[Text.UTF8Encoding]::new($false))
$oldJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','Process')
$oldPath = [Environment]::GetEnvironmentVariable('PATH','Process')
$exitCodes = @()
$xmlRows = @()
Push-Location $workspace
try {
    $env:JAVA_HOME=$JavaHome; $env:PATH=(Join-Path $JavaHome 'bin')+[IO.Path]::PathSeparator+$oldPath
    foreach ($stage in $stages) {
        $stageRoot=Join-Path $runRoot $stage.name; New-Item -ItemType Directory -Path $stageRoot | Out-Null
        $arguments=$common+@('-I',$init,('-Poffline.outputRoot='+$stageRoot))+$stage.tasks
        & (Join-Path $workspace 'gradlew.bat') @arguments *> (Join-Path $stageRoot 'gradle.log')
        $code=$LASTEXITCODE
        $exitCodes+=@{stage=$stage.name;exitCode=$code}
        $exitCodes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'stage-exits.json') -Encoding utf8
        if ($code -ne 0) { break }
    }
} finally {
    [Environment]::SetEnvironmentVariable('JAVA_HOME',$oldJavaHome,'Process')
    [Environment]::SetEnvironmentVariable('PATH',$oldPath,'Process')
    Pop-Location
}
foreach ($file in Get-ChildItem -LiteralPath $runRoot -Recurse -File -Filter 'TEST-*.xml') {
    [xml]$suite=Get-Content -LiteralPath $file.FullName -Raw
    foreach ($test in $suite.testsuite.testcase) {
        $status=if ($test.SelectSingleNode('failure') -or $test.SelectSingleNode('error')) {'FAILED'} elseif ($test.SelectSingleNode('skipped')) {'SKIPPED'} else {'PASSED'}
        $xmlRows+=@{class=$test.classname;name=$test.name;status=$status;xml=$file.FullName}
    }
}
$resultDir = Join-Path $runRoot 'test-results'
New-Item -ItemType Directory -Path $resultDir | Out-Null
$xmlFiles = @($xmlRows | ForEach-Object xml | Sort-Object -Unique)
foreach ($path in $xmlFiles) {
    $target = Join-Path $resultDir ([IO.Path]::GetFileName($path))
    if (Test-Path -LiteralPath $target) { throw ('Duplicate XML class file across supposedly isolated suites: '+$target) }
    Copy-Item -LiteralPath $path -Destination $target
}
($runName+'/test-results') | Set-Content -LiteralPath (Join-Path $evidenceRoot 'latest-results.txt') -Encoding utf8
$xmlRows | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runRoot 'fresh-results.json') -Encoding utf8
$summary=[ordered]@{scope=if($Tests.Count){'selected-root-aggregate'}else{'three-isolated-modules-plus-root-contracts'};tests=$xmlRows.Count;passed=@($xmlRows | Where-Object status -eq 'PASSED').Count;skipped=@($xmlRows | Where-Object status -eq 'SKIPPED').Count;failed=@($xmlRows | Where-Object status -eq 'FAILED').Count;retainedParity='Six retained methods are excluded; this run does not establish their input availability or parity.';output=$runRoot}
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8
$summary | ConvertTo-Json
if ($exitCodes.Count -ne $stages.Count -or @($exitCodes | Where-Object exitCode -ne 0).Count -gt 0 -or $xmlRows.Count -eq 0 -or $summary.failed -gt 0) { throw ('Offline gate incomplete or failed; inspect '+$runRoot) }
