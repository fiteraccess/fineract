#!/usr/bin/env pwsh
# Quick Fix Script for GitHub Actions Failures

$ErrorActionPreference = "Stop"

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "GITHUB ACTIONS FAILURE FIX SCRIPT" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

# ============================================================================
# ISSUE #1: UNSIGNED COMMITS (Main Failure)
# ============================================================================
Write-Host "[1] Checking Commit Signatures..." -ForegroundColor Yellow
Write-Host "---------------------------------------`n" -ForegroundColor Gray

$CommitLog = git log --pretty=format:"%h %G? %s" -10
$UnsignedCount = ($CommitLog | Select-String " N ").Count

Write-Host "Recent commits:" -ForegroundColor Gray
$CommitLog | ForEach-Object {
    if ($_ -match " N ") {
        Write-Host "  ❌ $_" -ForegroundColor Red
    } elseif ($_ -match " E ") {
        Write-Host "  ⚠️  $_" -ForegroundColor Yellow
    } else {
        Write-Host "  ✅ $_" -ForegroundColor Green
    }
}

if ($UnsignedCount -gt 0) {
    Write-Host "`n⚠️  Found $UnsignedCount unsigned commits!" -ForegroundColor Red
    Write-Host "`nThis is the #1 reason for GitHub Actions failures.`n" -ForegroundColor Yellow

    # Check if GPG is configured
    $GPGKey = git config user.signingkey

    if (-not $GPGKey) {
        Write-Host "❌ GPG signing is not configured!" -ForegroundColor Red
        Write-Host "`nTo setup GPG signing:" -ForegroundColor Yellow
        Write-Host "  1. Generate a GPG key:" -ForegroundColor White
        Write-Host "     gpg --full-generate-key" -ForegroundColor Gray
        Write-Host "`n  2. Get your key ID:" -ForegroundColor White
        Write-Host "     gpg --list-secret-keys --keyid-format=long" -ForegroundColor Gray
        Write-Host "`n  3. Configure Git:" -ForegroundColor White
        Write-Host "     git config --global user.signingkey YOUR_KEY_ID" -ForegroundColor Gray
        Write-Host "     git config --global commit.gpgsign true" -ForegroundColor Gray
        Write-Host "`n  4. Add GPG key to GitHub:" -ForegroundColor White
        Write-Host "     gpg --armor --export YOUR_KEY_ID" -ForegroundColor Gray
        Write-Host "     (Copy output to GitHub Settings → SSH and GPG keys)`n" -ForegroundColor Gray

        $SetupNow = Read-Host "Do you want to setup GPG signing now? (y/n)"

        if ($SetupNow -eq 'y') {
            Write-Host "`nOpening GPG setup guide..." -ForegroundColor Cyan
            Start-Process "https://docs.github.com/en/authentication/managing-commit-signature-verification/generating-a-new-gpg-key"
            Write-Host "After setup, re-run this script to sign your commits.`n" -ForegroundColor Yellow
            exit 0
        }
    } else {
        Write-Host "✅ GPG key configured: $GPGKey`n" -ForegroundColor Green

        # Offer to sign commits
        Write-Host "OPTIONS TO FIX:" -ForegroundColor Cyan
        Write-Host "  1. Sign last commit only (safe)" -ForegroundColor White
        Write-Host "  2. Sign last 5 commits (recommended)" -ForegroundColor White
        Write-Host "  3. Sign last 10 commits (all unsigned)" -ForegroundColor White
        Write-Host "  4. Skip for now`n" -ForegroundColor Gray

        $Choice = Read-Host "Choose option (1-4)"

        switch ($Choice) {
            "1" {
                Write-Host "`nSigning last commit..." -ForegroundColor Cyan
                git commit --amend --no-edit -S
                Write-Host "✅ Last commit signed!" -ForegroundColor Green
                Write-Host "⚠️  You'll need to force push: git push --force-with-lease`n" -ForegroundColor Yellow
            }
            "2" {
                Write-Host "`nSigning last 5 commits..." -ForegroundColor Cyan
                Write-Host "This will rewrite commit history. Backup recommended!`n" -ForegroundColor Yellow
                $Confirm = Read-Host "Continue? (yes/no)"

                if ($Confirm -eq "yes") {
                    git rebase --exec 'git commit --amend --no-edit -n -S' -i HEAD~5
                    Write-Host "✅ Last 5 commits signed!" -ForegroundColor Green
                    Write-Host "⚠️  You'll need to force push: git push --force-with-lease`n" -ForegroundColor Yellow
                }
            }
            "3" {
                Write-Host "`nSigning last 10 commits..." -ForegroundColor Cyan
                Write-Host "This will rewrite commit history. Backup recommended!`n" -ForegroundColor Yellow
                $Confirm = Read-Host "Continue? (yes/no)"

                if ($Confirm -eq "yes") {
                    git rebase --exec 'git commit --amend --no-edit -n -S' -i HEAD~10
                    Write-Host "✅ Last 10 commits signed!" -ForegroundColor Green
                    Write-Host "⚠️  You'll need to force push: git push --force-with-lease`n" -ForegroundColor Yellow
                }
            }
            "4" {
                Write-Host "`nSkipping commit signing...`n" -ForegroundColor Gray
            }
            default {
                Write-Host "`nInvalid choice. Skipping...`n" -ForegroundColor Red
            }
        }
    }
}

# ============================================================================
# ISSUE #2: CODE FORMATTING
# ============================================================================
Write-Host "`n[2] Checking Code Formatting..." -ForegroundColor Yellow
Write-Host "---------------------------------------`n" -ForegroundColor Gray

Write-Host "Running spotlessCheck..." -ForegroundColor Gray
$SpotlessCheck = & .\gradlew spotlessCheck --console=plain 2>&1 | Out-String

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Code formatting issues found!" -ForegroundColor Red

    $Fix = Read-Host "`nRun spotlessApply to fix? (y/n)"

    if ($Fix -eq 'y') {
        Write-Host "`nApplying spotless formatting..." -ForegroundColor Cyan
        & .\gradlew spotlessApply --console=plain

        if ($LASTEXITCODE -eq 0) {
            Write-Host "✅ Code formatted successfully!" -ForegroundColor Green

            $Commit = Read-Host "`nCommit formatting changes? (y/n)"
            if ($Commit -eq 'y') {
                git add .

                # Check if GPG signing is enabled
                $SignCommit = git config commit.gpgsign
                if ($SignCommit -eq "true") {
                    git commit -S -m "style: apply spotless formatting"
                } else {
                    git commit -m "style: apply spotless formatting"
                }

                Write-Host "✅ Formatting changes committed!`n" -ForegroundColor Green
            }
        }
    }
} else {
    Write-Host "✅ Code formatting is correct!`n" -ForegroundColor Green
}

# ============================================================================
# ISSUE #3: GRADLE WRAPPER
# ============================================================================
Write-Host "`n[3] Checking Gradle Wrapper..." -ForegroundColor Yellow
Write-Host "---------------------------------------`n" -ForegroundColor Gray

$WrapperIssues = @()

# Check if wrapper files are modified
$ModifiedWrapper = git status --porcelain | Select-String "gradle/wrapper|gradlew"

if ($ModifiedWrapper) {
    Write-Host "⚠️  Gradle wrapper files are modified:" -ForegroundColor Yellow
    $ModifiedWrapper | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }

    Write-Host "`nThese changes should be committed or reverted.`n" -ForegroundColor Yellow
} else {
    Write-Host "✅ Gradle wrapper files are clean`n" -ForegroundColor Green
}

# ============================================================================
# SUMMARY
# ============================================================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "NEXT STEPS" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

Write-Host "1. If you signed commits:" -ForegroundColor Yellow
Write-Host "   git push --force-with-lease`n" -ForegroundColor White

Write-Host "2. To test locally before pushing:" -ForegroundColor Yellow
Write-Host "   .\test-workflows-locally.ps1 -QuickCheck`n" -ForegroundColor White

Write-Host "3. To run full validation:" -ForegroundColor Yellow
Write-Host "   .\test-workflows-locally.ps1 -FullBuild`n" -ForegroundColor White

Write-Host "4. For more help:" -ForegroundColor Yellow
Write-Host "   See FIX-GITHUB-ACTIONS.md`n" -ForegroundColor White

Write-Host "========================================`n" -ForegroundColor Cyan

