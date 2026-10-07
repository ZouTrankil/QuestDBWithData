$ErrorActionPreference = 'Stop'
$taskAuditRoot = Join-Path $PSScriptRoot 'questdb-readonly.json'
function Read-QuestDbAuditSql([string] $TaskSql) {
    $taskUrl = 'http://127.0.0.1:9000/exec?query=' + [Uri]::EscapeDataString($TaskSql)
    Invoke-RestMethod -Uri $taskUrl -TimeoutSec 60
}
$taskWindow = "trade_date >= '2026-09-22T00:00:00.000000Z' AND trade_date < '2026-10-01T00:00:00.000000Z'"
$taskCritical = [ordered]@{
    stk_factor = @('close','pre_close','amount','vol')
    daily_basic = @('close','turnover_rate','circ_mv','total_mv')
    stk_limit = @('up_limit','down_limit')
    moneyflow = @('buy_sm_vol','sell_sm_vol','buy_md_vol','sell_md_vol','buy_lg_vol','sell_lg_vol','buy_elg_vol','sell_elg_vol','net_mf_vol','net_mf_amount')
    etf_factor = @('close','pre_close','amount','vol')
}
$taskEvidence = [ordered]@{
    observedAtUtc = [DateTime]::UtcNow.ToString('o')
    endpoint = 'http://127.0.0.1:9000/exec'
    readOnly = $true
    providerRequests = 0
    databaseWrites = 0
    expectedTradingDates = @('2026-09-22','2026-09-23','2026-09-24','2026-09-28','2026-09-29','2026-09-30')
    tables = [ordered]@{}
}
foreach ($taskTable in $taskCritical.Keys) {
    $taskColumns = @("sum(CASE WHEN ts_code IS NULL OR ts_code = '' THEN 1 ELSE 0 END) AS null_business_code")
    foreach ($taskColumn in $taskCritical[$taskTable]) {
        $taskColumns += "sum(CASE WHEN $taskColumn IS NULL THEN 1 ELSE 0 END) AS null_$taskColumn"
    }
    $taskStats = Read-QuestDbAuditSql "SELECT trade_date,count() AS rows,count_distinct(ts_code) AS unique_codes,$($taskColumns -join ',') FROM $taskTable WHERE $taskWindow GROUP BY trade_date ORDER BY trade_date"
    $taskDuplicates = Read-QuestDbAuditSql "SELECT count() AS duplicate_key_groups FROM (SELECT ts_code,trade_date,count() AS key_rows FROM $taskTable WHERE $taskWindow GROUP BY ts_code,trade_date) WHERE key_rows > 1"
    $taskLatest = Read-QuestDbAuditSql "SELECT min(trade_date) AS first_date,max(trade_date) AS latest_date,count() AS physical_rows FROM $taskTable"
    $taskNullDate = Read-QuestDbAuditSql "SELECT count() AS null_business_dates FROM $taskTable WHERE trade_date IS NULL"
    $taskEvidence.tables[$taskTable] = [ordered]@{
        criticalFields = $taskCritical[$taskTable]
        rowsByDate = $taskStats
        duplicateBusinessKeys = $taskDuplicates
        physicalRange = $taskLatest
        nullBusinessDates = $taskNullDate
    }
    $taskEvidence | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $taskAuditRoot -Encoding utf8
    Write-Output "Read-only QuestDB audit collected: $taskTable"
}
$taskEvidence.tablesMetadata = Read-QuestDbAuditSql "SELECT table_name,id,directoryName,walEnabled,dedup,partitionBy,designatedTimestamp FROM tables() WHERE table_name IN ('stk_factor','daily_basic','stk_limit','moneyflow','etf_factor','v_backtest_daily')"
$taskEvidence.walFrontiers = Read-QuestDbAuditSql "SELECT name,suspended,writerTxn,sequencerTxn,bufferedTxnSize FROM wal_tables() WHERE name IN ('stk_factor','daily_basic','stk_limit','moneyflow','etf_factor')"
$taskEvidence | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $taskAuditRoot -Encoding utf8
Write-Output 'Read-only source-table collection completed.'
