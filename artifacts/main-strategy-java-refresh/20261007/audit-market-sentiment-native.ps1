param(
    [string]$Workspace = 'C:\Users\zouqiang\IdeaProjects\QuestDBWithData',
    [string]$RunId = 'market-sentiment-466c4316-9c83-489f-8c6b-11a84ebe936c'
)
$ErrorActionPreference = 'Stop'
$evidence = Join-Path $Workspace ('var\main-strategy-java-refresh\20261007\sync-evidence\' + $RunId)
$output = Join-Path $Workspace 'artifacts\main-strategy-java-refresh\20261007'
$sourceRaw = Get-Content -LiteralPath (Join-Path $evidence 'source.json') -Raw
$source = [System.Text.Json.JsonDocument]::Parse([string]$sourceRaw)
$publication = Get-Content -LiteralPath (Join-Path $evidence 'publication.json') -Raw | ConvertFrom-Json
$port = Get-Content -LiteralPath (Join-Path $Workspace 'src\main\java\com\zoutrankil\data\repository\MarketSentimentDailyWritePort.java') -Raw
$list = [regex]::Match($port, 'COLUMNS=List\.of\((.*?)\);', [System.Text.RegularExpressions.RegexOptions]::Singleline).Groups[1].Value
$columns = @([regex]::Matches($list, '"([a-z0-9_]+)"') | ForEach-Object { $_.Groups[1].Value })
if ($columns.Count -ne 53) { throw 'Expected 53 native columns' }
$record = Get-Content -LiteralPath (Join-Path $Workspace 'src\main\java\com\zoutrankil\data\domain\table\MarketSentimentDailyRow.java') -Raw
$recordNames = @([regex]::Matches($record, '(?:Instant|String|Double|Boolean)\s+([A-Za-z0-9]+)\s*[,)]') | ForEach-Object { $_.Groups[1].Value })
if ($recordNames.Count -ne 53) { throw 'Expected 53 typed record components' }
$sqlEvidence = [System.Collections.Generic.List[object]]::new()
function Read-QuestDb([string]$Sql) {
    if ($Sql -notmatch '^SELECT ') { throw 'This audit permits SELECT only' }
    $url = 'http://127.0.0.1:9000/exec?query=' + [Uri]::EscapeDataString($Sql)
    $raw = [string](Invoke-WebRequest -TimeoutSec 60 -Uri $url).Content
    $doc = [System.Text.Json.JsonDocument]::Parse($raw)
    if ($doc.RootElement.TryGetProperty('error', [ref]([System.Text.Json.JsonElement]::new()))) { throw $raw }
    $sqlEvidence.Add([pscustomobject]@{ sql = $Sql; result = ($raw | ConvertFrom-Json) })
    return $doc
}
function Micros-To-Instant([long]$Micros) {
    $instant = [DateTimeOffset]::UnixEpoch.AddTicks($Micros * [long]10)
    $text = $instant.ToString("yyyy-MM-dd'T'HH:mm:ss", [Globalization.CultureInfo]::InvariantCulture)
    $fraction = $Micros % [long]1000000
    if ($fraction -ne 0) {
        if ($fraction % 1000 -eq 0) { $text += '.' + ([long]($fraction / 1000)).ToString('D3') }
        else { $text += '.' + $fraction.ToString('D6') }
    }
    return $text + 'Z'
}
function Instant-To-Micros([string]$Value) {
    $instant = [DateTimeOffset]::Parse($Value, [Globalization.CultureInfo]::InvariantCulture)
    return [long](($instant.UtcTicks - [DateTimeOffset]::UnixEpoch.UtcTicks) / 10)
}
function Digest([string]$Text) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}
function Source-Rows {
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($row in $source.RootElement.GetProperty('rows').EnumerateArray()) {
        $props = @($row.EnumerateObject())
        if ($props.Count -ne 53) { throw 'Source row must contain 53 typed fields' }
        $values = [object[]]::new(53)
        $tokens = [string[]]::new(53)
        for ($i=0; $i -lt 53; $i++) {
            if ($props[$i].Name -cne $recordNames[$i]) { throw ('Source record order/name mismatch: ' + $props[$i].Name) }
            $el = $props[$i].Value
            $tokens[$i] = $el.GetRawText()
            if ($el.ValueKind -eq [System.Text.Json.JsonValueKind]::Null) { $values[$i] = $null }
            elseif ($columns[$i] -in @('trade_date','updated_at')) { $values[$i] = Instant-To-Micros $el.GetString() }
            elseif ($el.ValueKind -eq [System.Text.Json.JsonValueKind]::Number) { $values[$i] = $el.GetDouble() }
            elseif ($el.ValueKind -in @([System.Text.Json.JsonValueKind]::True,[System.Text.Json.JsonValueKind]::False)) { $values[$i] = $el.GetBoolean() }
            else { $values[$i] = $el.GetString() }
        }
        $result.Add([pscustomobject]@{values=$values;tokens=$tokens})
    }
    return ,$result.ToArray()
}
function Live-Rows([string]$Table) {
    if ($Table -notmatch '^(market_sentiment_daily|java_d121_market_sentiment_daily_backup_[a-z0-9]+)$') { throw 'Unexpected audit target' }
    $projection = ($columns | ForEach-Object { if ($_ -in @('trade_date','updated_at')) { 'cast("' + $_ + '" AS long) AS "' + $_ + '"' } else { '"' + $_ + '"' } }) -join ','
    $doc = Read-QuestDb ('SELECT ' + $projection + ' FROM "' + $Table + '" ORDER BY trade_date LIMIT 10001')
    $result = [System.Collections.Generic.List[object]]::new()
    foreach ($row in $doc.RootElement.GetProperty('dataset').EnumerateArray()) {
        if ($row.GetArrayLength() -ne 53) { throw 'Live row must contain 53 fields' }
        $values = [object[]]::new(53); $tokens = [string[]]::new(53)
        for ($i=0; $i -lt 53; $i++) {
            $el = $row[$i]; $tokens[$i] = $el.GetRawText()
            if ($el.ValueKind -eq [System.Text.Json.JsonValueKind]::Null) { $values[$i] = $null }
            elseif ($columns[$i] -in @('trade_date','updated_at')) { $values[$i] = $el.GetInt64(); $tokens[$i] = '"' + (Micros-To-Instant $values[$i]) + '"' }
            elseif ($el.ValueKind -eq [System.Text.Json.JsonValueKind]::Number) { $values[$i] = $el.GetDouble() }
            elseif ($el.ValueKind -in @([System.Text.Json.JsonValueKind]::True,[System.Text.Json.JsonValueKind]::False)) { $values[$i] = $el.GetBoolean() }
            else { $values[$i] = $el.GetString() }
        }
        $result.Add([pscustomobject]@{values=$values;tokens=$tokens})
    }
    return ,$result.ToArray()
}
function Canonical-Digest([object[]]$Rows) {
    $builder = [Text.StringBuilder]::new()
    foreach ($row in $Rows) {
        $parts = for ($i=0; $i -lt 53; $i++) { '"' + $columns[$i] + '":' + $row.tokens[$i] }
        [void]$builder.Append('{'+($parts -join ',')+"}`n")
    }
    return Digest $builder.ToString()
}
function Binary-Digest([object[]]$Rows) {
    $builder = [Text.StringBuilder]::new()
    foreach ($row in $Rows) {
        for ($i=0; $i -lt 53; $i++) {
            $value = $row.values[$i]
            $token = if ($null -eq $value) { 'null' }
                elseif ($columns[$i] -in @('trade_date','updated_at')) { 'epoch-micros:' + ([long]$value).ToString([Globalization.CultureInfo]::InvariantCulture) }
                elseif ($value -is [double]) { 'ieee754:' + ([BitConverter]::DoubleToInt64Bits($value)).ToString('X16') }
                elseif ($value -is [bool]) { 'boolean:' + $value.ToString().ToLowerInvariant() }
                else { 'string:' + $value }
            [void]$builder.Append($columns[$i]+'='+$token+"`n")
        }
        [void]$builder.Append("`n")
    }
    return Digest $builder.ToString()
}
function Compare-Rows([object[]]$Expected, [object[]]$Actual) {
    $diffs = [System.Collections.Generic.List[object]]::new()
    if ($Expected.Count -ne $Actual.Count) { $diffs.Add([pscustomobject]@{reason='row_count';expected=$Expected.Count;actual=$Actual.Count}) }
    for ($r=0; $r -lt [Math]::Min($Expected.Count,$Actual.Count); $r++) {
        for ($i=0; $i -lt 53; $i++) {
            $a=$Expected[$r].values[$i]; $b=$Actual[$r].values[$i]
            $same = if ($null -eq $a -or $null -eq $b) { $null -eq $a -and $null -eq $b }
                elseif ($a -is [double] -and $b -is [double]) { [BitConverter]::DoubleToInt64Bits($a) -eq [BitConverter]::DoubleToInt64Bits($b) }
                else { $a.GetType() -eq $b.GetType() -and $a -ceq $b }
            if (-not $same) { $diffs.Add([pscustomobject]@{row=$r;column=$columns[$i];expected=$a;actual=$b}) }
        }
    }
    return ,$diffs.ToArray()
}
$formal = Live-Rows 'market_sentiment_daily'
$backup = Live-Rows $publication.backup
$requested = Source-Rows
$start = Instant-To-Micros ($source.RootElement.GetProperty('from').GetString() + 'T00:00:00Z')
$end = Instant-To-Micros ($source.RootElement.GetProperty('to').GetString() + 'T00:00:00Z')
$window = @($formal | Where-Object { $_.values[0] -ge $start -and $_.values[0] -le $end })
$outside = @($formal | Where-Object { $_.values[0] -lt $start -or $_.values[0] -gt $end })
$originalOutside = @($backup | Where-Object { $_.values[0] -lt $start -or $_.values[0] -gt $end })
$expectedAll = @(@($originalOutside)+@($requested) | Sort-Object { $_.values[0] })
$diffs = Compare-Rows $requested $window
$outsideDiffs = Compare-Rows $originalOutside $outside
$schema = Read-QuestDb "SELECT `"column`",type,designated,upsertKey FROM table_columns('market_sentiment_daily')"
$metadata = Read-QuestDb "SELECT id,table_name,designatedTimestamp,partitionBy,walEnabled,dedup,directoryName FROM tables() WHERE table_name='market_sentiment_daily'"
$physical = Read-QuestDb ("SELECT id,table_name,designatedTimestamp,partitionBy,walEnabled,dedup,directoryName FROM tables() WHERE table_name IN ('market_sentiment_daily','" + $publication.backup + "')")
$wal = Read-QuestDb "SELECT * FROM wal_tables() WHERE name='market_sentiment_daily'"
$freshness = Read-QuestDb "SELECT max(trade_date) AS latest_stk_factor FROM stk_factor"
$dates = Read-QuestDb "SELECT trade_date,count() AS n FROM daily WHERE trade_date >= '2026-09-21' AND trade_date < '2026-10-01' ORDER BY trade_date"
$duplicates = Read-QuestDb "SELECT * FROM (SELECT trade_date,count() AS n FROM market_sentiment_daily GROUP BY trade_date) WHERE n>1"
$margin = Read-QuestDb "SELECT trade_date,count() AS n,sum(CASE WHEN ts_code LIKE '%.SH' THEN 1 ELSE 0 END) sh,sum(CASE WHEN ts_code LIKE '%.SZ' THEN 1 ELSE 0 END) sz,sum(CASE WHEN ts_code LIKE '%.BJ' THEN 1 ELSE 0 END) bj FROM margin_detail WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01' GROUP BY trade_date ORDER BY trade_date"
$dailyOnly = Read-QuestDb "SELECT d.trade_date,d.ts_code FROM (SELECT trade_date,ts_code FROM daily WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01') d LEFT JOIN (SELECT trade_date,ts_code FROM stk_factor WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01') sf ON d.trade_date=sf.trade_date AND d.ts_code=sf.ts_code WHERE sf.ts_code IS NULL ORDER BY d.trade_date,d.ts_code"
$gaps = Read-QuestDb "SELECT sf.trade_date,count() AS factor_rows,sum(CASE WHEN db.ts_code IS NULL THEN 1 ELSE 0 END) missing_basic,sum(CASE WHEN sl.ts_code IS NULL THEN 1 ELSE 0 END) missing_limit FROM (SELECT trade_date,ts_code FROM stk_factor WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01') sf LEFT JOIN (SELECT trade_date,ts_code FROM daily_basic WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01') db ON sf.ts_code=db.ts_code AND sf.trade_date=db.trade_date LEFT JOIN (SELECT trade_date,ts_code FROM stk_limit WHERE trade_date>='2026-09-21' AND trade_date<'2026-10-01') sl ON sf.ts_code=sl.ts_code AND sf.trade_date=sl.trade_date GROUP BY sf.trade_date ORDER BY sf.trade_date"
$nullFinite = [System.Collections.Generic.List[object]]::new()
foreach ($row in $formal) { for($i=0;$i -lt 53;$i++) { if($row.values[$i] -is [double] -and -not [double]::IsFinite($row.values[$i])) { $nullFinite.Add([pscustomobject]@{column=$columns[$i];tradeMicros=$row.values[0]}) } } }
$scoreChecks = [System.Collections.Generic.List[object]]::new()
foreach ($row in $window) {
    $map=@{}; for($i=0;$i -lt 53;$i++) { $map[$columns[$i]]=$row.values[$i] }
    $coreColumns=@('heat_score','breadth_score','limit_score','profit_effect_score','structure_score','divergence_score')
    $core=($coreColumns | ForEach-Object {$map[$_]} | Measure-Object -Average).Average
    $enhanced=@($core); foreach($c in @('leverage_score','moneyflow_score')){ if($null -ne $map[$c]){$enhanced+= $map[$c]} }
    $expectedScore=($enhanced | Measure-Object -Average).Average
    $scoreChecks.Add([pscustomobject]@{date=(Micros-To-Instant $map.trade_date);score=$map.sentiment_score;state=$map.sentiment_state;quality=$map.data_quality_flag;core=$map.sentiment_score_core;coreResidual=[Math]::Abs($core-$map.sentiment_score_core);scoreResidual=[Math]::Abs($expectedScore-$map.sentiment_score);leverageScore=$map.leverage_score;moneyflowScore=$map.moneyflow_score;nullMarginInputs=($null -eq $map.margin_buy_sell_ratio -and $null -eq $map.margin_buy_amount_ratio -and $null -eq $map.margin_balance_change_5d);month=$map.month;version=$map.model_version})
}
$schemaRows = @($schema.RootElement.GetProperty('dataset').EnumerateArray())
$schemaErrors = [System.Collections.Generic.List[string]]::new()
if ($schemaRows.Count -ne 53) { $schemaErrors.Add('column_count') }
for($i=0;$i -lt [Math]::Min(53,$schemaRows.Count);$i++) {
    $expectedType = if($i -in @(0,52)){'TIMESTAMP'}elseif($i -in @(1,5,50,51)){'SYMBOL'}elseif($i -ge 42 -and $i -le 49){'BOOLEAN'}else{'DOUBLE'}
    if($schemaRows[$i][0].GetString() -cne $columns[$i] -or $schemaRows[$i][1].GetString() -cne $expectedType -or $schemaRows[$i][2].GetBoolean() -ne ($i -eq 0) -or $schemaRows[$i][3].GetBoolean()) { $schemaErrors.Add($columns[$i]) }
}
$expectedDates = @($dates.RootElement.GetProperty('dataset').EnumerateArray() | ForEach-Object { Instant-To-Micros $_[0].GetString() })
$actualDates = @($window | ForEach-Object { $_.values[0] })
$dateDifferences = @(Compare-Object $expectedDates $actualDates)
$ledger = Get-Content -LiteralPath (Join-Path $output 'market-sentiment-ledger.json') -Raw | ConvertFrom-Json
$intent = $ledger.publications[0].intent_json_parsed
$intentScope = $intent.scope | ConvertFrom-Json
$runStarted = $ledger.events | Where-Object { $_.entry_id -eq $RunId -and $_.state -eq 'RUNNING' }
$runEnded = $ledger.events | Where-Object { $_.entry_id -eq $RunId -and $_.state -eq 'VERIFIED' }
$latest = $scoreChecks[$scoreChecks.Count-1]
$sourceFileHash = (Get-FileHash -LiteralPath (Join-Path $evidence 'source.json') -Algorithm SHA256).Hash.ToLowerInvariant()
$beforeCanonical = Canonical-Digest $backup
$actualCanonical = Canonical-Digest $formal
$physicalRows = @($physical.RootElement.GetProperty('dataset').EnumerateArray())
$backupMeta = @($physicalRows | Where-Object { $_[1].GetString() -ceq $publication.backup })
$formalMeta = @($physicalRows | Where-Object { $_[1].GetString() -ceq 'market_sentiment_daily' })
$walRows = @($wal.RootElement.GetProperty('dataset').EnumerateArray())
$walReady = $walRows.Count -eq 1 -and -not $walRows[0][1].GetBoolean() -and $walRows[0][2].GetInt64() -eq $walRows[0][4].GetInt64() -and $walRows[0][3].GetInt64() -eq 0
$layoutReady = $formalMeta.Count -eq 1 -and $formalMeta[0][2].GetString() -ceq 'trade_date' -and $formalMeta[0][3].GetString() -ceq 'MONTH' -and $formalMeta[0][4].GetBoolean() -and -not $formalMeta[0][5].GetBoolean()
$identityReady = $formalMeta.Count -eq 1 -and $backupMeta.Count -eq 1 -and $formalMeta[0][0].GetInt32() -eq $intent.replacementId -and $backupMeta[0][0].GetInt32() -eq $intent.originalId -and $backupMeta[0][6].GetString() -ceq $intent.originalDirectory
$checks = [ordered]@{
    schema=($schemaErrors.Count -eq 0);dates=($dateDifferences.Count -eq 0);uniqueKeys=($duplicates.RootElement.GetProperty('dataset').GetArrayLength() -eq 0);
    windowFullFieldEquality=($diffs.Count -eq 0);outsideFullFieldEquality=($outsideDiffs.Count -eq 0);finite=($nullFinite.Count -eq 0);
    beforeFingerprint=($beforeCanonical -ceq $intent.beforeFingerprint);afterFingerprint=($actualCanonical -ceq $publication.fullTargetFingerprint -and $actualCanonical -ceq $intent.afterFingerprint);
    physicalLayout=$layoutReady;physicalPublicationIdentity=$identityReady;walSettled=$walReady;
    immutableSourceEvidence=($sourceFileHash -ceq $intentScope.sourceEvidenceSha256);ledgerVerified=(@($ledger.entries | Where-Object {$_.state -ne 'VERIFIED'}).Count -eq 0 -and $ledger.publications[0].state -eq 'VERIFIED');
    partialMargin=($latest.date -eq '2026-09-30T00:00:00Z' -and $latest.nullMarginInputs -and $null -eq $latest.leverageScore -and $null -ne $latest.moneyflowScore -and $latest.quality -eq 'partial_enhanced');
    outputDatesUtcMidnight=(@($window | Where-Object {$_.values[0] % [long]86400000000 -ne 0}).Count -eq 0);
    scoreFormula=(@($scoreChecks | Where-Object {$_.coreResidual -gt 1e-12 -or $_.scoreResidual -gt 1e-12 -or $_.score -lt 0 -or $_.score -gt 100}).Count -eq 0);
    versionAndMonth=(@($scoreChecks | Where-Object {$_.month -cne '202609' -or $_.version -cne 'market_sentiment_daily_v2_rule_pca_20260427'}).Count -eq 0)
}
$result=[ordered]@{
    auditedAt=[DateTimeOffset]::Now.ToString('o'); auditMode='readonly QuestDB SELECT; no jobs/provider calls/tests';runId=$RunId;
    passed=(@($checks.Values | Where-Object {$_ -ne $true}).Count -eq 0);checks=$checks;schemaErrors=$schemaErrors.ToArray();dateDifferences=$dateDifferences;
    runningAtUtc=$runStarted.updated_at;verifiedAtUtc=$runEnded.updated_at;elapsedSeconds=([DateTimeOffset]::Parse($runEnded.updated_at)-[DateTimeOffset]::Parse($runStarted.updated_at)).TotalSeconds;logicalDate=$ledger.run[0].logical_date;
    sourceEvidenceSha256=$sourceFileHash;publicationEvidenceSha256=(Get-FileHash -LiteralPath (Join-Path $evidence 'publication.json') -Algorithm SHA256).Hash.ToLowerInvariant();
    formalRows=$formal.Count;backupRows=$backup.Count;windowRows=$window.Count;outsideRows=$outside.Count;fieldCount=$columns.Count;windowMatchedCells=$window.Count*53;outsideMatchedCells=$outside.Count*53;
    windowDifferences=$diffs;outsideDifferences=$outsideDiffs;nonfiniteFields=$nullFinite.ToArray();
    sourceRowsBinarySha256=(Binary-Digest $requested);windowReadbackBinarySha256=(Binary-Digest $window);outsideBeforeBinarySha256=(Binary-Digest $originalOutside);outsideAfterBinarySha256=(Binary-Digest $outside);expectedFullBinarySha256=(Binary-Digest $expectedAll);actualFullBinarySha256=(Binary-Digest $formal);
    beforeTargetCanonicalSha256=$beforeCanonical;receiptFullTargetFingerprint=$publication.fullTargetFingerprint;actualFullCanonicalSha256=$actualCanonical;expectedFullCanonicalSha256=(Canonical-Digest $expectedAll);scoreChecks=$scoreChecks.ToArray();publication=$publication;
    sqlEvidence=$sqlEvidence.ToArray()
}
$result | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $output 'market-sentiment-native-readback.json') -Encoding utf8
$source.Dispose()
[pscustomobject]$result | Select-Object auditedAt,passed,checks,elapsedSeconds,formalRows,backupRows,windowRows,outsideRows,windowMatchedCells,outsideMatchedCells,windowDifferences,outsideDifferences,nonfiniteFields,sourceRowsBinarySha256,windowReadbackBinarySha256,outsideBeforeBinarySha256,outsideAfterBinarySha256,beforeTargetCanonicalSha256,receiptFullTargetFingerprint,actualFullCanonicalSha256,expectedFullCanonicalSha256,scoreChecks | ConvertTo-Json -Depth 8
