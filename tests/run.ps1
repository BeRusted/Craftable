[CmdletBinding()]
param(
    [ValidateSet('unit', 'server', 'inventory', 'planning', 'knowledge', 'baseline', 'all')]
    [string]$Group = 'unit',
    [switch]$Offline,
    [switch]$DryRun
)

# Only dispatch existing Gradle/JUnit/NeoForge harnesses. Assertions and PASS
# markers remain owned by those harnesses; a failed command stops this group.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$commands = @(switch ($Group) {
    'unit'      { ,@('cleanTest', 'test') }
    'server'    { ,@('runGameTestServer') }
    'inventory' { ,@('runClient', '-Pm3Smoke') }
    'planning'  { ,@('runClient', '-Pm4Smoke') }
    'knowledge' { ,@('runServer', '-Pm47Smoke'); ,@('runServer', '-Pm47Smoke') }
    'baseline'  { ,@('cleanTest', 'test', 'runGameTestServer', 'build') }
    'all' {
        ,@('cleanTest', 'test', 'runGameTestServer', 'build')
        ,@('runClient', '-Pm3Smoke')
        ,@('runClient', '-Pm4Smoke')
        ,@('runServer', '-Pm47Smoke')
        ,@('runServer', '-Pm47Smoke')
    }
})

Push-Location -LiteralPath $projectRoot
try {
    $outputDir = Join-Path $projectRoot ('run/verification/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Group)
    if (!$DryRun) { New-Item -ItemType Directory -Path $outputDir | Out-Null }
    $step = 0
    foreach ($command in $commands) {
        $step++
        $gradleArgs = @($command) + @('--console=plain', '--no-daemon')
        if ($command -contains 'test') { $gradleArgs += '--no-build-cache' }
        if ($Offline) { $gradleArgs += '--offline' }
        Write-Host ('.\gradlew.bat ' + ($gradleArgs -join ' '))
        if ($DryRun) { continue }
        $consoleLog = Join-Path $outputDir ("$step-console.log")
        # Native stderr can be a PowerShell ErrorRecord under Windows PS 5.1;
        # let Gradle's exit status decide failure, not the stream used for a warning.
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat @gradleArgs 2>&1 | Tee-Object -FilePath $consoleLog | Out-Host
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        foreach ($runName in @('gameTestServer', 'client', 'server')) {
            $task = switch ($runName) { 'gameTestServer' { 'runGameTestServer' }; 'client' { 'runClient' }; 'server' { 'runServer' } }
            $log = Join-Path $projectRoot "run/$runName/logs/latest.log"
            if ($command -contains $task -and (Test-Path -LiteralPath $log)) {
                Copy-Item -LiteralPath $log -Destination (Join-Path $outputDir "$step-$runName.log")
            }
        }
        if ($code -ne 0) { throw "Test group '$Group' failed at step $step (exit $code). See $consoleLog" }
    }
    if (!$DryRun) { Write-Host "Test group '$Group' passed. Evidence: $outputDir" }
} finally {
    Pop-Location
}
