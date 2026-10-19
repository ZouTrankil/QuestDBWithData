# Pure calendar/coverage planning. No service calls or database writes.
function New-BacktestCatchupPlan {
    param([string]$Anchor, [string]$Target, [string[]]$OpenDates,
        [System.Collections.IDictionary]$Coverage, [string]$ExplicitFrom = '')
    $culture = [cultureinfo]::InvariantCulture
    $anchorDay = [datetime]::ParseExact($Anchor, 'yyyy-MM-dd', $culture)
    $targetDay = [datetime]::ParseExact($Target, 'yyyy-MM-dd', $culture)
    if ($anchorDay -gt $targetDay -or !$Coverage.Count -or $OpenDates -notcontains $Target) {
        throw 'A managed anchor, completed open target and dense-table coverage are required'
    }
    $expected = @($OpenDates | Where-Object { $_ -ge $Anchor -and $_ -le $Target } | Sort-Object -Unique)
    $missingByTable = [ordered]@{}
    $allMissing = @()
    foreach ($table in $Coverage.Keys) {
        $present = @{}
        foreach ($date in @($Coverage[$table])) { $present[[string]$date] = $true }
        $missing = @($expected | Where-Object { !$present.ContainsKey($_) })
        $missingByTable[$table] = $missing
        $allMissing += $missing
    }
    $allMissing = @($allMissing | Sort-Object -Unique)
    $fromDay = $targetDay.AddDays(-2)
    if ($fromDay -lt $anchorDay) { $fromDay = $anchorDay }
    if ($allMissing.Count) {
        $firstMissing = [datetime]::ParseExact($allMissing[0], 'yyyy-MM-dd', $culture)
        if ($firstMissing -lt $fromDay) { $fromDay = $firstMissing }
    }
    if ($ExplicitFrom) { $fromDay = [datetime]::ParseExact($ExplicitFrom, 'yyyy-MM-dd', $culture) }
    if ($fromDay -gt $targetDay) { throw 'Backfill start is after the completed open target' }
    $from = $fromDay.ToString('yyyy-MM-dd')
    $dates = @($OpenDates | Where-Object { $_ -ge $from -and $_ -le $Target } | Sort-Object -Unique)
    $chunks = @()
    for ($lo=$fromDay; $lo -le $targetDay; $lo=$lo.AddDays(5)) {
        $hi = $lo.AddDays(4); if ($hi -gt $targetDay) { $hi=$targetDay }
        $a=$lo.ToString('yyyy-MM-dd'); $b=$hi.ToString('yyyy-MM-dd')
        $days=@($dates | Where-Object { $_ -ge $a -and $_ -le $b })
        if ($days.Count) { $chunks += [pscustomobject]@{from=$a;through=$b;tradeDates=$days} }
    }
    [pscustomobject][ordered]@{coverageAnchor=$Anchor;from=$from;through=$Target;tradeDates=$dates;
        missingTradeDates=$allMissing;missingByTable=$missingByTable;chunks=$chunks;chunkCalendarDays=5}
}
