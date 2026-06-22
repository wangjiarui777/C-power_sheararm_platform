param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DeviceCode = "DEV-001",
    [string]$AuthToken = $env:RUOYI_AUTH_TOKEN,
    [string]$CollectorToken = $env:SENSOR_COLLECTOR_TOKEN,
    [string]$WebSocketOrigin = "http://localhost",
    [switch]$SkipWebSocket
)

$ErrorActionPreference = "Stop"
if (-not $CollectorToken) {
    $CollectorToken = "dev-collector-token"
}

function Write-Step {
    param([string]$Message)
    Write-Host "[8CH] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "  OK  $Message" -ForegroundColor Green
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
        [hashtable]$Body = $null
    )
    $params = @{
        Method = $Method
        Uri = (Join-Url $BaseUrl $Path)
        TimeoutSec = 15
    }
    $headers = @{}
    if ($AuthToken) {
        $headers["Authorization"] = "Bearer $AuthToken"
    }
    if ($Path -like "*/upload") {
        $headers["X-Collector-Token"] = $CollectorToken
        $headers.Remove("Authorization")
    }
    if ($headers.Count -gt 0) {
        $params["Headers"] = $headers
    }
    if ($Body) {
        $params["ContentType"] = "application/json;charset=utf-8"
        $params["Body"] = ($Body | ConvertTo-Json -Depth 8)
    }

    $response = Invoke-RestMethod @params
    if ($null -ne $response.code -and [int]$response.code -ne 200) {
        throw "API $Method $Path failed: code=$($response.code), msg=$($response.msg)"
    }
    return $response
}

function Get-Array {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    return @($Value)
}

function Convert-ToWsUrl {
    param([string]$HttpUrl, [string]$Ticket)
    $root = $HttpUrl.TrimEnd("/")
    if ($root.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
        return "wss://" + $root.Substring(8) + "/ws/sensor?ticket=" + [Uri]::EscapeDataString($Ticket)
    }
    if ($root.StartsWith("http://", [System.StringComparison]::OrdinalIgnoreCase)) {
        return "ws://" + $root.Substring(7) + "/ws/sensor?ticket=" + [Uri]::EscapeDataString($Ticket)
    }
    return $root + "/ws/sensor?ticket=" + [Uri]::EscapeDataString($Ticket)
}

function Send-WsText {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [string]$Text
    )
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $segment = [System.ArraySegment[byte]]::new($bytes)
    $Socket.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
}

function Receive-WsText {
    param(
        [System.Net.WebSockets.ClientWebSocket]$Socket,
        [int]$TimeoutSec = 10
    )
    $buffer = New-Object byte[] 8192
    $segment = [System.ArraySegment[byte]]::new($buffer)
    $cts = [System.Threading.CancellationTokenSource]::new()
    $cts.CancelAfter([TimeSpan]::FromSeconds($TimeoutSec))
    try {
        $result = $Socket.ReceiveAsync($segment, $cts.Token).GetAwaiter().GetResult()
        if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
            return $null
        }
        return [System.Text.Encoding]::UTF8.GetString($buffer, 0, $result.Count)
    } finally {
        $cts.Dispose()
    }
}

Write-Step "Eight-channel smoke test target: $BaseUrl"
$runId = Get-Date -Format "yyyyMMddHHmmss"
$remark = "Created by setup/eight-channel-smoke-test.ps1 $runId"
$ws = $null

if (-not $SkipWebSocket) {
    if (-not $AuthToken) {
        throw "RUOYI_AUTH_TOKEN or -AuthToken is required for the WebSocket smoke test."
    }
    Write-Step "Opening WebSocket subscription"
    $ticketResponse = Invoke-Api -Method Post -Path "/sensor/ws-ticket"
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ws.Options.SetRequestHeader("Origin", $WebSocketOrigin)
    $ws.ConnectAsync([System.Uri](Convert-ToWsUrl $BaseUrl $ticketResponse.data.ticket), [System.Threading.CancellationToken]::None).GetAwaiter().GetResult() | Out-Null
    Send-WsText -Socket $ws -Text '{"type":"subscribe","channel":"overview"}'
    Write-Ok "Subscribed to overview channel"
}

Write-Step "Uploading one vibration sample for each channel"
for ($channel = 1; $channel -le 8; $channel++) {
    $body = @{
        deviceCode = $DeviceCode
        channelId = $channel
        temperatureValue = [Math]::Round(35.0 + $channel * 0.4, 2)
        vibrationValue = [Math]::Round(0.40 + $channel * 0.08, 4)
        accelerationValue = [Math]::Round(0.40 + $channel * 0.08, 4)
        sampleTime = (Get-Date).AddSeconds($channel).ToString("yyyy-MM-dd HH:mm:ss")
        remark = $remark
    }
    Invoke-Api -Method Post -Path "/sensor/vibration-data/upload" -Body $body | Out-Null
}
Write-Ok "Uploaded 8 channel samples"

Write-Step "Checking recent vibration records"
$recent = Invoke-Api -Method Get -Path "/sensor/vibration-data/recent"
$rows = Get-Array $recent.data
$createdRows = @($rows | Where-Object { $_.remark -eq $remark })
Assert-True ($createdRows.Count -ge 8) "Recent endpoint returns uploaded samples"
for ($channel = 1; $channel -le 8; $channel++) {
    $row = $createdRows | Where-Object { [int]$_.channelId -eq $channel } | Select-Object -First 1
    Assert-True ($null -ne $row) "Channel $channel is present in recent data"
    Assert-True ($null -ne $row.vibrationValue) "Channel $channel returns vibration value"
    Assert-True ($null -ne $row.temperatureValue) "Channel $channel returns temperature value"
}

Write-Step "Checking industrial multi-channel overview"
$overview = Invoke-Api -Method Get -Path "/sensor/vibration-data/multi-channel/overview?deviceCode=$DeviceCode&windowMinutes=30"
$overviewChannels = Get-Array $overview.data.channels
Assert-True ($overviewChannels.Count -eq 8) "Overview returns exactly 8 channels"
Assert-True ($null -ne $overview.data.summary) "Overview returns summary block"
Assert-True ($overview.data.summary.channelCount -eq 8) "Overview summary reports 8 channels"
for ($channel = 1; $channel -le 8; $channel++) {
    $overviewChannel = $overviewChannels | Where-Object { [int]$_.channelId -eq $channel } | Select-Object -First 1
    Assert-True ($null -ne $overviewChannel) "Overview includes channel $channel"
    Assert-True ($null -ne $overviewChannel.channelName) "Overview channel $channel has industrial point name"
}

Write-Step "Checking channel analysis interface"
$analysis = Invoke-Api -Method Get -Path "/sensor/vibration-data/multi-channel/1/analysis?deviceCode=$DeviceCode"
Assert-True ($analysis.data.channelId -eq 1) "Analysis returns selected channel"
Assert-True ((Get-Array $analysis.data.trend).Count -gt 0) "Analysis returns recent trend"
Assert-True ($null -ne $analysis.data.thresholds.highLimit) "Analysis returns threshold block"
Assert-True ($null -ne $analysis.data.dataStatus) "Analysis returns data status"

if ($ws) {
    Write-Step "Checking WebSocket incremental update"
    $probeChannel = 8
    $probeBody = @{
        deviceCode = $DeviceCode
        channelId = $probeChannel
        temperatureValue = 41.88
        vibrationValue = 1.8800
        accelerationValue = 1.8800
        sampleTime = (Get-Date).AddSeconds(12).ToString("yyyy-MM-dd HH:mm:ss")
        remark = "$remark websocket-probe"
    }
    Invoke-Api -Method Post -Path "/sensor/vibration-data/upload" -Body $probeBody | Out-Null

    $matched = $false
    $deadline = (Get-Date).AddSeconds(12)
    while ((Get-Date) -lt $deadline -and -not $matched) {
        try {
            $text = Receive-WsText -Socket $ws -TimeoutSec 4
            if ($text) {
                $msg = $text | ConvertFrom-Json
                if ([int]$msg.channelId -eq $probeChannel -and $null -ne $msg.vibrationValue) {
                    $matched = $true
                }
            }
        } catch {
            # Ignore timeout and keep waiting until deadline.
        }
    }
    Assert-True $matched "WebSocket pushes channel-scoped vibration update"
    $ws.Dispose()
}

Write-Step "Eight-channel smoke test complete"
