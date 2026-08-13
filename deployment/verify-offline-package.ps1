param(
    [Parameter(Mandatory = $true)][string]$PackagePath,
    [Parameter(Mandatory = $true)][string]$SignaturePath,
    [Parameter(Mandatory = $true)][string]$ExpectedCertificateThumbprint,
    [string]$DestinationPath = ""
)
$ErrorActionPreference = "Stop"
$package = (Resolve-Path -LiteralPath $PackagePath).Path
$signature = (Resolve-Path -LiteralPath $SignaturePath).Path
if (-not $package.EndsWith('.zip', [StringComparison]::OrdinalIgnoreCase)) { throw '离线包必须是 ZIP 文件' }

$content = [System.Security.Cryptography.Pkcs.ContentInfo]::new([System.IO.File]::ReadAllBytes($package))
$signedCms = [System.Security.Cryptography.Pkcs.SignedCms]::new($content, $true)
$signedCms.Decode([System.IO.File]::ReadAllBytes($signature))
$signedCms.CheckSignature($true)
if ($signedCms.SignerInfos.Count -ne 1) { throw '离线包必须且只能有一个签名者' }
$certificate = $signedCms.SignerInfos[0].Certificate
$actual = $certificate.Thumbprint.Replace(" ", "").ToUpperInvariant()
$expected = $ExpectedCertificateThumbprint.Replace(" ", "").ToUpperInvariant()
if ($actual -ne $expected) { throw "签名证书指纹不匹配，期望 $expected，实际 $actual" }
if ([DateTime]::UtcNow -lt $certificate.NotBefore.ToUniversalTime() -or
    [DateTime]::UtcNow -gt $certificate.NotAfter.ToUniversalTime()) { throw '签名证书不在有效期内' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($package)
try {
    if ($archive.Entries.Count -gt 10000) { throw 'ZIP 条目数超过 10000 上限' }
    $entries = @{}
    [Int64]$expandedSize = 0
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ([string]::IsNullOrWhiteSpace($name) -or $name.StartsWith('/') -or
            $name.Contains(':') -or $name.Split('/') -contains '..') { throw "检测到非法 ZIP 路径: $name" }
        if ($entries.ContainsKey($name)) { throw "检测到重复 ZIP 条目: $name" }
        $entries[$name] = $entry
        $expandedSize += $entry.Length
        if ($entry.Length -gt 2GB) { throw "单个条目超过 2GB 上限: $name" }
    }
    if ($expandedSize -gt 8GB) { throw 'ZIP 解压后总体积超过 8GB 上限' }
    foreach ($required in @('backend/ruoyi-admin.jar','frontend/index.html','inference/inference_service.py',
        'inference/models-manifest.json','SHA256SUMS.json')) {
        if (-not $entries.ContainsKey($required)) { throw "离线包缺少必需文件: $required" }
    }

    $reader = [System.IO.StreamReader]::new($entries['SHA256SUMS.json'].Open(), [Text.Encoding]::UTF8)
    try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    $manifestPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($record in $manifest) {
        $path = [string]$record.path
        if (-not $manifestPaths.Add($path)) { throw "清单路径重复: $path" }
        if (-not $entries.ContainsKey($path)) { throw "清单文件不存在于 ZIP: $path" }
        $entry = $entries[$path]
        if ([Int64]$record.size -ne $entry.Length) { throw "文件大小不匹配: $path" }
        $stream = $entry.Open()
        try {
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
            finally { $sha.Dispose() }
        } finally { $stream.Dispose() }
        if ($hash -ne ([string]$record.sha256).ToLowerInvariant()) { throw "SHA-256 不匹配: $path" }
    }
    foreach ($name in $entries.Keys) {
        if (-not $name.EndsWith('/') -and $name -ne 'SHA256SUMS.json' -and -not $manifestPaths.Contains($name)) {
            throw "ZIP 包含未登记文件: $name"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($DestinationPath)) {
        $destination = [IO.Path]::GetFullPath($DestinationPath)
        if ([IO.Path]::GetPathRoot($destination) -eq $destination) { throw '禁止解压到磁盘根目录' }
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            $relative = $entry.FullName.Replace('/', [IO.Path]::DirectorySeparatorChar)
            $target = [IO.Path]::GetFullPath((Join-Path $destination $relative))
            $prefix = $destination.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
            if (-not $target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Zip Slip 路径: $relative" }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            $input = $entry.Open(); $output = [IO.File]::Create($target)
            try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
        }
    }
}
finally { $archive.Dispose() }

Write-Output "Offline package signature, paths, sizes and SHA-256 manifest verified: $package"
