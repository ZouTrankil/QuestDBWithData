$ErrorActionPreference='Stop'
$taskLedger=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java-sync-ledger-readonly.json') -Raw | ConvertFrom-Json
$taskPhysical=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'questdb-readonly.json') -Raw | ConvertFrom-Json
$taskReceipts=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'java-sync-source-receipts-readonly.json') -Raw | ConvertFrom-Json
$taskView=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'view-rows-readonly.json') -Raw | ConvertFrom-Json
$taskDependencies=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'view-dependencies-readonly.json') -Raw | ConvertFrom-Json
$taskExtra=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'extra-coverage-readonly.json') -Raw | ConvertFrom-Json
$taskExpectedDates=@('20260922','20260923','20260924','20260928','20260929','20260930')
$taskTables=[ordered]@{}
foreach($taskProperty in $taskPhysical.tables.PSObject.Properties){
    $taskName=$taskProperty.Name; $taskStats=$taskProperty.Value
    $taskSources=@($taskReceipts.receipts | Where-Object jobId -eq ('data.'+$taskName))
    $taskPhysicalColumns=@($taskExtra.physicalSchemas.dataset | Where-Object { $_[0] -eq $taskName })
    $taskSchemaFields=@($taskPhysicalColumns | ForEach-Object { $_[1] })
    $taskSourceFields=@($taskSources[0].fields)
    $taskSchemaDifference=@(Compare-Object -ReferenceObject $taskSourceFields -DifferenceObject $taskSchemaFields)
    $taskDates=@()
    foreach($taskRow in $taskStats.rowsByDate.dataset){
        $taskDate=([DateTime]::Parse($taskRow[0])).ToString('yyyyMMdd')
        $taskSource=@($taskSources | Where-Object tradeDate -eq $taskDate)
        $taskNullCounts=[ordered]@{}
        for($taskIndex=3;$taskIndex -lt $taskStats.rowsByDate.columns.Count;$taskIndex++){
            $taskNullCounts[$taskStats.rowsByDate.columns[$taskIndex].name]=$taskRow[$taskIndex]
        }
        $taskPassed=$taskSource.Count -eq 1 -and $taskSource[0].passed -eq $true -and $taskSource[0].sourceRows -eq $taskRow[1] -and $taskRow[1] -eq $taskRow[2] -and @($taskNullCounts.Values | Where-Object {$_ -ne 0}).Count -eq 0
        $taskDates += [ordered]@{tradeDate=$taskDate;physicalRows=$taskRow[1];uniqueCodes=$taskRow[2];nullCounts=$taskNullCounts;sourceRows=$taskSource[0].sourceRows;runId=$taskSource[0].runId;sliceId=$taskSource[0].sliceId;passed=$taskPassed}
        if(-not $taskPassed){throw "Source/physical read-only audit failed: $taskName $taskDate"}
    }
    if(@(Compare-Object $taskExpectedDates @($taskDates.tradeDate)).Count -ne 0){throw "Trading date coverage differs: $taskName"}
    $taskUpsertKeys=@($taskPhysicalColumns | Where-Object {$_[9] -eq $true} | ForEach-Object {$_[1]})
    $taskKeysCorrect=@(Compare-Object @('ts_code','trade_date') $taskUpsertKeys).Count -eq 0
    $taskMetadata=@($taskPhysical.tablesMetadata.dataset | Where-Object { $_[0] -eq $taskName })[0]
    $taskWal=@($taskPhysical.walFrontiers.dataset | Where-Object { $_[0] -eq $taskName })[0]
    $taskRange=$taskStats.physicalRange.dataset[0]
    $taskPassed=$taskSchemaDifference.Count -eq 0 -and $taskKeysCorrect -and $taskStats.duplicateBusinessKeys.dataset[0][0] -eq 0 -and $taskStats.nullBusinessDates.dataset[0][0] -eq 0 -and $taskMetadata[3] -eq $true -and $taskMetadata[4] -eq $true -and $taskMetadata[5] -eq 'YEAR' -and $taskMetadata[6] -eq 'trade_date' -and $taskWal[1] -eq $false -and $taskWal[2] -eq $taskWal[3] -and $taskWal[4] -eq 0 -and ([DateTime]::Parse($taskRange[1])).ToString('yyyyMMdd') -eq '20260930'
    $taskTables[$taskName]=[ordered]@{passed=$taskPassed;latestDate=$taskRange[1];physicalRowsAllHistory=$taskRange[2];firstDate=$taskRange[0];windowRows=($taskDates.physicalRows | Measure-Object -Sum).Sum;sourceFieldCount=$taskSourceFields.Count;physicalFieldCount=$taskSchemaFields.Count;fieldNamesMatch=$taskSchemaDifference.Count -eq 0;physicalColumnTypes=@($taskPhysicalColumns | ForEach-Object {[ordered]@{name=$_[1];type=$_[2];designated=$_[8];upsertKey=$_[9]}});criticalFields=$taskStats.criticalFields;duplicateKeyGroups=$taskStats.duplicateBusinessKeys.dataset[0][0];nullBusinessDates=$taskStats.nullBusinessDates.dataset[0][0];metadata=$taskMetadata;walFrontier=$taskWal;dates=$taskDates}
    if(-not $taskPassed){throw "Schema/range/WAL audit failed: $taskName"}
}
$taskLogsByRun=@{
    'stk-factor-eba6ccdf-3b72-418e-b584-64b074e3e468'='stk-factor-0928-0930.log'
    'stk-factor-068f2f38-d20d-4b47-b7f1-5fd134d22518'='stk-factor-0922-0924.log'
    'daily-basic-71780938-93af-4629-8203-66f52163a37d'='daily-basic-0922-0930.log'
    'daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691'='daily-basic-resume.log'
    'stk-limit-5e26ff2f-3f99-4bf3-b063-dad034b7755d'='stk-limit-0922-0930.log'
    'stk-limit-ecde9425-a828-4c4e-a419-51995e2535df'='stk-limit-resume.log'
    'moneyflow-ae452f65-7f01-401c-8b6c-d666b3a9906b'='moneyflow-0928-0930.log'
    'moneyflow-464eefa5-18b7-49f5-bbbd-d1ce9eb98a3a'='moneyflow-0922-0924.log'
    'etf-factor-d6e3e5ff-198c-4cc9-9304-e6dbf50ce5f7'='etf-factor-0922-0930.log'
}
$taskRuns=@()
$taskLogRoot=Join-Path (Resolve-Path '.').Path 'var/main-strategy-java-refresh/20261007'
foreach($taskRun in $taskLedger.runs){
    $taskLogPath=Join-Path $taskLogRoot $taskLogsByRun[$taskRun.summary.id]
    $taskLogText=Get-Content -LiteralPath $taskLogPath -Raw
    $taskLogResult=[regex]::Match($taskLogText,'(?s)\{\s*"runId"\s*:.*?\r?\n\}').Value | ConvertFrom-Json
    if($taskLogResult.runId -ne $taskRun.summary.id -or $taskLogResult.state -ne $taskRun.summary.state){throw 'Log and authoritative run differ'}
    $taskSliceStates=@($taskRun.entries | Where-Object {$_.entry.kind -eq 'SLICE'} | ForEach-Object {$_.entry.state})
    $taskVerification=$taskRun.runPayload.verification
    if($taskRun.summary.state -eq 'VERIFIED' -and (-not $taskVerification.passed -or $taskVerification.missingKeys -ne 0 -or $taskVerification.duplicateKeys -ne 0 -or $taskVerification.mismatchedRows -ne 0 -or $taskVerification.expectedRows -ne $taskVerification.matchedRows -or @($taskSliceStates | Where-Object {$_ -ne 'VERIFIED'}).Count -ne 0)){throw 'Ledger completion verification failed'}
    $taskResume=$null
    if($null -ne $taskRun.summary.parentRunId){
        $taskPrior=@($taskLedger.runs | Where-Object {$_.summary.id -eq $taskRun.summary.parentRunId})[0]
        $taskResume=[ordered]@{parentRunId=$taskPrior.summary.id;parentState=$taskPrior.summary.state;sameTarget=$taskPrior.summary.targetId -eq $taskRun.summary.targetId;sameFrom=$taskPrior.frozen.from -eq $taskRun.frozen.from;sameTo=$taskPrior.frozen.to -eq $taskRun.frozen.to;sameDates=$taskPrior.frozen.parameters.trade_dates -eq $taskRun.frozen.parameters.trade_dates;parentHasNoFetchedSlices=@($taskPrior.entries | Where-Object {$_.entry.kind -eq 'SLICE'}).Count -eq 0}
    }
    $taskRuns += [ordered]@{summary=$taskRun.summary;mode=$taskRun.frozen.mode;from=$taskRun.frozen.from;to=$taskRun.frozen.to;parameters=$taskRun.frozen.parameters;verification=$taskVerification;sliceStates=$taskSliceStates;sourceRows=$taskLogResult.sourceRows;verifiedRows=$taskLogResult.verifiedRows;errorCode=$taskLogResult.errorCode;logPath=$taskLogPath;logSha256=(Get-FileHash -LiteralPath $taskLogPath -Algorithm SHA256).Hash.ToLower();resume=$taskResume}
}
$taskMarketLedger=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'market-sentiment-ledger.json') -Raw | ConvertFrom-Json
$taskMarketPublication=$taskMarketLedger.publications[0]
$taskMarketScope=$taskMarketPublication.intent_json_parsed.scope | ConvertFrom-Json
$taskMarketSource=Get-Content -LiteralPath $taskMarketScope.sourceEvidence -Raw | ConvertFrom-Json
$taskMarketActualSha=(Get-FileHash -LiteralPath $taskMarketScope.sourceEvidence -Algorithm SHA256).Hash.ToLower()
if($taskMarketActualSha -ne $taskMarketScope.sourceEvidenceSha256){throw 'Published market source evidence SHA differs'}
$taskProvenance=[ordered]@{producer='Java owner jobs using existing TusharePageService/TushareClient/Spring WebClient';priorPythonWritesExist=$true;priorPythonArtifacts='D:/work/fund_2/back-monitor/artifacts/main-strategy-refresh/20261007';newSourceVerification='30 new Java source receipts and seven VERIFIED Java run IDs, full key/value readback in durable ledger';rowCountMeaning='sourceRows/verifiedRows count submitted and verified source rows, not new inserts; existing DEDUP keys can be refreshed';auditProviderRequests=0;auditDatabaseWrites=0;auditTestsRun=0}
$taskReport=[ordered]@{observedAtUtc=[DateTime]::UtcNow.ToString('o');conclusion='PASS for the five audited source tables and the bounded v_backtest_daily window';targetDate='2026-09-30';tradingDates=$taskExpectedDates;tables=$taskTables;runs=$taskRuns;verifiedRunCount=@($taskRuns | Where-Object {$_.summary.state -eq 'VERIFIED'}).Count;retainedFailedRunCount=@($taskRuns | Where-Object {$_.summary.state -eq 'FAILED'}).Count;verifiedSourceRows=($taskTables.Values.windowRows | Measure-Object -Sum).Sum;sourceReceipts=$taskReceipts.receipts;view=[ordered]@{ddl=$taskDependencies.'0'.dataset[0][0];rowsByDate=$taskView;dependencies=@('stk_factor','stk_limit','stk_suspend','stk_st_daily');stkSuspendLatest=$taskDependencies.'1';stkStLatest=$taskDependencies.'2';referenceRows=$taskExtra.referenceDates;coverage='Six dates have rows==unique(ts_code), close/up_limit/down_limit null=0; max recent view date is 2026-09-30'};etfTargets=$taskExtra.targetEtfs;factorUniverseDifference=[ordered]@{physicalDailyOnly=$taskExtra.ipoDaily;physicalDailyBasicOnly=$taskDependencies.'3';javaListingDayEvidence=$taskMarketSource.dailyOnlyMissingFactor;sourceEvidence=$taskMarketScope.sourceEvidence;sourceEvidenceSha256=$taskMarketActualSha;publicationState=$taskMarketPublication.state;decision='001246.SZ is in daily/daily_basic on listing day 20260930; captured Java exact factor probe returned zero, so no fabricated factor row'};calendar=$taskDependencies.'4';credentialLeaseRecovery=[ordered]@{dailyBasicDiagnosisArtifact=(Join-Path $taskLogRoot 'daily-basic-source-lease-audit.json');cause='Original errors retained Incomplete; zero source rows and overlap with an existing credential lease owner corroborate process lifetime lease contention, rather than a verified empty provider response';recovery='Serial Java resume runs preserved parent IDs, frozen windows and targets, and completed VERIFIED';proofLimit='Original suppressed Incomplete alone does not retain the exact credential lease exception text'};provenance=$taskProvenance;limitations=@('This independent audit reran read-only row/key/null/schema/source-receipt checks; complete values are evidenced by native owner readback receipts, not by re-fetching provider data.','Main-strategy market sentiment, ETF portfolio announcement freshness, margin and Level2 acceptance are separate coordinator audits; five source tables alone do not prove every strategy input.')}
$taskReport | ConvertTo-Json -Depth 25 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'java-sync-data-audit.json') -Encoding utf8
$taskLines=[Collections.Generic.List[string]]::new()
$taskLines.Add('# Java 主策略源数据独立验收（截至 2026-09-30）')
$taskLines.Add('')
$taskLines.Add('结论：本次范围内五张正式源表通过；最新均为 2026-09-30。7 个 Java run VERIFIED，30 份按日源凭据 SHA256/日期/代码/唯一键校验通过，146,823 行来源记录与当前正式表按日行数一致。审计仅使用本地 SQLite 和 QuestDB 9000 只读 SQL，没有调用 provider、写库或运行测试。')
$taskLines.Add('')
$taskLines.Add('## 六个交易日的正式表行数')
$taskLines.Add('')
$taskLines.Add('| 日期 | stk_factor | daily_basic | stk_limit | moneyflow | etf_factor | v_backtest_daily |')
$taskLines.Add('|---|---:|---:|---:|---:|---:|---:|')
foreach($taskDate in $taskExpectedDates){
    $taskValues=@($taskDate)
    foreach($taskTable in $taskTables.Keys){$taskValues += (@($taskTables[$taskTable].dates | Where-Object tradeDate -eq $taskDate)[0].physicalRows)}
    $taskValues += (@($taskView.dataset | Where-Object {([DateTime]::Parse($_[0])).ToString('yyyyMMdd') -eq $taskDate})[0][1])
    $taskLines.Add('| '+($taskValues -join ' | ')+' |')
}
$taskLines.Add('')
$taskLines.Add('上述五表各日期的行数等于唯一代码数；窗口内 `(ts_code,trade_date)` 重复组为 0，业务代码空值为 0；全表 trade_date 空值为 0。关键空值检查：factor/ETF 的 close、pre_close、amount、vol；daily_basic 的 close、turnover_rate、circ_mv、total_mv；stk_limit 的 up_limit、down_limit；moneyflow 的八个买卖量和 net_mf_vol、net_mf_amount；结果均为 0。未将全部可选技术指标要求为非空。')
$taskLines.Add('')
$taskLines.Add('物理列数依次为 35 / 18 / 4 / 20 / 89，列名与 Java 源契约一致。所有表均 YEAR 分区、WAL、DEDUP，指定时间为 trade_date，upsert keys 为 ts_code+trade_date；审计时均无 WAL suspension，writerTxn=sequencerTxn，bufferedTxnSize=0。stk_factor 使用正式旧 API stk_factor 的 35 字段源契约，不是 stk_factor_pro 的 261 字段。')
$taskLines.Add('')
$taskLines.Add('## Java VERIFIED 凭据')
$taskLines.Add('')
$taskLines.Add('| run ID | 范围 | 已逐键全字段读回行数 |')
$taskLines.Add('|---|---|---:|')
foreach($taskRun in ($taskRuns | Where-Object {$_.summary.state -eq 'VERIFIED'})){$taskLines.Add('| `'+$taskRun.summary.id+'` | '+$taskRun.from+'～'+$taskRun.to+' | '+$taskRun.verifiedRows+' |')}
$taskLines.Add('')
$taskLines.Add('各 VERIFIED run 和每个日 slice 的 ledger verification 均 passed=true、expectedRows=matchedRows=actualRows，missingKeys/duplicateKeys/mismatchedRows 均为 0；终态与对应日志一致。JSON 保留原始 sourceFingerprint、源文件 SHA256、slice ID、table ID/WAL frontier、全物理类型及日志 SHA256。daily_basic 的 sourceFingerprint 按正式 canonical body 重新计算，另保留 source 文件和 raw response 文件 SHA256。')
$taskLines.Add('')
$taskLines.Add('## 初始失败与恢复')
$taskLines.Add('')
$taskLines.Add('- daily_basic 原 run `daily-basic-71780938-93af-4629-8203-66f52163a37d` 仍保留 FAILED / Incomplete / sourceRows=0；其关联 resume run `daily-basic-5bdc9a21-9fbc-4dc2-a143-4528d5d2b691` 为 VERIFIED。')
$taskLines.Add('- stk_limit 原 run `stk-limit-5e26ff2f-3f99-4bf3-b063-dad034b7755d` 仍保留 FAILED / Incomplete / sourceRows=0；其关联 resume run `stk-limit-ecde9425-a828-4c4e-a419-51995e2535df` 为 VERIFIED。')
$taskLines.Add('- 两次恢复均保留 parentRunId，相同正式 target、from/to 和明确 trade_dates。旧错误没有改成成功。原日志抑制了底层异常，不能单凭 Incomplete 证明确切锁异常；零源响应、当时已有凭证占用进程、现有 SharedRequestBudget 整进程独占锁路径和串行后成功共同支持凭证 lease 冲突诊断。')
$taskLines.Add('')
$taskLines.Add('## 回测视图、IPO 差集与 ETF')
$taskLines.Add('')
$taskLines.Add('数据库实际 SHOW CREATE VIEW 显示 v_backtest_daily 依赖 stk_factor、stk_limit、stk_suspend、stk_st_daily；四者 latest 均为 2026-09-30。视图以因子为基础并补停牌记录，无单独同步任务；六日行数=唯一代码数，close/up_limit/down_limit 空值为 0。停牌及 ST 表六日均有记录，完整 DDL 与按日行数保存在 JSON。')
$taskLines.Add('')
$taskLines.Add('0930 daily/daily_basic 比 stk_factor 多的唯一代码是 001246.SZ。已发布 Java market source 的记录证明 listing_date=20260930，按精确代码+日期调用 stk_factor 返回 0 行；源文件 SHA256 与 VERIFIED publication scope 一致。按来源真实缺失接受，不补造 IPO 因子。stk_limit 与 moneyflow 的源范围还包含其它记录，因此不能强制它们行数等于 factor。')
$taskLines.Add('')
$taskLines.Add('512770.SH、589330.SH 六日 ETF 因子记录全部存在；0930 close 分别为 2.374 / 0.858。fund_factor_pro 的正式 89 字段源也包含 OF 后缀记录，审计按该来源命名保留；不能把该源的全部记录均当作 SH/SZ 上市 ETF。')
$taskLines.Add('')
$taskLines.Add('本地 SSE calendar 显示 9/25、26、27 休市，本窗口应验收六个交易日。')
$taskLines.Add('')
$taskLines.Add('## 写入来源与验收边界')
$taskLines.Add('')
$taskLines.Add('正式库已有此前 Python 同步产生的记录。本轮 Java 的 WebClient 源响应、原生 owner run/slice 和正式表完整键/全字段读回是独立新凭据；sourceRows/verifiedRows 统计提交并读回的源记录，不代表首次新增插入数，也不将全部历史物理行归属为 Java 新生成。此前 Python artifacts 位于 D:/work/fund_2/back-monitor/artifacts/main-strategy-refresh/20261007，本轮 Java artifacts 单独保存。')
$taskLines.Add('')
$taskLines.Add('本报告覆盖五张源表及回测视图窗口；市场情绪发布、持仓披露时效、margin、Level2 全量验收由协调任务分别核对。本次未重新请求 provider，也未重复全量字段 SQL 对比；全字段正确性依据正式 Java owner 已持久化的逐键完整值读回回执，独立复核当前库行数/键/关键空值/结构与保留源凭据。')
$taskLines.Add('')
$taskLines.Add('## 文件')
$taskLines.Add('')
$taskLines.Add('- java-sync-data-audit.json：本报告结构化明细。')
$taskLines.Add('- java-sync-ledger-readonly.json：只读 ledger run/entry/event 原始证据。')
$taskLines.Add('- java-sync-source-receipts-readonly.json：30 份保留源凭据 SHA256、日期/代码/键检查。')
$taskLines.Add('- questdb-readonly.json、view-rows-readonly.json、view-dependencies-readonly.json、extra-coverage-readonly.json：原始只读 SQL 结果。')
$taskLines.Add('- market-sentiment-ledger.json：IPO 来源证明关联的正式发布账本原始证据。')
[IO.File]::WriteAllLines((Join-Path $PSScriptRoot 'java-sync-data-audit.md'),$taskLines,[Text.UTF8Encoding]::new($false))
Write-Output ('Java source audit report saved: '+(Join-Path $PSScriptRoot 'java-sync-data-audit.md'))
