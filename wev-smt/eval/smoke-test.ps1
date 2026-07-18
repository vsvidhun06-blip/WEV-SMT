# WEV-SMT artifact smoke test (TACAS 2027 AE, "Getting started" kick-the-tyres check).
#
# Builds the project and runs the three-way LB separation that is the paper's
# central claim:
#
#   LB-real            real (semantic) dependency  -> thin-air cycle    -> FORBIDDEN
#   LB-fake-xor-cycle  fake (r^r^1) dependency     -> no semantic cycle -> ALLOWED
#   LBfd               fake linear dependency      -> no semantic cycle -> ALLOWED
#
# Runs in well under 60s on a warm Maven repo (~25s build + ~10s solve).
#
# Usage:  pwsh -File eval/smoke-test.ps1        (from the repo root)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$fails = 0
$results = @()

function Check($name, $expected, $actual) {
    if ($actual -eq $expected) {
        Write-Host "  [PASS] $name -> $actual" -ForegroundColor Green
        $script:results += "PASS  $name ($actual)"
    } else {
        Write-Host "  [FAIL] $name -> got '$actual', expected '$expected'" -ForegroundColor Red
        $script:results += "FAIL  $name (got $actual, expected $expected)"
        $script:fails++
    }
}

Write-Host "=== WEV-SMT smoke test =========================================="
$start = Get-Date

# -- Step 1: build ----------------------------------------------------------
# `package` also copies the Z3 native libraries into target/native via the
# platform-activated z3-natives-* profile in pom.xml.
Write-Host "Step 1: build (mvn package -DskipTests)..."
mvn -o -q package -DskipTests
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }
Write-Host "  build OK"

if (-not (Test-Path target/cp.txt)) {
    mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
}
$cp = "target/classes;" + (Get-Content target/cp.txt)

if (-not (Test-Path target/native)) {
    Write-Host "  [FAIL] target/native missing - Z3 natives were not copied." -ForegroundColor Red
    exit 1
}

# -- Step 2: the LB separation ---------------------------------------------
# NOTE on the fake case: eval/examples/paper/LB-fake-xor.litmus writes r^r = 0,
# so the parser finds no write of 1 and both reads default to the initial write
# -- the LB cycle is never wired and EVERY model trivially allows it. That makes
# it useless as a discriminator. LB-fake-xor-cycle.litmus (r^r^1, folds to the
# constant 1) does wire the cycle, so its ALLOWED verdict is a real model verdict.
Write-Host "Step 2: LB separation under WEAKEST..."

$tests = @(
    @{ File = 'eval/examples/paper/LB-real.litmus';           Name = 'LB-real';           Expect = 'FORBIDDEN' },
    @{ File = 'eval/examples/paper/LB-fake-xor-cycle.litmus'; Name = 'LB-fake-xor-cycle'; Expect = 'ALLOWED'   },
    @{ File = 'eval/examples/paper/LBfd.litmus';              Name = 'LBfd';              Expect = 'ALLOWED'   }
)

$files = $tests | ForEach-Object { $_.File }
$raw = java "-Djava.library.path=target\native" -cp $cp wev.smt.cli.WevBatch @files

# WevBatch prints one "name|verdict|solve_ms|status" line per file; the parser
# may also emit diagnostic lines, so match on the pipe-separated shape only.
$verdicts = @{}
foreach ($line in $raw) {
    if ($line -match '^([^|]+)\|([A-Z]+)\|(\d+)\|(.*)$') {
        $verdicts[$Matches[1]] = @{ Verdict = $Matches[2]; Ms = $Matches[3]; Status = $Matches[4] }
    }
}

foreach ($t in $tests) {
    if ($verdicts.ContainsKey($t.Name)) {
        $v = $verdicts[$t.Name]
        if ($v.Status -ne 'OK') {
            Check $t.Name $t.Expect "ERROR($($v.Status))"
        } else {
            Check $t.Name $t.Expect $v.Verdict
        }
    } else {
        Check $t.Name $t.Expect 'NO-OUTPUT'
    }
}

# -- Summary ----------------------------------------------------------------
$elapsed = [int]((Get-Date) - $start).TotalSeconds
Write-Host ""
Write-Host "=== Summary ====================================================="
foreach ($r in $results) { Write-Host "  $r" }
Write-Host "  wall-clock: ${elapsed}s"
if ($fails -eq 0) {
    Write-Host "=== SMOKE TEST PASSED (3/3) ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== SMOKE TEST FAILED ($fails of 3 checks) ===" -ForegroundColor Red
    exit 1
}
