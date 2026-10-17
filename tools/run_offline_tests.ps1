# Windows 入口也走同一份离线规则，避免漏掉市场和独立应用。
[CmdletBinding()]
param([string[]]$Tests = @(), [string]$EvidenceDirectory = '.gradle/offline-checks',
      [string]$JavaHome = '', [switch]$DryRun)
$ErrorActionPreference = 'Stop'
$python = Get-Command python3 -ErrorAction SilentlyContinue
if (!$python) { $python = Get-Command python -ErrorAction Stop }
$arguments = @((Join-Path $PSScriptRoot 'run_offline_tests.py'), '--evidence-directory', $EvidenceDirectory)
foreach ($selector in $Tests) { $arguments += @('--tests', $selector) }
if ($DryRun) { $arguments += '--dry-run' }
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    if ($JavaHome) {
        $env:JAVA_HOME = $JavaHome
        $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $oldPath
    }
    & $python.Source @arguments
    $code = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
if ($code -ne 0) { throw '离线检查未通过，请查看输出中的报告目录' }
