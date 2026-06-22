param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [string]$Token = "",
    [switch]$Mutating
)

$ErrorActionPreference = "Stop"
$script:Warnings = New-Object System.Collections.Generic.List[string]
if ($Token -and $Token.StartsWith("Bearer ", [System.StringComparison]::OrdinalIgnoreCase)) {
    $Token = $Token.Substring(7).Trim()
}

function Write-Step {
    param([string]$Message)
    Write-Host "[PHM] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  OK  $Message" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Message)
    $script:Warnings.Add($Message) | Out-Null
    Write-Host "  WARN $Message" -ForegroundColor Yellow
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
    Write-Ok $Message
}

function Join-Url {
    param(
        [string]$Root,
        [string]$Path
    )
    return $Root.TrimEnd("/") + "/" + $Path.TrimStart("/")
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Body = $null,
        [hashtable]$Query = $null
    )
    $uri = Join-Url $BaseUrl $Path
    if ($Query) {
        $pairs = @()
        foreach ($key in $Query.Keys) {
            if ($null -ne $Query[$key] -and "$($Query[$key])" -ne "") {
                $pairs += [System.Uri]::EscapeDataString($key) + "=" + [System.Uri]::EscapeDataString([string]$Query[$key])
            }
        }
        if ($pairs.Count -gt 0) {
            $uri = $uri + "?" + ($pairs -join "&")
        }
    }

    $headers = @{}
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $params = @{
        Method = $Method
        Uri = $uri
        Headers = $headers
        TimeoutSec = 15
    }
    if ($Body) {
        $params["ContentType"] = "application/json;charset=utf-8"
        $params["Body"] = ($Body | ConvertTo-Json -Depth 12)
    }

    $response = Invoke-RestMethod @params
    if ($null -ne $response.code -and [int]$response.code -ne 200) {
        throw "API $Method $Path failed: code=$($response.code), msg=$($response.msg)"
    }
    return $response
}

function Ensure-Token {
    if ($Token) {
        Write-Ok "Using provided token"
        return
    }

    Write-Step "No token provided; trying password login"
    $captcha = Invoke-RestMethod -Method Get -Uri (Join-Url $BaseUrl "/captchaImage") -TimeoutSec 15
    if ($captcha.captchaEnabled -eq $true) {
        throw "Captcha is enabled. Run this script with -Token '<token-from-browser>' or temporarily disable sys.account.captchaEnabled for smoke testing."
    }

    $loginBody = @{
        username = $Username
        password = $Password
        code = ""
        uuid = $captcha.uuid
    }
    $login = Invoke-RestMethod -Method Post -Uri (Join-Url $BaseUrl "/login") -ContentType "application/json;charset=utf-8" -Body ($loginBody | ConvertTo-Json) -TimeoutSec 15
    if ($null -ne $login.code -and [int]$login.code -ne 200) {
        throw "Login failed: code=$($login.code), msg=$($login.msg)"
    }
    $script:Token = $login.token
    Assert-True ([bool]$script:Token) "Login returned token"
}

function Get-Array {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    return @($Value)
}

Write-Step "PHM smoke test target: $BaseUrl"
Ensure-Token

Write-Step "Checking device cluster"
$cluster = Invoke-Api -Method Get -Path "/phm/devices/cluster"
$devices = Get-Array $cluster.data.devices
Assert-True ($devices.Count -gt 0) "Device cluster has at least one device"
Assert-True ($null -ne $cluster.data.stats) "Device cluster returns statistics"
Assert-True ($null -ne $cluster.data.goodRateTrend) "Device cluster returns good-rate trend"
$device = $devices | Where-Object { $_.deviceCode -eq "DEV-001" } | Select-Object -First 1
if (-not $device) {
    $device = $devices[0]
    Write-Warn "Seed device DEV-001 was not found; using $($device.deviceCode) for detail checks."
}

Write-Step "Checking machine brain for $($device.deviceCode)"
$brain = Invoke-Api -Method Get -Path "/phm/devices/$($device.id)/brain"
Assert-True ($brain.data.device.id -eq $device.id) "Machine brain returns selected device"
Assert-True ($null -ne $brain.data.points) "Machine brain returns measure point block"
Assert-True ($null -ne $brain.data.systemConfig) "Machine brain returns system config block"

Write-Step "Checking config interfaces"
$points = Invoke-Api -Method Get -Path "/phm/points" -Query @{ deviceId = $device.id }
$features = Invoke-Api -Method Get -Path "/phm/features"
$rules = Invoke-Api -Method Get -Path "/phm/alarm-rules"
$configs = Invoke-Api -Method Get -Path "/phm/system-config"
if ($device.deviceCode -eq "DEV-001") {
    Assert-True ((Get-Array $points.data).Count -gt 0) "Seed device DEV-001 has measure points"
} else {
    Assert-True ((Get-Array $points.data).Count -ge 0) "Measure point list endpoint is available"
}
Assert-True ((Get-Array $features.data).Count -gt 0) "Feature config list has data"
Assert-True ((Get-Array $rules.data).Count -gt 0) "Alarm rule list has data"
Assert-True ((Get-Array $configs.data).Count -gt 0) "System config list has data"

Write-Step "Checking alarm center"
$alarms = Invoke-Api -Method Get -Path "/phm/alarms"
$alarmRows = Get-Array $alarms.data
Assert-True ($alarmRows.Count -gt 0) "Alarm list has at least one alarm"
$firstAlarm = $alarmRows[0]
$alarmDetail = Invoke-Api -Method Get -Path "/phm/alarms/$($firstAlarm.id)"
Assert-True ($alarmDetail.data.alarm.id -eq $firstAlarm.id) "Alarm detail returns selected alarm"
Assert-True ($null -ne $alarmDetail.data.handleRecords) "Alarm detail returns handle records"
Assert-True ($null -ne $alarmDetail.data.events) "Alarm detail returns related device events"

$seedHandled = $alarmRows | Where-Object { $_.alarmNo -eq "ALM202605100001" } | Select-Object -First 1
if ($seedHandled) {
    $seedDetail = Invoke-Api -Method Get -Path "/phm/alarms/$($seedHandled.id)"
    Assert-True ($seedHandled.status -eq "handled") "Seed alarm ALM202605100001 is handled"
    Assert-True (@(Get-Array $seedDetail.data.handleRecords).Count -gt 0) "Seed handled alarm has handle record"
} else {
    Write-Warn "Seed alarm ALM202605100001 was not found. If this is a fresh deployment, import sql/phm_platform.sql."
}

Write-Step "Checking reports"
$realtime = Invoke-Api -Method Get -Path "/phm/reports/realtime"
$history = Invoke-Api -Method Get -Path "/phm/reports/history"
$reports = Invoke-Api -Method Get -Path "/phm/reports/service"
Assert-True ($null -ne $realtime.data) "Realtime report endpoint returns data"
Assert-True ($null -ne $history.data.summary) "History report endpoint returns summary"
Assert-True ($null -ne $reports.data) "Service report endpoint returns data"

if ($Mutating) {
    Write-Step "Running mutating checks"
    $eventContent = "PHM smoke test event " + (Get-Date -Format "yyyyMMddHHmmss")
    $eventBody = @{
        deviceId = $device.id
        deviceCode = $device.deviceCode
        eventTime = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        eventType = "other"
        eventContent = $eventContent
        remark = "Created by setup/phm-smoke-test.ps1"
    }
    Invoke-Api -Method Post -Path "/phm/device-events" -Body $eventBody | Out-Null
    $events = Invoke-Api -Method Get -Path "/phm/device-events" -Query @{ deviceId = $device.id }
    $createdEvent = Get-Array $events.data | Where-Object { $_.eventContent -eq $eventContent } | Select-Object -First 1
    Assert-True ($null -ne $createdEvent) "Device event can be created"
    Invoke-Api -Method Delete -Path "/phm/device-events/$($createdEvent.id)" | Out-Null
    Write-Ok "Device event can be deleted"

    $reportName = "PHM smoke report " + (Get-Date -Format "yyyyMMddHHmmss")
    $reportBody = @{
        reportType = "run"
        fileName = $reportName
        fileUrl = "/profile/upload/phm-smoke-test.pdf"
        fileExt = "pdf"
        remark = "Created by setup/phm-smoke-test.ps1"
    }
    Invoke-Api -Method Post -Path "/phm/reports/service" -Body $reportBody | Out-Null
    $reportRows = Invoke-Api -Method Get -Path "/phm/reports/service" -Query @{ reportType = "run" }
    $createdReport = Get-Array $reportRows.data | Where-Object { $_.fileName -eq $reportName } | Select-Object -First 1
    Assert-True ($null -ne $createdReport) "Service report can be created"
    Invoke-Api -Method Delete -Path "/phm/attachments/$($createdReport.id)" | Out-Null
    Write-Ok "Service report can be deleted"
}

Write-Step "Smoke test complete"
if ($script:Warnings.Count -gt 0) {
    Write-Host "Warnings:" -ForegroundColor Yellow
    foreach ($warning in $script:Warnings) {
        Write-Host "  - $warning" -ForegroundColor Yellow
    }
}
