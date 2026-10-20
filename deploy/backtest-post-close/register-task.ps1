param([string]$TaskUser = '')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$root=Join-Path $workspace 'var/backtest-post-close'
$receipt=Join-Path $root 'task-registration.json'
$utf8=[Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Path $root -Force | Out-Null
$result=[ordered]@{status='REGISTERING';startedAt=[DateTimeOffset]::Now.ToString('o');error=$null}
try {
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
    if (!([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Administrator approval is required to register the boot trigger. Run register-task.ps1 from an elevated PowerShell.'
    }
    if (!$TaskUser) { $TaskUser=$identity.Name }
    if ((Get-TimeZone).Id -ne 'China Standard Time') { throw 'The host timezone must be Asia/Shanghai' }
    if (!(Test-Path -LiteralPath (Join-Path $root 'runtime.json') -PathType Leaf)) { throw 'Install the frozen Java runtime first' }
    $name='QuestDB-Backtest-PostClose'
    $existing=Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
    if ($existing -and $existing.State -eq 'Running') { throw 'The existing task is running; let its native writer finish before changing registration' }
    if ($existing) {
        $backup=Join-Path $root ('scheduled-task-before-'+[guid]::NewGuid().ToString('N')+'.xml')
        Export-ScheduledTask -TaskName $name -ErrorAction Stop | Set-Content -LiteralPath $backup -Encoding UTF8
        $result['backup']=$backup
    }
    $powershell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $script=Join-Path $PSScriptRoot 'run.ps1'
    $action=New-ScheduledTaskAction -Execute $powershell -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$script+'"') -WorkingDirectory $workspace
    $daily=New-ScheduledTaskTrigger -Daily -At '20:35'
    $daily.Repetition=(New-ScheduledTaskTrigger -Once -At '20:35' -RepetitionInterval (New-TimeSpan -Minutes 15) -RepetitionDuration (New-TimeSpan -Hours 3 -Minutes 15)).Repetition
    $daily.Repetition.StopAtDurationEnd=$false
    $boot=New-ScheduledTaskTrigger -AtStartup
    $boot.Delay='PT2M'
    $logon='S4U'
    $principal=New-ScheduledTaskPrincipal -UserId $TaskUser -LogonType $logon -RunLevel Limited
    # Native owners already have finite deadlines and durable unknown-write guards.
    # Do not kill an active publication merely because a long catch-up reached 3h.
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([timespan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 15) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $settings.AllowHardTerminate=$false
    Register-ScheduledTask -TaskName $name -Action $action -Trigger @($daily,$boot) -Settings $settings -Principal $principal -Description 'Java local QuestDB post-close ingestion. Runs without login; boot catch-up checks actual trading-date coverage and uses verified bounded owners.' -Force -ErrorAction Stop | Out-Null
    $task=Get-ScheduledTask -TaskName $name -ErrorAction Stop
    if ([string]$task.Principal.LogonType -ne $logon -or @($task.Triggers | Where-Object {$_.CimClass.CimClassName -eq 'MSFT_TaskBootTrigger'}).Count -ne 1 -or @($task.Triggers | Where-Object {$_.CimClass.CimClassName -eq 'MSFT_TaskLogonTrigger'}).Count -ne 0) { throw 'Task readback does not match the requested unattended definition' }
    $registeredDaily=@($task.Triggers | Where-Object {$_.CimClass.CimClassName -eq 'MSFT_TaskDailyTrigger'})
    if ($registeredDaily.Count -ne 1 -or $registeredDaily[0].Repetition.StopAtDurationEnd -or $task.Settings.AllowHardTerminate -or $task.Settings.ExecutionTimeLimit -ne 'PT0S') { throw 'Task readback would allow a scheduled stop during native publication' }
    Export-ScheduledTask -TaskName $name -ErrorAction Stop | Set-Content -LiteralPath (Join-Path $root 'scheduled-task.xml') -Encoding UTF8
    $info=Get-ScheduledTaskInfo -TaskName $name -ErrorAction Stop
    $result.status='INSTALLED';$result['taskName']=$name;$result['taskUser']=$task.Principal.UserId
    $result['logonType']=[string]$task.Principal.LogonType;$result['bootDelay']='PT2M'
    $result['dailyAt']='20:35 Asia/Shanghai';$result['restartInterval']=$task.Settings.RestartInterval
    $result['restartCount']=$task.Settings.RestartCount;$result['executionTimeLimit']=$task.Settings.ExecutionTimeLimit
    $result['stopAtRepetitionEnd']=$registeredDaily[0].Repetition.StopAtDurationEnd
    $result['nextRunTime']=$info.NextRunTime.ToString('o')
    $result['finishedAt']=[DateTimeOffset]::Now.ToString('o')
    [IO.File]::WriteAllText($receipt,($result | ConvertTo-Json -Depth 8),$utf8)
    $result | ConvertTo-Json -Depth 8
} catch {
    $result.status='FAILED';$result.error=$_.Exception.Message
    [IO.File]::WriteAllText($receipt,($result | ConvertTo-Json -Depth 8),$utf8)
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
