param(
    [ValidateSet('test', 'demo', 'fsck', 'all')]
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
        'fsck' { @('-Dexec.mainClass=io.github.inmemorygit.Cli', '-Dexec.args=fsck', 'exec:java') }
        'all' { @('test', 'exec:java') }
    }
    & $wrapper @goals
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($Task -eq 'all') {
        & $wrapper '-Dexec.mainClass=io.github.inmemorygit.Cli' '-Dexec.args=fsck' 'exec:java'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
} finally {
    Pop-Location
}
