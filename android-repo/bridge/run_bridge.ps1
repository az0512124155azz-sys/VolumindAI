param(
  [string]$RelayUrl = "wss://volumindai.onrender.com/ws"
)
$ErrorActionPreference = "Stop"
$BridgeDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = Join-Path $BridgeDir ".venv"
$PythonExe = Join-Path $VenvDir "Scripts\python.exe"
if (-not (Test-Path $VenvDir)) { python -m venv $VenvDir }
& $PythonExe -m pip install -r (Join-Path $BridgeDir "requirements.txt")
$env:VOLUMIND_RELAY_URL = $RelayUrl
& $PythonExe (Join-Path $BridgeDir "volumind_bridge.py")
