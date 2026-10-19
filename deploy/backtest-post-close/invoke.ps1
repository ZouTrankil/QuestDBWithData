param(
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$Through='',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$From='',
    [switch]$PlanOnly,
    [ValidateRange(0,1800)][int]$DependencyWaitSeconds=600
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'planning.ps1')
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runtimeRoot=Join-Path $workspace 'var/backtest-post-close'
$statusPath=Join-Path $runtimeRoot 'status.json'
$anchorPath=Join-Path $runtimeRoot 'coverage-anchor.json'
$utf8=[Text.UTF8Encoding]::new($false)
$lock=$null; $check=$null; $status=$null; $dataRunStarted=$false; $runRoot=$null
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
function Query([string]$Sql) {
    $timeout=if ($PlanOnly) {15} else {1800}
    Invoke-RestMethod -Uri ('http://127.0.0.1:9000/exec?limit=0,20000&query='+[uri]::EscapeDataString($Sql)) -TimeoutSec $timeout
}
function Scalar([string]$Sql) { (Query $Sql).dataset[0][0] }
function Save-Json([string]$Path,$Value) {
    $pending=$Path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    [IO.File]::WriteAllText($pending,($Value | ConvertTo-Json -Depth 30),$utf8)
    Move-Item -LiteralPath $pending -Destination $Path -Force
}
function Day($Value) {
    if ($Value -is [datetime] -or $Value -is [datetimeoffset]) { return [datetime]$Value.Date }
    [datetime]::ParseExact(([string]$Value).Substring(0,10),'yyyy-MM-dd',[cultureinfo]::InvariantCulture)
}
function Date-Text([datetime]$Value) { $Value.ToString('yyyy-MM-dd') }
function Save-Check {
    if ($check) { Save-Json (Join-Path $checkRoot 'check.json') $check; Save-Json (Join-Path $runtimeRoot 'last-check.json') $check }
}
function Wait-Dependencies {
    $deadline=[datetime]::UtcNow.AddSeconds($DependencyWaitSeconds)
    do {
        $qdb=$false; $provider=$false; $reason=''
        try { $probe=Invoke-RestMethod -Uri 'http://127.0.0.1:9000/exec?query=select%201' -TimeoutSec 5; $qdb=($probe.dataset[0][0] -eq 1) } catch { $reason='QuestDB: '+$_.Exception.Message }
        if ($qdb) {
            try { $response=Invoke-WebRequest -Uri 'http://api.tushare.pro' -UseBasicParsing -TimeoutSec 10; $provider=($response.StatusCode -eq 200) } catch { $reason='Tushare connectivity: '+$_.Exception.Message }
        }
        $check['questdbReady']=$qdb; $check['providerReachable']=$provider
        if ($qdb -and $provider) { return }
        $check.phase='WAITING_DEPENDENCIES';$check['dependencyError']=$reason;Save-Check
        if ([datetime]::UtcNow -ge $deadline) { throw ('Dependencies did not become ready: '+$reason) }
        Start-Sleep -Seconds 5
    } while ($true)
}
function Table-State {
    Query "select t.id,t.table_name,t.directoryName,w.writerTxn,w.sequencerTxn,w.bufferedTxnSize,w.suspended from tables() t join wal_tables() w on w.name=t.table_name where t.table_name in ('daily','daily_basic','stk_factor','stk_limit','stk_suspend','stk_st_daily','backtest_daily','v_backtest_daily') order by t.table_name"
}
function Assert-Settled($State) {
    if (@($State.dataset).Count -ne 8) { throw 'All eight formal WAL objects are required' }
    foreach ($row in $State.dataset) { if ($row[3] -ne $row[4] -or $row[5] -ne 0 -or $row[6]) { throw ('Unsettled WAL: '+$row[1]) } }
}
function Invoke-Owner([string]$Command,[datetime]$Lo,[datetime]$Hi) {
    $tag=$Command+'-'+(Date-Text $Lo)+'-'+(Date-Text $Hi)
    $logs=if ($dataRunStarted) {$runRoot} else {$checkRoot}
    $out=Join-Path $logs ($tag+'.log');$err=Join-Path $logs ($tag+'.stderr.log')
    $arguments=@('-Xms128m','-Xmx2g','-Dapp.questdb.host=127.0.0.1','-Dapp.tushare.api-url=http://api.tushare.pro',
        '-Dapp.sync.stk-limit-table=stk_limit','-Dapp.sync.stk-suspend-table=stk_suspend','-Dapp.sync.stk-st-daily-table=stk_st_daily',
        ('-Dapp.sync.ledger-path='+(Join-Path $workspace 'var/sync-ledger.sqlite3')),
        '-cp',$runtime.classpath,'com.zoutrankil.data.QuestDataApplication',$Command,
        '--from',(Date-Text $Lo),'--to',(Date-Text $Hi),'--logical-date',(Date-Text $now.Date))
    if ($Command -ne 'run-backtest-daily-job') { $arguments+=@('--mode','BACKFILL') }
    if ($Command -eq 'run-exchange-calendar') { $arguments+=@('--exchanges','SSE,SZSE') }
    $argfile=Join-Path $logs ($tag+'.args')
    [IO.File]::WriteAllLines($argfile,@($arguments | ForEach-Object {'"'+$_.Replace('\','/').Replace('"','\"')+'"'}),$utf8)
    $check.phase=$Command;$check['window']=@((Date-Text $Lo),(Date-Text $Hi));Save-Check
    if ($dataRunStarted) { $status.phase=$Command;$status.window=$check.window;Save-Json $statusPath $status }
    Write-Output ($Command+' '+(Date-Text $Lo)+'..'+(Date-Text $Hi))
    $child=Start-Process -FilePath $runtime.java -ArgumentList ('"@'+$argfile+'"') -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput $out -RedirectStandardError $err -Wait -PassThru
    $stage=[ordered]@{command=$Command;from=(Date-Text $Lo);through=(Date-Text $Hi);exitCode=$child.ExitCode;log=$out;logSha256=(Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToLowerInvariant()}
    $check.stages+=$stage;Save-Check
    if ($dataRunStarted) { $status.stages+=$stage;Save-Json $statusPath $status }
    if ($child.ExitCode -ne 0) { throw ('Owner failed; retain and reconcile its original ledger run: '+$out) }
}
function Test-Window([datetime]$Lo,[datetime]$Hi,[string[]]$Dates) {
    $sql=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'verify-source.sql')).Replace('{from}',(Date-Text $Lo)).Replace('{until}',(Date-Text $Hi.AddDays(1)))
    $source='('+$sql+')'
    $fields='trade_date,ts_code,open,high,low,close,vol,amount,adj_factor,up_limit,down_limit,cast(is_suspended as LONG) is_suspended,is_st'
    $predicate="trade_date>='"+(Date-Text $Lo)+"' and trade_date<'"+(Date-Text $Hi.AddDays(1))+"'"
    $before=Table-State;Assert-Settled $before;$counts=@()
    foreach ($table in @('backtest_daily','v_backtest_daily')) {
        $actual='select '+$fields+' from '+$table+' where '+$predicate
        $differences=@();$differences+='select * from '+$source+' except all '+$actual
        $differences+=$actual+' except all select * from '+$source
        foreach ($difference in $differences) { if ([long](Scalar ('select count() from ('+$difference+')')) -ne 0) { throw ('Full key/value source comparison failed: '+$table) } }
        $rows=Query ('select trade_date,count() n,count_distinct(ts_code) nk from '+$table+' where '+$predicate+' group by trade_date order by trade_date')
        if (@($rows.dataset).Count -ne $Dates.Count) { throw ('Trading date coverage failed: '+$table) }
        for ($i=0;$i -lt $Dates.Count;$i++) { if ((Date-Text (Day $rows.dataset[$i][0])) -ne $Dates[$i] -or $rows.dataset[$i][1] -lt 1 -or $rows.dataset[$i][1] -ne $rows.dataset[$i][2]) { throw ('Date/key coverage failed: '+$table) } }
        $counts+=[ordered]@{table=$table;from=(Date-Text $Lo);through=(Date-Text $Hi);counts=$rows.dataset}
    }
    $after=Table-State;Assert-Settled $after
    if (($before.dataset | ConvertTo-Json -Depth 8 -Compress) -ne ($after.dataset | ConvertTo-Json -Depth 8 -Compress)) { throw 'Source or target changed during physical audit' }
    $missing=Query ('select trade_date,ts_code from daily where '+$predicate+' except select trade_date,ts_code from v_backtest_daily where '+$predicate)
    [pscustomobject]@{physicalAudit=$counts;dailyUniverseMissing=@($missing.dataset)}
}
try {
    $now=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
    $ceiling=$now.Date;if ($now.TimeOfDay -lt [timespan]::FromHours(20.5)) {$ceiling=$ceiling.AddDays(-1)}
    $throughDay=if ($Through) {Day $Through} else {$ceiling}
    if ($throughDay -gt $ceiling) {throw 'Requested date is after the 20:30 Asia/Shanghai source completion cutoff'}
    if (!$PlanOnly) {
        try {$lock=[IO.File]::Open((Join-Path $runtimeRoot 'job.lock'),'OpenOrCreate','ReadWrite','None')}
        catch [IO.IOException] {Write-Output '{"phase":"already_running"}';exit 0}
        $checkId=[datetime]::UtcNow.ToString('yyyyMMddTHHmmss')+'-'+[guid]::NewGuid().ToString('N')
        $checkRoot=Join-Path $runtimeRoot ('checks/'+$checkId);New-Item -ItemType Directory -Path $checkRoot -Force | Out-Null
        $check=[ordered]@{phase='CHECKING';startedAt=[DateTimeOffset]::Now.ToString('o');user=[Security.Principal.WindowsIdentity]::GetCurrent().Name;sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId;userInteractive=[Environment]::UserInteractive;stages=@();error=$null}
        Save-Check;Wait-Dependencies
        $runtime=Get-Content -LiteralPath (Join-Path $runtimeRoot 'runtime.json') -Raw | ConvertFrom-Json
        foreach ($library in $runtime.libraries) {if ((Get-FileHash -LiteralPath $library.path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $library.sha256) {throw 'Frozen runtime library changed; redeploy before running'}}
        $javaProbe=Start-Process -FilePath $runtime.java -ArgumentList '-version' -WorkingDirectory $workspace -WindowStyle Hidden -RedirectStandardOutput (Join-Path $checkRoot 'java-version.stdout.log') -RedirectStandardError (Join-Path $checkRoot 'java-version.log') -Wait -PassThru
        if ($javaProbe.ExitCode -ne 0) {throw 'The frozen Java runtime could not start in this task session'}
        $check['javaReady']=$true;Save-Check
    }
    $prior=if (Test-Path -LiteralPath $statusPath) {Get-Content -LiteralPath $statusPath -Raw | ConvertFrom-Json} else {$null}
    $dense=@('daily','daily_basic','stk_factor','stk_limit','backtest_daily','v_backtest_daily')
    $frontiers=@($dense | ForEach-Object {$latest=Scalar ('select max(trade_date) from '+$_);if (!$latest) {throw ('Historical bootstrap required: '+$_)};Day $latest})
    $oldest=($frontiers | Sort-Object | Select-Object -First 1)
    if (Test-Path -LiteralPath $anchorPath) {$anchor=Day ((Get-Content -LiteralPath $anchorPath -Raw | ConvertFrom-Json).from)}
    elseif ($prior -and $prior.PSObject.Properties['from']) {$anchor=Day $prior.from}
    else {$anchor=$oldest.AddDays(-2)}
    if ($oldest.AddDays(-2) -lt $anchor) {$anchor=$oldest.AddDays(-2)}
    $calendarFrom=$anchor
    if ($From -and (Day $From) -lt $calendarFrom) {$calendarFrom=Day $From}
    if ($calendarFrom -gt $throughDay) {throw 'Managed coverage starts after the requested date; supply an explicit bounded -From'}
    $calendarSql="select cal_date,is_open from exchange_calendar where exchange='SSE' and cal_date>='"+(Date-Text $calendarFrom)+"' and cal_date<='"+(Date-Text $throughDay)+"' order by cal_date"
    $calendar=Query $calendarSql
    $calendarDays=[int]($throughDay-$calendarFrom).TotalDays+1
    if (@($calendar.dataset).Count -ne $calendarDays) {
        if ($PlanOnly) {throw 'Calendar coverage is incomplete; a real run will refresh it through the existing Java owner'}
        for ($lo=$calendarFrom;$lo -le $throughDay;$lo=$lo.AddDays(366)) {$hi=$lo.AddDays(365);if ($hi -gt $throughDay) {$hi=$throughDay};Invoke-Owner 'run-exchange-calendar' $lo $hi}
        $calendar=Query $calendarSql
        if (@($calendar.dataset).Count -ne $calendarDays) {throw 'Source-certified SSE calendar is still incomplete'}
    }
    $openDates=@($calendar.dataset | Where-Object {$_[1] -eq 1} | ForEach-Object {Date-Text (Day $_[0])})
    if (!$openDates.Count) {throw 'No completed open session in the managed calendar'}
    $target=$openDates[-1];$coverage=[ordered]@{}
    foreach ($table in $dense) {
        $rows=Query ('select distinct trade_date from '+$table+" where trade_date>='"+(Date-Text $calendarFrom)+"' and trade_date<='"+$target+"' order by trade_date")
        $coverage[$table]=@($rows.dataset | ForEach-Object {Date-Text (Day $_[0])})
    }
    $planAnchor=if ($From -and (Day $From) -lt $anchor) {$From} else {Date-Text $anchor}
    $plan=New-BacktestCatchupPlan -Anchor $planAnchor -Target $target -OpenDates $openDates -Coverage $coverage -ExplicitFrom $From
    if ($PlanOnly) {$plan | ConvertTo-Json -Depth 12;exit 0}
    Save-Json $anchorPath ([ordered]@{from=(Date-Text $anchor);scope='Managed post-close trading dates; dates are checked in all six dense formal objects'})
    $check['plan']=$plan;Save-Check
    $current=Table-State;Assert-Settled $current
    if (!$From -and $prior -and $prior.phase -eq 'VERIFIED' -and $prior.through -eq $target -and !$plan.missingTradeDates.Count -and
        ($prior.tables.dataset | ConvertTo-Json -Depth 8 -Compress) -eq ($current.dataset | ConvertTo-Json -Depth 8 -Compress)) {
        $check.phase='ALREADY_VERIFIED';$check['finishedAt']=[DateTimeOffset]::Now.ToString('o');Save-Check
        Write-Output ('{"phase":"already_verified","through":"'+$target+'","missingTradeDates":[]}');exit 0
    }
    $runRoot=Join-Path $runtimeRoot ('runs/'+$target+'-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
    $status=[ordered]@{phase='RUNNING';coverageAnchor=(Date-Text $anchor);from=$plan.from;through=$target;tradeDates=$plan.tradeDates;missingTradeDates=$plan.missingTradeDates;missingByTable=$plan.missingByTable;startedAt=[DateTimeOffset]::Now.ToString('o');runRoot=$runRoot;checkRoot=$checkRoot;stages=@($check.stages);completedChunks=@();window=@();error=$null;tables=$null}
    $dataRunStarted=$true;Save-Json (Join-Path $runRoot 'plan.json') $plan;Save-Json $statusPath $status
    foreach ($chunk in $plan.chunks) {
        $lo=Day $chunk.from;$hi=Day $chunk.through
        foreach ($command in @('run-daily-job','run-daily-basic-job','run-stk-limit-job','run-stk-suspend-job','run-stk-st-daily-job','run-stk-factor-job')) {
            if ($command -eq 'run-stk-factor-job') {foreach ($day in $chunk.tradeDates) {Invoke-Owner $command (Day $day) (Day $day)}}
            else {Invoke-Owner $command $lo $hi}
        }
        Invoke-Owner 'run-backtest-daily-job' $lo $hi
        $status.completedChunks+=$chunk;Save-Json $statusPath $status
    }
    $counts=@();$universeMissing=@();$beforeAudit=Table-State;Assert-Settled $beforeAudit
    foreach ($chunk in $plan.chunks) {
        $audit=Test-Window (Day $chunk.from) (Day $chunk.through) $chunk.tradeDates
        $counts+=@($audit.physicalAudit);$universeMissing+=@($audit.dailyUniverseMissing)
    }
    $afterAudit=Table-State;Assert-Settled $afterAudit
    if (($beforeAudit.dataset | ConvertTo-Json -Depth 8 -Compress) -ne ($afterAudit.dataset | ConvertTo-Json -Depth 8 -Compress)) {throw 'Source or target changed during final chunk audits'}
    $status['dailyUniverseComplete']=($universeMissing.Count -eq 0);$status['dailyUniverseMissing']=$universeMissing
    $status.phase='VERIFIED';$status.tables=$afterAudit;$status['finishedAt']=[DateTimeOffset]::Now.ToString('o');$status['physicalAudit']=$counts
    Save-Json (Join-Path $runRoot 'result.json') $status;Save-Json $statusPath $status
    $check.phase='VERIFIED';$check['runRoot']=$runRoot;$check['finishedAt']=$status.finishedAt;Save-Check
    $status | ConvertTo-Json -Depth 20
} catch {
    if ($check) {$check.phase='FAILED';$check.error=$_.Exception.Message;Save-Check}
    if ($dataRunStarted -and $status) {$status.phase='FAILED';$status.error=$_.Exception.Message;Save-Json (Join-Path $runRoot 'result.json') $status;Save-Json $statusPath $status}
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {if ($lock) {$lock.Dispose()}}
