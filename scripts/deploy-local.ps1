
$ErrorActionPreference = "Stop"

# Ivorovi Local Deployment Script
$ProjectRoot = "C:\Development\IVOROVI-AssessmentPlatform"

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host " IVOROVI - LOCAL DEPLOYMENT" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan

Set-Location $ProjectRoot

# 1. Check required tools
Write-Host "[1/7] Checking required tools..." -ForegroundColor Yellow

$RequiredCommands = @("git", "java", "pnpm", "docker")

foreach ($Command in $RequiredCommands) {
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Command"
    }
}

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Docker Desktop is not running. Start Docker Desktop and retry."
}

# 2. Check Git branch and local changes
Write-Host "`n[2/7] Checking Git repository..." -ForegroundColor Yellow

$Branch = git branch --show-current
if ($Branch -ne "main") {
    throw "You are on branch '$Branch'. Switch to main before deploying."
}

$GitChanges = @(git status --porcelain --untracked-files=all)

$UnexpectedChanges = @(
    $GitChanges | Where-Object {
        $_ -notmatch '^\?\? scripts/deploy-local\.ps1$'
    }
)

if ($UnexpectedChanges.Count -gt 0) {
    $UnexpectedChanges | ForEach-Object { Write-Host $_ }
    throw "Local changes detected. Commit or safely back them up before deploying."
}

# 3. Pull latest code
Write-Host "`n[3/7] Pulling latest code from GitHub..." -ForegroundColor Yellow

git pull --ff-only origin main
if ($LASTEXITCODE -ne 0) {
    throw "Git pull failed."
}

# 4. Build backend
Write-Host "`n[4/7] Building Spring Boot backend..." -ForegroundColor Yellow

.\gradlew.bat :backend:api:bootJar
if ($LASTEXITCODE -ne 0) {
    throw "Backend build failed."
}

# 5. Install dependencies and build frontend
Write-Host "`n[5/7] Building frontend..." -ForegroundColor Yellow

Push-Location "$ProjectRoot\frontend"
try {
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend dependency installation failed."
    }

    pnpm build
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend build failed."
    }
}
finally {
    Pop-Location
}

# 6. Rebuild and restart Docker services
Write-Host "`n[6/7] Rebuilding and restarting Docker services..." -ForegroundColor Yellow

Push-Location "$ProjectRoot\deploy\compose"
try {
    docker compose --profile app up -d --build
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose deployment failed."
    }

    # 7. Display status
    Write-Host "`n[7/7] Deployment status..." -ForegroundColor Yellow
    docker compose --profile app ps
}
finally {
    Pop-Location
}

Write-Host "`n========================================" -ForegroundColor Green
Write-Host " DEPLOYMENT COMMANDS COMPLETED" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green

Write-Host "`nLocal application URLs:" -ForegroundColor Cyan
Write-Host "Web Portal : http://localhost:8081"
Write-Host "Exam Portal: http://localhost:8082"
Write-Host "API Health : http://localhost:8080/actuator/health"
Write-Host "Keycloak   : http://localhost:8180"
