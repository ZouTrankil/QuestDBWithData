$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'planning.ps1')
function Assert($Condition,[string]$Message) { if (!$Condition) { throw $Message } }
$dates=@('2026-10-12','2026-10-13','2026-10-14','2026-10-15')
$full=[ordered]@{daily=$dates;daily_basic=$dates;stk_factor=$dates;stk_limit=$dates;backtest_daily=$dates;v_backtest_daily=$dates}
$plan=New-BacktestCatchupPlan '2026-10-12' '2026-10-15' $dates $full
Assert ($plan.missingTradeDates.Count -eq 0 -and $plan.from -eq '2026-10-13') 'Complete coverage must keep the recent correction window'

# A maximum-date check would miss this historical hole.
$hole=[ordered]@{}
foreach ($table in $full.Keys) {$hole[$table]=$dates}
$hole.daily=@('2026-10-12','2026-10-14','2026-10-15')
$hole.v_backtest_daily=@('2026-10-13','2026-10-14','2026-10-15')
$plan=New-BacktestCatchupPlan '2026-10-12' '2026-10-15' $dates $hole
Assert (($plan.missingTradeDates -join ',') -eq '2026-10-12,2026-10-13') 'Union of intermediate holes must include every affected trading day'
Assert ($plan.from -eq '2026-10-12' -and $plan.missingByTable.daily[0] -eq '2026-10-13') 'Backfill must reach the earliest table-specific gap'

# Synthetic open-day fixture: tests batching mechanics, not real exchange holidays.
$longDates=@();$start=[datetime]'2026-01-01';$end=[datetime]'2026-03-31'
for ($day=$start;$day -le $end;$day=$day.AddDays(1)) {
    if ($day.DayOfWeek -notin @([DayOfWeek]::Saturday,[DayOfWeek]::Sunday)) {$longDates+=$day.ToString('yyyy-MM-dd')}
}
$plan=New-BacktestCatchupPlan '2026-01-01' '2026-03-31' $longDates ([ordered]@{daily=@();backtest_daily=@('2026-03-31')})
Assert ($plan.from -eq '2026-01-01' -and $plan.missingTradeDates.Count -eq $longDates.Count) 'A gap longer than 32 days must remain recoverable'
$actualDates=@($plan.chunks | ForEach-Object {$_.tradeDates})
Assert (($actualDates -join ',') -eq ($longDates -join ',')) 'Chunks must cover each expected trading date exactly once'
foreach ($chunk in $plan.chunks) {
    Assert (([datetime]$chunk.through-[datetime]$chunk.from).TotalDays -le 4) 'A native-owner chunk may not exceed five calendar days'
    Assert ($chunk.tradeDates.Count -gt 0) 'Closed-only chunks must be skipped'
}

$weekend=@('2026-10-09','2026-10-12')
$plan=New-BacktestCatchupPlan '2026-10-09' '2026-10-12' $weekend ([ordered]@{daily=$weekend})
Assert ($plan.missingTradeDates.Count -eq 0 -and ($plan.tradeDates -join ',') -eq '2026-10-12') 'Closed days must not be treated as missing sessions'
$plan=New-BacktestCatchupPlan '2026-10-12' '2026-10-15' $dates $full '2026-10-14'
Assert ($plan.from -eq '2026-10-14' -and $plan.tradeDates.Count -eq 2) 'Explicit manual bounds must be honored'
$rejected=$false
try {New-BacktestCatchupPlan '2026-10-12' '2026-10-16' $dates $full | Out-Null} catch {$rejected=$true}
Assert $rejected 'A target without an open calendar session must be rejected'
Write-Output 'PASS: complete coverage, intermediate holes, 90-day outage, bounded chunks, closed sessions, manual bounds, invalid target'
