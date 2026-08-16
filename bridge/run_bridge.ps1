param(
  [Parameter(Mandatory=$true)][string]$RelayUrl
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path ".venv")) { python -m venv .venv }
& .\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:VOLUMIND_RELAY_URL = $RelayUrl
& .\.venv\Scripts\python.exe volumind_bridge.py

