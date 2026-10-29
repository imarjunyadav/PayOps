# PayOps local development launcher
# Usage: .\start-dev.ps1
# Requires .env file with PAYOPS_DB_URL, PAYOPS_DB_USERNAME, PAYOPS_DB_PASSWORD, PAYOPS_JWT_SECRET

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env file not found. Copy .env.example to .env and fill in your values."
    exit 1
}

# Load .env
Get-Content $envFile | Where-Object { $_ -match "^\s*[^#]" -and $_ -match "=" } | ForEach-Object {
    $key, $value = $_ -split "=", 2
    [System.Environment]::SetEnvironmentVariable($key.Trim(), $value.Trim(), "Process")
}

# Validate required vars
foreach ($var in @("PAYOPS_DB_URL", "PAYOPS_DB_USERNAME", "PAYOPS_DB_PASSWORD", "PAYOPS_JWT_SECRET")) {
    if (-not [System.Environment]::GetEnvironmentVariable($var, "Process")) {
        Write-Error "Required environment variable '$var' is not set in .env"
        exit 1
    }
}

$secret = [System.Environment]::GetEnvironmentVariable("PAYOPS_JWT_SECRET", "Process")
if ($secret.Length -lt 64) {
    Write-Error "PAYOPS_JWT_SECRET must be at least 64 characters. Current length: $($secret.Length)"
    exit 1
}

Write-Host "Starting Fake Bank on port 8081..." -ForegroundColor Cyan
$fakeBankJob = Start-Job -ScriptBlock {
    param($dir, $env)
    $env.GetEnumerator() | ForEach-Object {
        [System.Environment]::SetEnvironmentVariable($_.Key, $_.Value, "Process")
    }
    Set-Location $dir
    & .\mvnw.cmd spring-boot:run -pl fake-bank 2>&1
} -ArgumentList $PSScriptRoot, ([System.Environment]::GetEnvironmentVariables("Process") | ForEach-Object { $_ })

Write-Host "Waiting 10s for Fake Bank to start..." -ForegroundColor Yellow
Start-Sleep -Seconds 10

Write-Host "Starting PayOps main app on port 8080..." -ForegroundColor Cyan
Write-Host ""
Write-Host "  Swagger UI  -> http://localhost:8080/swagger-ui/index.html" -ForegroundColor Green
Write-Host "  API base    -> http://localhost:8080/api" -ForegroundColor Green
Write-Host ""
Write-Host "Press Ctrl+C to stop." -ForegroundColor Yellow

& .\mvnw.cmd spring-boot:run

Remove-Job $fakeBankJob -Force
