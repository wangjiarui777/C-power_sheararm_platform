Set-Location "$PSScriptRoot"

Write-Host "[1/2] Cleaning and building all required modules..." -ForegroundColor Cyan
cmd /c "mvn clean install -DskipTests"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Module build failed, aborting startup." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "[2/2] Starting ruoyi-admin with active dev profile..." -ForegroundColor Cyan
Set-Location "$PSScriptRoot\ruoyi-admin"
cmd /c "mvn -U spring-boot:run -Dspring-boot.run.profiles=dev"
