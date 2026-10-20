param(
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$Through='',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$From='',
    [switch]$PlanOnly,
    [ValidateRange(0,1800)][int]$DependencyWaitSeconds=600
)
& (Join-Path $PSScriptRoot 'invoke.ps1') @PSBoundParameters
exit $LASTEXITCODE