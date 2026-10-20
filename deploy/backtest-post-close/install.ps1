param([string]$JavaHome = '', [switch]$RegisterTask, [switch]$Unattended)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Unattended -and !$RegisterTask) { throw '-Unattended requires -RegisterTask; unattended is now the default registration mode' }
if ($RegisterTask -and !([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run task registration in an elevated PowerShell: Windows requires administrator approval for boot triggers'
}
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$root = Join-Path $workspace 'var/backtest-post-close'
if (!$JavaHome) { $JavaHome = Join-Path $workspace '.gradle/toolchains/temurin-24.0.2/jdk-24.0.2+12' }
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Java 24 is required; supply -JavaHome' }
$previousJava = $env:JAVA_HOME
Push-Location $workspace
try {
    $env:JAVA_HOME = $JavaHome
    & (Join-Path $workspace 'gradlew.bat') :installDist --offline --console=plain
    if ($LASTEXITCODE) { throw 'Application distribution build failed' }
} finally { $env:JAVA_HOME = $previousJava; Pop-Location }

$runtime = Join-Path $root ('runtime/' + [guid]::NewGuid().ToString('N'))
$lib = Join-Path $runtime 'lib'
New-Item -ItemType Directory -Path $lib -Force | Out-Null
$entries = @()
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $workspace 'build/install/QuestDBWithData/lib') -Filter '*.jar' -File) {
    $before = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $destination = Join-Path $lib $file.Name
    Copy-Item -LiteralPath $file.FullName -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $before) { throw 'Runtime changed while copying' }
    $entries += [ordered]@{path=$destination;sha256=$before;bytes=$file.Length}
}
if ($entries.Count -lt 10) { throw 'Incomplete application runtime' }
$manifest = [ordered]@{java=$java;classpath=(Join-Path $lib '*');createdAt=[DateTimeOffset]::UtcNow.ToString('o');libraries=$entries}
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $runtime 'manifest.json'),($manifest | ConvertTo-Json -Depth 8),$utf8)
$pending = Join-Path $root ('runtime-' + [guid]::NewGuid().ToString('N') + '.tmp')
[IO.File]::WriteAllText($pending,($manifest | ConvertTo-Json -Depth 8),$utf8)
Move-Item -LiteralPath $pending -Destination (Join-Path $root 'runtime.json') -Force

if ($RegisterTask) {
    & (Join-Path $PSScriptRoot 'register-task.ps1')
    if ($LASTEXITCODE) { throw 'Task registration failed; inspect var/backtest-post-close/task-registration.json' }
}
Write-Output ('Runtime installed: ' + $runtime)
