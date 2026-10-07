$ErrorActionPreference = 'Stop'
$base = 'artifacts/main-strategy-java-refresh/20261007'
function Read-AuditJson([string]$name) { Get-Content -LiteralPath ($base + '/' + $name) -Raw | ConvertFrom-Json }
function File-Proof([string]$path) { [ordered]@{ path = (Resolve-Path -LiteralPath $path).Path; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = (Get-Item -LiteralPath $path).Length } }
$ledger = Read-AuditJson 'goal-java-routes-ledger-readonly.json'
$build = Read-AuditJson 'goal-final-build-receipt.json'
$launch = Read-AuditJson 'goal-local-regime-plan-launch.json'
$observation = Read-AuditJson 'goal-current-questdb-observation.json'
$acceptance = Read-AuditJson 'main-strategy-readonly-acceptance.json'
$market = Read-AuditJson 'market-sentiment-native-readback.json'
$regime = Read-AuditJson 'regime-native-java-audit.json'
$etf = Read-AuditJson 'etf-margin-audit.json'
$etfReceipts = Read-AuditJson 'goal-etf-retained-receipt-audit.json'
$planText = Get-Content -LiteralPath $launch.log -Raw
$plan = $planText.Substring($planText.IndexOf('{')) | ConvertFrom-Json
if ($build.exit_code -ne 0 -or $launch.exit_code -ne 0 -or $plan.status -ne 'PLANNED' -or $plan.executed -ne $false) { throw 'Current build/plan receipt is not successful plan-only evidence' }
if ($launch.arguments -notcontains '-Dapp.questdb.host=127.0.0.1') { throw 'Local override missing' }
if ($plan.plan.targetBefore.tableId -ne 3017 -or @($plan.plan.targetBefore.rows).Count -ne 666 -or $plan.plan.targetBefore.fingerprint -ne $regime.fullTargetCanonicalSha256) { throw 'Current local target differs from verified publication' }
if (-not $market.passed -or -not $regime.passed -or $acceptance.status -ne 'ok') { throw 'Accepted data audit failed' }
$accepted = @($ledger.runs | Where-Object { $_.summary.state -eq 'VERIFIED' -or $_.summary.state -eq 'VERIFIED_EMPTY' })
$verified = @($accepted | Where-Object { $_.summary.state -eq 'VERIFIED' })
foreach ($run in $verified) {
    $v = $run.runPayload.verification
    if (-not $v.passed -or $v.expectedRows -ne $v.actualRows -or $v.expectedRows -ne $v.matchedRows -or $v.missingKeys -ne 0 -or $v.duplicateKeys -ne 0 -or $v.mismatchedRows -ne 0) { throw ('Incomplete verified run ' + $run.summary.id) }
}
$notTerminal = @($ledger.runs | Where-Object { $_.summary.state -notin @('VERIFIED','VERIFIED_EMPTY','FAILED','CANCELLED') })
if ($notTerminal.Count -gt 0) { throw 'Nonterminal requested dataset run remains' }
if (@($etfReceipts.etfDaily | Where-Object { -not $_.complete -or -not $_.fingerprintMatches }).Count -gt 0 -or @($etfReceipts.etfPortfolio.sourceFiles | Where-Object { -not $_.fingerprintMatches }).Count -gt 0 -or -not $etfReceipts.etfPortfolio.completion.complete) { throw 'ETF preserved source evidence failed' }
$routeDefinitions = @(
    @{ dataset='stk_factor'; endpoint='stk_factor'; source='StockFactorSource'; columns=35; note='Legacy 35-column contract, jobVersion 2; no stk_factor_pro 261-column substitution.' },
    @{ dataset='daily_basic'; endpoint='daily_basic'; source='DailyBasicSource'; columns=18; note='Exact trade-date bounded response; full key/value verification.' },
    @{ dataset='stk_limit'; endpoint='stk_limit'; source='StockLimitSource'; columns=4; note='Formal existing target allowed only explicit bounded BACKFILL/RECONCILE; resume retains guard.' },
    @{ dataset='moneyflow'; endpoint='moneyflow'; source='MoneyflowSource'; columns=20; note='Formal existing target allowed only explicit bounded BACKFILL/RECONCILE; source window and resume guards retained.' },
    @{ dataset='etf_daily'; endpoint='fund_daily'; source='EtfDailySource'; note='Three complete retained source receipts and full key/value readback.' },
    @{ dataset='etf_factor'; endpoint='fund_factor_pro'; source='EtfFactorSource'; columns=89; note='Existing official 89-column fund_factor_pro mapper; accepts declared ETF code suffixes.' },
    @{ dataset='etf_portfolio'; endpoint='fund_portfolio'; source='EtfPortfolioSource'; note='Announcement-date source scan 0928-0930 complete and verified empty; actual latest disclosures retained by explicit user preference.' },
    @{ dataset='market_sentiment_daily'; endpoint=$null; source='MarketSentimentDailyCalculation'; columns=53; note='Native Java calculation from pinned QuestDB sources; saved IPO listing/factor receipts use the existing WebFlux client. Completed run version 1; final owner version 2 current plan only.' },
    @{ dataset='regime_features_monitor_daily'; endpoint=$null; source='RegimeFeaturesMonitorDailyCalculation'; columns=21; note='Native Java calculation from pinned QuestDB sources and retained IPO evidence; completed run version 1. Final owner version 2 current local plan only.' }
)
$routes = foreach ($def in $routeDefinitions) {
    $runs = @($accepted | Where-Object { $_.summary.jobId -eq ('data.' + $def.dataset) })
    if ($runs.Count -eq 0) { throw ('Dataset has no accepted Java run: ' + $def.dataset) }
    [ordered]@{
        dataset=$def.dataset; endpoint=$def.endpoint; source_owner=$def.source; source_columns=$def.columns; note=$def.note
        latest_required_date=if ($def.dataset -eq 'etf_portfolio') { 'actual latest disclosure accepted' } else { '2026-09-30' }
        rows_0930=@($observation.queries.source0930.result.dataset | Where-Object { $_[0] -eq $def.dataset } | ForEach-Object { $_[1] })
        runs=@($runs | ForEach-Object { [ordered]@{ run_id=$_.summary.id; job_version=$_.summary.jobVersion; state=$_.summary.state; parent_run_id=$_.summary.parentRunId; from=$_.frozen.from; to=$_.frozen.to; updated_at=$_.summary.updatedAt; verification=$_.runPayload.verification; empty_source_receipt=if ($_.summary.state -eq 'VERIFIED_EMPTY') { $_.runPayload } else { $null } } })
    }
}
$sourceNames = @('TusharePageService','TushareClient','ClientConfiguration','QuestDbProperties','StaticTargetIdentity','StockFactorSource','StockFactorMapper','DailyBasicSource','StockLimitSource','MoneyflowSource','EtfDailySource','EtfFactorSource','EtfPortfolioSource','StockFactorWritePort','DailyBasicWritePort','StockLimitWritePort','MoneyflowWritePort','StockLimitJobService','MoneyflowJobService','SyncJobRunner','MarketSentimentDailyCalculation','MarketSentimentDailyJobService','RegimeFeaturesMonitorDailyCalculation','RegimeFeaturesMonitorDailyJobService','NativeDailyWindowWritePort','MarketSentimentDailyWritePort','RegimeFeaturesMonitorDailyWritePort','NativeDailyWindowPublication')
$sourceFiles = @(Get-ChildItem -LiteralPath 'src/main/java' -Recurse -File | Where-Object { $_.Extension -eq '.java' -and $_.BaseName -in $sourceNames })
$sourceProof = @($sourceFiles | Sort-Object FullName | ForEach-Object { File-Proof $_.FullName })
$evidenceNames = @('goal-java-routes-ledger-readonly.json','java-sync-data-audit.json','java-sync-source-receipts-readonly.json','goal-etf-retained-receipt-audit.json','etf-margin-audit.json','market-sentiment-native-readback.json','market-sentiment-ledger.json','regime-native-java-audit.json','regime-monitor-ledger.json','main-strategy-readonly-acceptance.json','goal-current-questdb-observation.json','native-current-plan-v2-readonly.json','goal-final-build-receipt.json','goal-local-regime-plan-launch.json')
$evidenceProof = @($evidenceNames | ForEach-Object { File-Proof ($base + '/' + $_) })
$evidenceProof += File-Proof $build.log
$evidenceProof += File-Proof $launch.log
$marketSourceProof = File-Proof $market.publication.sourceEvidence
$regimeScope = $regime.publicationJournal.intent.scope | ConvertFrom-Json
$regimeSourceProof = File-Proof $regimeScope.sourceEvidence
if ($marketSourceProof.sha256 -ne $market.sourceEvidenceSha256 -or $regimeSourceProof.sha256 -ne $regime.sourceFileSha256) { throw 'Native pinned source evidence SHA changed' }
$receipt = [ordered]@{
    observed_at=[DateTimeOffset]::Now.ToString('o')
    audit_kind='independent final Java route closure, retained evidence and source code audit'
    read_only=$true; provider_requests=0; database_writes=0; production_jobs_started=0; tests_added_or_run=0; production_java_modified=$false
    scope=[ordered]@{ cutoff='2026-09-30'; source_trade_dates=@('2026-09-22','2026-09-23','2026-09-24','2026-09-28','2026-09-29','2026-09-30'); native_trade_dates=@('2026-09-21','2026-09-22','2026-09-23','2026-09-24','2026-09-28','2026-09-29','2026-09-30'); etf_portfolio_policy='User explicitly permits actual latest disclosure when no 0930 disclosure exists.' }
    conclusion=[ordered]@{ status='closed_with_upstream_and_consumer_limits'; java_route_confirmed=$true; required_additional_java_data_jobs=@(); approved_data_goal_has_no_remaining_required_java_job=$true; retrospective_point_in_time_certification=$false; trading_or_strategy_plan_published=$false }
    webflux_chain=[ordered]@{ path=@('existing dataset JobService/Source','injected TusharePageService','injected TushareClient.requestAsync/requestMono','existing SharedRequestBudget','injected singleton WebClient.post/exchangeToMono','shared Reactor Netty connection provider'); native='Pinned QuestDB reads and shared typed native window publisher; only necessary IPO proof used existing client. No Python provider bridge.'; source_refs=@('TusharePageService.java:17-31','TushareClient.java:24-61','ClientConfiguration.java:20-62','QuestDbProperties.java:51'); unrelated_or_second_http_client_found=$false; existing_credential_lease_preserved=$true }
    final_build=$build
    current_local_plan=[ordered]@{ launch_receipt=$launch; status=$plan.status; executed=$plan.executed; dataVerified=$plan.dataVerified; job_version=$plan.plan.request.definition.version; target_table_id=$plan.plan.targetBefore.tableId; target_rows=@($plan.plan.targetBefore.rows).Count; target_full_sha256=$plan.plan.targetBefore.fingerprint; matches_regime_publication=$true; evidence_role='Fresh current localhost plan proves final code can read the same published physical target; it is not substituted for historical sync launch evidence.' }
    local_target=[ordered]@{ host='127.0.0.1'; http_port=9000; pg_port=8812; explicit_override='-Dapp.questdb.host=127.0.0.1'; property_routes='QuestDbProperties drives PG connection and qwpConfig sender; StaticTargetIdentity binds JDBC target and live physical table identity.'; current_observation_time=$observation.observed_at; current_wal_rows=$observation.queries.wal.result.dataset; current_source_0930=$observation.queries.source0930.result.dataset }
    ledger=[ordered]@{ readonly_observed_at=$ledger.observedAtUtc; selected_runs=@($ledger.runs).Count; verified=$verified.Count; verified_empty=@($accepted | Where-Object { $_.summary.state -eq 'VERIFIED_EMPTY' }).Count; failed=@($ledger.runs | Where-Object { $_.summary.state -eq 'FAILED' }).Count; cancelled=@($ledger.runs | Where-Object { $_.summary.state -eq 'CANCELLED' }).Count; nonterminal_selected_runs=$notTerminal.Count; historical_failures=@($ledger.runs | Where-Object { $_.summary.state -in @('FAILED','CANCELLED') } | ForEach-Object { [ordered]@{ id=$_.summary.id; job=$_.summary.jobId; state=$_.summary.state; payload=$_.runPayload } }); failure_resolution='daily_basic and stk_limit FAILED originals are preserved, then accepted parent-linked serial resume. Original Incomplete category suppresses precise cause; lease contention is corroborated by concurrency/code/diagnostic receipt and successful serialized recovery, not proved by category alone. Cancelled market run superseded by a separately verified same-window run.' }
    dataset_routes=$routes
    native_publications=[ordered]@{
        market=[ordered]@{ run_id=$market.runId; passed=$market.passed; columns=$market.fieldCount; new_rows=$market.windowRows; formal_rows=$market.formalRows; source_cells_compared=$market.windowMatchedCells; outside_cells_compared=$market.outsideMatchedCells; publication=$market.publication; source_evidence=$marketSourceProof }
        regime=[ordered]@{ run_id=$regime.runId; passed=$regime.passed; columns=21; new_rows=$regime.windowRows; formal_rows=$regime.fullRows; source_cells_compared=$regime.sourceFieldComparisons; outside_cells_compared=$regime.outsideFieldComparisons; backup_cells_compared=$regime.backupFieldComparisons; publication=$regime.publicationJournal; source_evidence=$regimeSourceProof }
        final_code_version_boundary='Both actual native calculations ran jobVersion 1 and have passed full-field publication audits; final version 2 mode/context guard source compiles and read-only plans pass. No version 2 production run is asserted.'
        digest_boundary='Run aggregate fingerprint, per-slice source fingerprint, source file SHA, and complete target hash are distinct scopes and are checked at their matching scope.'
    }
    provenance=[ordered]@{ selected_java_source_verified_rows=[long](($verified | ForEach-Object { $_.runPayload.verification.expectedRows } | Measure-Object -Sum).Sum); meaning='Source records matched, not newly inserted row count.'; portfolio_0831='Historical 901265-row repair was Python; retained Java 0928-0930 source scan is verified empty. Historical rows are independently full-key/value checked, without relabeling them as Java writes.'; portfolio_historical_verification=$etf.etf_portfolio_0831_reused_full_key_value_verification }
    accepted_consumer_snapshot=[ordered]@{ observed_at=$acceptance.observed_at; status=$acceptance.status; failures=$acceptance.failures; latest=$acceptance.latest; backtest_days_checked=$acceptance.checked_days; target_disclosures=$acceptance.target_disclosures }
    counterexamples_and_limits=@(
        [ordered]@{ issue='589330.SH actual latest disclosure is 20260526, age127 at0930 and exceeds former120day rule'; handling='User latest-disclosure permission allows data goal closure; retain freshness fact, do not invent0930 holdings.'; additional_java_job_required=$false },
        [ordered]@{ issue='512770.SH same report has disjoint0721 and0831 disclosures (46.57+53.36=99.93); existing latest-ann consumer takes53.36'; handling='All actual retained rows match upstream. Consumer selection semantics remains a separate issue; repeat source sync does not repair it.'; additional_java_job_required=$false },
        [ordered]@{ issue='0930 margin_detail provider lacksSZ/BJ coverage'; handling='Sentiment partial_enhanced omits financing enhancement, regime optional margin changeNULL. Repeating unavailable provider scope cannot manufacture completeness.'; additional_java_job_required=$false },
        [ordered]@{ issue='001246.SZ IPO on0930 has daily/basic but exact native stk_factor source-empty'; handling='Exact Java listing and factor response evidence retained; no fabricatedfactor or falseallstockfactorcoverage claim.'; additional_java_job_required=$false },
        [ordered]@{ issue='0930 data cut-off versus Oct7 calculation/availability timestamps'; handling='Current-data acceptance only; this audit does not certify historical as-of replay availability.'; additional_java_job_required=$false }
    )
    final_source_file_sha256=$sourceProof
    evidence_file_sha256=$evidenceProof
}
$out = $base + '/goal-java-route-closure-audit.json'
$receipt | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath $out -Encoding UTF8
[pscustomobject]@{ output=(Resolve-Path -LiteralPath $out).Path; observed_at=$receipt.observed_at; status=$receipt.conclusion.status; required_additional_java_jobs=$receipt.conclusion.required_additional_java_data_jobs.Count; routes=@($receipt.dataset_routes).Count; verified=$receipt.ledger.verified; verified_empty=$receipt.ledger.verified_empty; current_local_plan_full_sha=$receipt.current_local_plan.target_full_sha256; source_files=@($sourceProof).Count; evidence_files=@($evidenceProof).Count } | ConvertTo-Json -Depth 5
