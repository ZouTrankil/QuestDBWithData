$ErrorActionPreference='Stop'
$taskPath=Join-Path $PSScriptRoot 'regime-monitor-ledger.json'
$taskLedger=Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json
$taskSchema=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'regime-monitor-schema-readonly.json') -Raw | ConvertFrom-Json
$taskRunId='regime-monitor-5c1ba217-3a43-40cd-82cb-69a83d69b756'
$taskEvents=@($taskLedger.events | Where-Object entry_id -eq $taskRunId)
$taskPending=$taskEvents | Where-Object state -eq PENDING | Select-Object -First 1
$taskRunning=$taskEvents | Where-Object state -eq RUNNING | Select-Object -First 1
$taskFinished=$taskEvents | Where-Object state -eq VERIFIED | Select-Object -Last 1
$taskSliceFetched=$taskLedger.events | Where-Object state -eq FETCHED | Select-Object -First 1
$taskSliceSubmitted=$taskLedger.events | Where-Object state -eq SUBMITTED | Select-Object -First 1
$taskSliceVerified=$taskLedger.events | Where-Object { $_.state -eq 'VERIFIED' -and $_.entry_id -like 'slice-*' } | Select-Object -First 1
$taskPublication=$taskLedger.publications[0]
$taskScope=$taskPublication.intent_json_parsed.scope | ConvertFrom-Json
$taskSource=Get-Content -LiteralPath $taskScope.sourceEvidence -Raw | ConvertFrom-Json
$taskSourceHash=(Get-FileHash -LiteralPath $taskScope.sourceEvidence -Algorithm SHA256).Hash.ToLower()
if($taskSourceHash -ne $taskScope.sourceEvidenceSha256){throw 'Regime source SHA differs from publication intent'}
$taskDuration=[ordered]@{
    createdAtUtc=$taskPending.updated_at
    runningAtUtc=$taskRunning.updated_at
    sourceFetchedAtUtc=$taskSliceFetched.updated_at
    sourceSliceSubmittedAtUtc=$taskSliceSubmitted.updated_at
    sourceSliceVerifiedAtUtc=$taskSliceVerified.updated_at
    publicationVerifiedAtUtc=$taskPublication.updated_at
    finishedAtUtc=$taskFinished.updated_at
    createdToVerifiedSeconds=(([DateTimeOffset]$taskFinished.updated_at)-([DateTimeOffset]$taskPending.updated_at)).TotalSeconds
    runningToVerifiedSeconds=(([DateTimeOffset]$taskFinished.updated_at)-([DateTimeOffset]$taskRunning.updated_at)).TotalSeconds
    runningToFetchedSeconds=(([DateTimeOffset]$taskSliceFetched.updated_at)-([DateTimeOffset]$taskRunning.updated_at)).TotalSeconds
    submittedToSliceVerifiedSeconds=(([DateTimeOffset]$taskSliceVerified.updated_at)-([DateTimeOffset]$taskSliceSubmitted.updated_at)).TotalSeconds
    postSliceVerificationToRunFinishedSeconds=(([DateTimeOffset]$taskFinished.updated_at)-([DateTimeOffset]$taskSliceVerified.updated_at)).TotalSeconds
    timingNote='Derived from ledger timestamps; tail includes publication and runner finalization, not a separately instrumented pure publication timer.'
}
$taskEvidence=[ordered]@{
    sourcePath=$taskScope.sourceEvidence
    actualSourceSha256=$taskSourceHash
    publicationSourceSha256=$taskScope.sourceEvidenceSha256
    shaMatches=$true
    producer=$taskSource.producer
    sourceFingerprint=$taskSource.sourceFingerprint
    sourceWindowRows=$taskSource.rows.Count
    typedFieldCount=21
    sourceWindowValues=$taskSource.rows.Count*21
    retainedOldRows=659
    retainedOldValues=659*21
    publishedRows=666
    publishedValues=666*21
    fullPublishedFingerprint=$taskPublication.intent_json_parsed.afterFingerprint
    rawSourceProperties=$taskSource.PSObject.Properties.Name
    scope=$taskScope
    sourceFingerprintNote='Publication uses the per-source/slice fingerprint; run verification sourceFingerprint is the runner aggregate and can differ.'
}
$taskLedger | Add-Member -NotePropertyName questdbReadOnly -NotePropertyValue $taskSchema -Force
$taskLedger | Add-Member -NotePropertyName timings -NotePropertyValue $taskDuration -Force
$taskLedger | Add-Member -NotePropertyName sourceEvidenceAudit -NotePropertyValue $taskEvidence -Force
$taskLedger | ConvertTo-Json -Depth 26 | Set-Content -LiteralPath $taskPath -Encoding utf8
[pscustomobject]@{runId=$taskRunId;state=($taskLedger.entries | Where-Object kind -eq RUN).state;publication=$taskPublication.state;sourceWindowRows=$taskSource.rows.Count;schemaColumns=$taskSchema.schema.count;publishedRows=666;createdToVerifiedSeconds=$taskDuration.createdToVerifiedSeconds;runningSeconds=$taskDuration.runningToVerifiedSeconds;fullFingerprint=$taskEvidence.fullPublishedFingerprint;sourceShaMatches=$true} | ConvertTo-Json -Depth 4
