<#
  WEV-SMT run wrapper (C4-1), Windows PowerShell. Sets the Z3 native library path and runs
  a CLI main class with the full project classpath. All experiment runs should go through
  this wrapper so -Djava.library.path is never wrong or forgotten.

  Usage:   eval\run_wev.ps1 <fully.qualified.MainClass> [args...]
  Example: eval\run_wev.ps1 wev.smt.cli.Layer2AuditRun eval/corpus-weakest-manifest-v2.csv

  Requires: `mvn -q test-compile` at least once (compiles classes and copies the Z3 natives
  into target\native). This script (re)generates the cached dependency classpath if missing.
#>
param(
  [Parameter(Mandatory = $true, Position = 0)] [string] $MainClass,
  [Parameter(ValueFromRemainingArguments = $true)] [string[]] $Args
)
$ErrorActionPreference = 'Stop'

# Repo root = parent of this script's directory (script lives in eval\).
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$nativeDir = Join-Path $root 'target\native'
$cpFile    = Join-Path $root 'target\cp.txt'

if (-not (Test-Path $nativeDir)) {
  Write-Host "[run_wev] target\native missing - running 'mvn -q test-compile' to compile + copy Z3 natives..."
  mvn -q test-compile
}
if (-not (Test-Path $cpFile)) {
  Write-Host "[run_wev] regenerating dependency classpath ($cpFile)..."
  mvn -q dependency:build-classpath "-Dmdep.outputFile=$cpFile"
}

# Windows classpath separator is ';'.
$cp = "target\classes;" + (Get-Content -Raw $cpFile).Trim()

& java "-Djava.library.path=$nativeDir" -cp $cp $MainClass @Args
exit $LASTEXITCODE
