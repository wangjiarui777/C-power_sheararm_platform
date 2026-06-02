Set-Location $PSScriptRoot

$ErrorActionPreference = 'Stop'

function Start-ProcessWindow {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,
        [Parameter(Mandatory = $true)]
        [string]$Command
    )

    Start-Process -FilePath 'powershell' -ArgumentList @(
        '-NoExit',
        '-ExecutionPolicy', 'Bypass',
        '-Command', "Set-Location -LiteralPath '$WorkingDirectory'; $Command"
    ) -WorkingDirectory $WorkingDirectory | Out-Null

    Write-Host "[OK] $Name started" -ForegroundColor Green
}

function Test-CommandExists {
    param([Parameter(Mandatory = $true)][string]$CommandName)
    return [bool](Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Get-PortProcessId {
    param([Parameter(Mandatory = $true)][int]$Port)
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $conn) {
        return $conn.OwningProcess
    }
    return $null
}

function Stop-PortProcess {
    param([Parameter(Mandatory = $true)][int]$Port)
    $processId = Get-PortProcessId -Port $Port
    if ($null -eq $processId) {
        return $false
    }
    try {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Write-Host "[OK] Stopped process $processId on port $Port" -ForegroundColor Yellow
        Start-Sleep -Seconds 2
        return $true
    } catch {
        Write-Host "[WARN] Failed to stop process $processId on port $Port : $($_.Exception.Message)" -ForegroundColor Yellow
        return $false
    }
}

function Ensure-PortFree {
    param([Parameter(Mandatory = $true)][int]$Port)
    while (Get-PortProcessId -Port $Port) {
        if (-not (Stop-PortProcess -Port $Port)) {
            break
        }
    }
}

Write-Host '[1/5] Checking required tools...' -ForegroundColor Cyan
foreach ($tool in @('java', 'mvn', 'node', 'npm', 'python')) {
    if (-not (Test-CommandExists -CommandName $tool)) {
        Write-Host "[WARN] $tool not found in PATH" -ForegroundColor Yellow
    }
}

Write-Host '[2/5] Releasing occupied ports if needed...' -ForegroundColor Cyan
Ensure-PortFree -Port 8080
Ensure-PortFree -Port 9528
Ensure-PortFree -Port 5000

Write-Host '[3/5] Starting backend (ruoyi-admin)...' -ForegroundColor Cyan
Start-ProcessWindow -Name 'Backend' -WorkingDirectory $PSScriptRoot -Command 'cmd /c "mvn -pl ruoyi-admin -am spring-boot:run -Dspring-boot.run.profiles=dev"'

Write-Host '[4/5] Starting frontend (ruoyi-ui)...' -ForegroundColor Cyan
Start-ProcessWindow -Name 'Frontend' -WorkingDirectory (Join-Path $PSScriptRoot 'ruoyi-ui') -Command 'cmd /c "npm run dev"'

Write-Host '[5/5] Starting Python inference service...' -ForegroundColor Cyan
Start-ProcessWindow -Name 'Python inference service' -WorkingDirectory $PSScriptRoot -Command 'python inference_service.py'

Write-Host ''
Write-Host 'All requested services have been launched in separate windows.' -ForegroundColor Green
Write-Host 'Starting MAT receiver in a separate window now...' -ForegroundColor Green
Start-ProcessWindow -Name 'MAT receiver' -WorkingDirectory (Join-Path $PSScriptRoot 'get') -Command 'cmd /c "javac CwruMatReceiver.java && java CwruMatReceiver 8888 got"'

Write-Host ''
Write-Host 'If any service fails, check the corresponding window output.' -ForegroundColor Green
