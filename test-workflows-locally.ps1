#!/usr/bin/env pwsh
# Local GitHub Actions Workflow Testing Script
# Runs the most critical checks that GitHub Actions will perform

param(
    [switch]$SkipTests,
    [switch]$SkipSignature,
    [switch]$QuickCheck,
    [switch]$FullBuild
)

$ErrorActionPreference = "Continue"
$FailedChecks = @()

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "LOCAL GITHUB ACTIONS WORKFLOW TESTING" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# Get branch info
$CurrentBranch = git branch --show-current
Write-Host "Current Branch: $CurrentBranch`n" -ForegroundColor Yellow

# ============================================================================
# 1. COMMIT SIGNATURE VERIFICATION (verify-commits.yml)
# ============================================================================
if (-not $SkipSignature) {
    Write-Host "`n[1/6] Checking Commit Signatures..." -ForegroundColor Cyan
    Write-Host "---------------------------------------" -ForegroundColor Gray

    $UnsignedCommits = git log --pretty=format:"%H %G? %s" -10 | Select-String " N "

    if ($UnsignedCommits) {
        Write-Host "❌ FAILED: Found unsigned commits:" -ForegroundColor Red
        $UnsignedCommits | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
        Write-Host "`nTo fix: Sign your commits with:" -ForegroundColor Yellow
        Write-Host "  git commit --amend --no-edit -S" -ForegroundColor White
        Write-Host "  git rebase --exec 'git commit --amend --no-edit -n -S' -i HEAD~5" -ForegroundColor White
        $FailedChecks += "Commit Signatures"
    } else {
        Write-Host "✅ PASSED: All recent commits are signed" -ForegroundColor Green
    }
}

# ============================================================================
# 2. GRADLE WRAPPER VALIDATION
# ============================================================================
Write-Host "`n[2/6] Validating Gradle Wrapper..." -ForegroundColor Cyan
Write-Host "---------------------------------------" -ForegroundColor Gray

# Check wrapper files exist
$WrapperFiles = @(
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties"
)

$MissingFiles = $WrapperFiles | Where-Object { -not (Test-Path $_) }
if ($MissingFiles) {
    Write-Host "❌ FAILED: Missing Gradle wrapper files:" -ForegroundColor Red
    $MissingFiles | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
    $FailedChecks += "Gradle Wrapper"
} else {
    Write-Host "✅ PASSED: Gradle wrapper files present" -ForegroundColor Green
}

# ============================================================================
# 3. CODE QUALITY CHECKS (spotless, checkstyle)
# ============================================================================
if (-not $QuickCheck) {
    Write-Host "`n[3/6] Running Code Quality Checks..." -ForegroundColor Cyan
    Write-Host "---------------------------------------" -ForegroundColor Gray

    Write-Host "  → Running spotlessCheck..." -ForegroundColor Gray
    $SpotlessResult = & .\gradlew spotlessCheck --console=plain 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ FAILED: Spotless formatting check failed" -ForegroundColor Red
        Write-Host "To fix: Run .\gradlew spotlessApply" -ForegroundColor Yellow
        $FailedChecks += "Spotless"
    } else {
        Write-Host "✅ PASSED: Code formatting is correct" -ForegroundColor Green
    }

    Write-Host "`n  → Running checkstyleMain..." -ForegroundColor Gray
    $CheckstyleResult = & .\gradlew checkstyleMain --console=plain 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ FAILED: Checkstyle violations found" -ForegroundColor Red
        $FailedChecks += "Checkstyle"
    } else {
        Write-Host "✅ PASSED: No checkstyle violations" -ForegroundColor Green
    }
} else {
    Write-Host "`n[3/6] SKIPPED: Code Quality Checks (--QuickCheck)" -ForegroundColor Yellow
}

# ============================================================================
# 4. COMPILATION CHECK
# ============================================================================
Write-Host "`n[4/6] Testing Compilation..." -ForegroundColor Cyan
Write-Host "---------------------------------------" -ForegroundColor Gray

$CompileCmd = ".\gradlew clean compileJava compileTestJava -x test --console=plain"
Write-Host "  → Running: $CompileCmd" -ForegroundColor Gray

$CompileResult = Invoke-Expression $CompileCmd 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ FAILED: Compilation errors found" -ForegroundColor Red
    Write-Host $CompileResult | Select-String "error:" | ForEach-Object { Write-Host "   $_" -ForegroundColor Red }
    $FailedChecks += "Compilation"
} else {
    Write-Host "✅ PASSED: Project compiles successfully" -ForegroundColor Green
}

# ============================================================================
# 5. UNIT TESTS
# ============================================================================
if (-not $SkipTests -and -not $QuickCheck) {
    Write-Host "`n[5/6] Running Unit Tests (Sample)..." -ForegroundColor Cyan
    Write-Host "---------------------------------------" -ForegroundColor Gray
    Write-Host "  → Running core tests..." -ForegroundColor Gray

    # Run a subset of fast tests to validate
    $TestCmd = ".\gradlew :fineract-core:test -x spotlessCheck -x checkstyle --console=plain --no-daemon"
    $TestResult = Invoke-Expression $TestCmd 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ FAILED: Unit tests failed" -ForegroundColor Red
        $FailedChecks += "Unit Tests"
    } else {
        Write-Host "✅ PASSED: Core tests passing" -ForegroundColor Green
    }
} else {
    Write-Host "`n[5/6] SKIPPED: Unit Tests (use -SkipTests:$false to run)" -ForegroundColor Yellow
}

# ============================================================================
# 6. BUILD CHECK (similar to build-postgresql.yml)
# ============================================================================
if ($FullBuild) {
    Write-Host "`n[6/6] Running Full Build..." -ForegroundColor Cyan
    Write-Host "---------------------------------------" -ForegroundColor Gray

    $BuildCmd = ".\gradlew :fineract-provider:clean :fineract-provider:build -x test -x cucumber --console=plain"
    Write-Host "  → Running: $BuildCmd" -ForegroundColor Gray

    $BuildResult = Invoke-Expression $BuildCmd 2>&1

    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ FAILED: Full build failed" -ForegroundColor Red
        $FailedChecks += "Full Build"
    } else {
        Write-Host "✅ PASSED: Full build successful" -ForegroundColor Green
    }
} else {
    Write-Host "`n[6/6] SKIPPED: Full Build (use -FullBuild to run)" -ForegroundColor Yellow
}

# ============================================================================
# SUMMARY
# ============================================================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "SUMMARY" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

if ($FailedChecks.Count -eq 0) {
    Write-Host "🎉 ALL CHECKS PASSED!" -ForegroundColor Green
    Write-Host "Your changes are ready to push to GitHub.`n" -ForegroundColor Green
    exit 0
} else {
    Write-Host "❌ FAILED CHECKS ($($FailedChecks.Count)):" -ForegroundColor Red
    $FailedChecks | ForEach-Object { Write-Host "   - $_" -ForegroundColor Red }
    Write-Host "`nFix these issues before pushing to GitHub.`n" -ForegroundColor Yellow
    exit 1
}

