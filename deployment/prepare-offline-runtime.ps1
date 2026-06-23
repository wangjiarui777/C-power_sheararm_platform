param(
    [Parameter(Mandatory = $true)][string]$InstallRoot
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path $InstallRoot).Path
$python = Join-Path $root "runtime\python\python.exe"
$requirements = Join-Path $root "current\inference\requirements.txt"
$wheelhouse = Join-Path $root "wheelhouse"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "缺少离线 Python 运行时: $python" }
if (-not (Test-Path -LiteralPath $requirements -PathType Leaf)) { throw "缺少 requirements.txt" }
if (-not (Test-Path -LiteralPath $wheelhouse -PathType Container)) { throw "缺少 wheelhouse" }
& $python -m pip install --no-index --find-links $wheelhouse --requirement $requirements
if ($LASTEXITCODE -ne 0) { throw "离线 Python 依赖安装失败" }
& $python -c "import fastapi, torch, numpy, scipy; print('offline inference runtime ready')"
if ($LASTEXITCODE -ne 0) { throw "离线 Python 运行时自检失败" }
