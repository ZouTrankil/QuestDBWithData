param([string]$TaskName = 'QuestDB-L2-Live', [string]$At = '08:55')
$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
foreach ($name in @('java-path.txt','runtime-classpath.txt')) {
    if (!(Test-Path -LiteralPath (Join-Path $workspace ('var/l2-live/' + $name)))) {
        throw 'Build once with gradlew.bat writeL2LiveRuntime before registering the daily task.'
    }
}
$script = Join-Path $PSScriptRoot 'run.ps1'
# Run in the foreground so Task Scheduler owns the actual process lifetime and retries.
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument ('-NoProfile -File "' + $script + '" start -Foreground -NoBuild') -WorkingDirectory $workspace
$trigger = New-ScheduledTaskTrigger -Daily -At ([DateTime]::ParseExact($At,'HH:mm',$null))
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Hours 9) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Daily L2 session; authoritative QuestDB calendar gates vendor connection.'
