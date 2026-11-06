param(
    [ValidateSet('test', 'demo', 'all')]
    [string]$Task = 'all'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$wrapper = if ($env:OS -eq 'Windows_NT') {
    Join-Path $projectRoot 'mvnw.cmd'
} else {
    Join-Path $projectRoot 'mvnw'
}

Push-Location -LiteralPath $projectRoot
try {
    $goals = switch ($Task) {
        'test' { @('test') }
        'demo' { @('compile', 'exec:java') }
        'all' { @('test', 'exec:java') }
    }
    & $wrapper @goals
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
