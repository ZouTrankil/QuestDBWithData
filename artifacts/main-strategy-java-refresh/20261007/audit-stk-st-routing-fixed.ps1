param([string]$RunId='stk-st-daily-07b07a05-eadc-441f-b731-ff1a923daa41')
$ErrorActionPreference='Stop'
$artifactDir=Join-Path (Get-Location) 'artifacts/main-strategy-java-refresh/20261007'
$sourceDir=Join-Path (Get-Location) "var/main-strategy-java-refresh/20261007/sync-evidence/$RunId/source"
$before=Get-Content -LiteralPath (Join-Path $artifactDir 'stk-st-source-routing-fixed-before.json') -Raw|ConvertFrom-Json
$annuals=@(Get-ChildItem -LiteralPath $sourceDir -Filter 'namechange-*.json'|Sort-Object Name)
if($annuals.Count -ne 17){throw 'Incomplete annual evidence inventory'}
$periods=[Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
$rawShas=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$yearSummaries=@();$index=0
foreach($file in $annuals){
    $body=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json
    $year=2010+$index;$index++;$expectedEnd=if($year -eq 2026){'20260930'}else{"${year}1231"}
    if($body.year -ne $year -or $body.parameters.start_date -ne "${year}0101" -or $body.parameters.end_date -ne $expectedEnd -or $body.endpoint -ne 'namechange' -or $body.sourceKind -ne 'tushare' -or !$body.sourceComplete -or $body.returnedRows -ne $body.rawRows.Count -or $body.rawRows.Count -ge 5000 -or ($body.fields -join ',') -ne 'ts_code,name,start_date,end_date'){throw 'Annual evidence scope, completion, fields or cap differs'}
    if($body.rawRowsFingerprint -notmatch '^[0-9a-f]{64}$' -or $file.Name -ne ("namechange-$year-"+$body.rawRowsFingerprint+'.json')){throw 'Annual raw fingerprint filename differs'}
    if($body.returnedRows -gt 0 -and !$rawShas.Add($body.rawRowsFingerprint)){throw 'Repeated nonempty annual response'}
    $unique=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal);$stCount=0;$overlapCount=0
    foreach($raw in $body.rawRows){
        if($raw.ts_code -isnot [string] -or $raw.ts_code -notmatch '^[A-Z0-9]{6}\.(SH|SZ|BJ)$' -or $raw.name -isnot [string] -or [string]::IsNullOrWhiteSpace($raw.name) -or $raw.start_date -isnot [string] -or $raw.start_date -notmatch '^\d{8}$' -or ($null -ne $raw.end_date -and $raw.end_date -isnot [string])){throw 'Annual raw row has invalid field types'}
        $start=[DateTime]::ParseExact($raw.start_date,'yyyyMMdd',[Globalization.CultureInfo]::InvariantCulture)
        if(![string]::IsNullOrEmpty($raw.end_date)){$end=[DateTime]::ParseExact($raw.end_date,'yyyyMMdd',[Globalization.CultureInfo]::InvariantCulture);if($end -lt $start){throw 'Inverted effective interval'}}
        $intervalKey=$raw.ts_code+'|'+$raw.start_date+'|'+$raw.name+'|'+$raw.end_date
        if(!$unique.Add($intervalKey)){throw 'Duplicate annual raw interval'}
        if($raw.name.ToUpperInvariant().Contains('ST')){
            $stCount++;if($raw.ts_code -notmatch '^\d{6}\.(SH|SZ|BJ)$'){throw 'Invalid ST stock code'}
            if($raw.start_date -le '20260930' -and ([string]::IsNullOrEmpty($raw.end_date) -or $raw.end_date -ge '20260928')){$overlapCount++;$periods[$intervalKey]=$raw}
        }
    }
    $yearSummaries+=[ordered]@{year=$year;parameters=$body.parameters;returnedRows=$body.returnedRows;stRows=$stCount;overlappingRows=$overlapCount;sourceComplete=$body.sourceComplete;file=$file.FullName;fileSha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant();declaredRawSha256=$body.rawRowsFingerprint;effectiveStartMin=($body.rawRows.start_date|Sort-Object|Select-Object -First 1);effectiveStartMax=($body.rawRows.start_date|Sort-Object|Select-Object -Last 1)}
}
$dayFiles=@(Get-ChildItem -LiteralPath $sourceDir -Filter 'stk-st-daily-*.json'|Sort-Object Name)
if($dayFiles.Count -ne 3){throw 'Incomplete daily source receipt inventory'}
$days=@();$index=0
foreach($file in $dayFiles){
    $day=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json;$expectedDate=[DateTime]::new(2026,9,28).AddDays($index).ToString('yyyy-MM-dd');$index++
    if($day.tradeDate -ne $expectedDate -or $day.from -ne '2026-09-28' -or $day.to -ne '2026-09-30' -or $day.historyAnchor -ne '2010-01-01' -or !$day.sourceComplete -or $day.endpoint -ne 'namechange' -or $day.sourceKind -ne 'tushare.namechange' -or $day.historyReceipts.Count -ne 17){throw 'Daily source receipt scope differs'}
    foreach($ref in $day.historyReceipts){$saved=$yearSummaries[$ref.year-2010];if($ref.fingerprint -ne $saved.fileSha256 -or $ref.returnedRows -ne $saved.returnedRows -or $ref.file -ne [IO.Path]::GetFileName($saved.file)){throw 'Annual reference content SHA or count differs'}}
    $basic=$day.tradeDate.Replace('-','')
    $expected=@($periods.Values|Where-Object{$_.start_date -le $basic -and ([string]::IsNullOrEmpty($_.end_date) -or $_.end_date -ge $basic)}|Select-Object -ExpandProperty ts_code -Unique|Sort-Object)
    $actual=@($day.dailyRows.ts_code|Sort-Object)
    if(@(Compare-Object $expected $actual).Count -ne 0 -or @($actual|Select-Object -Unique).Count -ne $actual.Count){throw 'Daily receipt keys differ from independent raw interval expansion'}
    if(@($day.dailyRows|Where-Object{$_.timestamp -ne $day.tradeDate -or $_.is_st -isnot [long] -and $_.is_st -isnot [int] -or $_.is_st -ne 1 -or $_.ts_code -notmatch '^\d{6}\.(SH|SZ|BJ)$'}).Count -ne 0){throw 'Daily receipt field type or value differs'}
    $micros=[Int64](([DateTimeOffset]::Parse($day.tradeDate+'T00:00:00Z')).ToUnixTimeMilliseconds())*1000
    $old=@($before.queries.rows.result.dataset|Where-Object{$_[2] -eq $micros}|ForEach-Object{$_[0]}|Sort-Object)
    $days+=[ordered]@{date=$day.tradeDate;independentRows=$expected.Count;receiptRows=$actual.Count;oldRows=$old.Count;oldKeysExact=(@(Compare-Object $old $actual).Count -eq 0);independentExpansionExact=$true;duplicateKeys=0;invalidFieldValues=0;file=$file.FullName;fileSha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$report=[ordered]@{observedAtUtc=[DateTime]::UtcNow.ToString('o');readOnly=$true;providerCalls=0;dbWrites=0;runId=$RunId;status='SOURCE_VALIDATED_PUBLICATION_PENDING';announcementWindowNote='Tushare input start/end filter announcement dates; raw start/end are effective intervals and may extend beyond request year. ann_date is not in the owner four-field contract; this audit validates the captured request and independent effective interval expansion, not unreturned issuer disclosures.';annuals=$yearSummaries;annualWindows=17;distinctNonemptyDeclaredRawSha256=$rawShas.Count;uniqueOverlappingPeriods=$periods.Count;daily=$days;sourceRows=($days|ForEach-Object{[long]$_.receiptRows}|Measure-Object -Sum).Sum;existing837KeysPreserved=(@($days|Where-Object{!$_.oldKeysExact}).Count -eq 0);rawShaCheck='Declared canonical raw SHA, filename and cross-year uniqueness; all complete annual file SHA independently recomputed and matched every daily reference.';sourceFileShaCheck='Every daily receipt SHA independently recomputed; independent key expansion and all three fields checked.'}
[IO.File]::WriteAllText((Join-Path $artifactDir 'stk-st-native-source-audit.json'),($report|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))
[PSCustomObject]@{runId=$RunId;annualWindows=17;sourceRows=$report.sourceRows;uniqueOverlappingPeriods=$periods.Count;oldKeysExact=$report.existing837KeysPreserved;daily=$days}|ConvertTo-Json -Depth 8
