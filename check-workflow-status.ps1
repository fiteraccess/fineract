# Quick workflow status checker
# Run this script to check the current status of GitHub Actions workflows

Write-Host "`n=== GitHub Actions Workflow Status ===" -ForegroundColor Cyan
Write-Host "Branch: fix/mandatory-client-fields" -ForegroundColor Yellow
Write-Host "Latest Commit: a479a2d19`n" -ForegroundColor Yellow

# Get the latest 5 workflow runs
$runs = gh run list --branch fix/mandatory-client-fields --limit 5 --json status,conclusion,name,createdAt,databaseId,event | ConvertFrom-Json

Write-Host "Latest Workflow Runs:" -ForegroundColor Green
Write-Host ("=" * 100) -ForegroundColor Gray

foreach ($run in $runs) {
    $age = (Get-Date) - [datetime]$run.createdAt
    $ageStr = if ($age.TotalHours -ge 1) {
        "$([math]::Floor($age.TotalHours))h $($age.Minutes)m ago"
    } elseif ($age.TotalMinutes -ge 1) {
        "$([math]::Floor($age.TotalMinutes))m $($age.Seconds)s ago"
    } else {
        "$([math]::Floor($age.TotalSeconds))s ago"
    }

    $statusIcon = switch ($run.status) {
        "in_progress" { "⏳" }
        "queued" { "⌛" }
        "completed" {
            switch ($run.conclusion) {
                "success" { "✅" }
                "failure" { "❌" }
                "cancelled" { "🚫" }
                default { "⚪" }
            }
        }
        default { "❓" }
    }

    $statusColor = switch ($run.status) {
        "in_progress" { "Yellow" }
        "queued" { "Cyan" }
        "completed" {
            switch ($run.conclusion) {
                "success" { "Green" }
                "failure" { "Red" }
                default { "Gray" }
            }
        }
        default { "Gray" }
    }

    Write-Host "$statusIcon " -NoNewline -ForegroundColor $statusColor
    Write-Host "[ID: $($run.databaseId)] " -NoNewline -ForegroundColor Gray
    Write-Host $run.name -NoNewline -ForegroundColor White
    Write-Host " - " -NoNewline
    Write-Host $run.status -NoNewline -ForegroundColor $statusColor
    if ($run.conclusion) {
        Write-Host " ($($run.conclusion))" -NoNewline -ForegroundColor $statusColor
    }
    Write-Host " - $ageStr" -ForegroundColor Gray
}

Write-Host ("`n" + "=" * 100) -ForegroundColor Gray

# Count statuses
$inProgress = ($runs | Where-Object { $_.status -eq "in_progress" }).Count
$completed = ($runs | Where-Object { $_.status -eq "completed" }).Count
$queued = ($runs | Where-Object { $_.status -eq "queued" }).Count
$success = ($runs | Where-Object { $_.conclusion -eq "success" }).Count
$failure = ($runs | Where-Object { $_.conclusion -eq "failure" }).Count

Write-Host "`nSummary:" -ForegroundColor Cyan
Write-Host "  In Progress: $inProgress" -ForegroundColor Yellow
Write-Host "  Queued: $queued" -ForegroundColor Cyan
Write-Host "  Completed: $completed (✅ Success: $success, ❌ Failed: $failure)" -ForegroundColor Gray

if ($inProgress -gt 0) {
    Write-Host "`n💡 Tip: Run this script again in a few minutes to check progress." -ForegroundColor Yellow
    Write-Host "   Or watch live at: https://github.com/fiteraccess/fineract/actions" -ForegroundColor Gray
}

Write-Host ""

