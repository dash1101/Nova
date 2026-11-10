<#
  Nova server for Windows - download the newest release and install it.

      irm https://raw.githubusercontent.com/dash1101/Nova/main/get.ps1 | iex        (in an administrator PowerShell)

  It downloads nova-windows.zip from the newest GitHub release, checks it against the release's SHA256SUMS,
  unpacks it to a temporary folder and runs its installer (server\windows\install.ps1 in the source).
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Write-Host "Open PowerShell as administrator (right-click -> Run as administrator) and run this again." -ForegroundColor Red; return }
$base = if ($env:NOVA_RELEASE_URL) { $env:NOVA_RELEASE_URL.TrimEnd('/') } else { 'https://github.com/dash1101/Nova/releases/latest/download' }
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("nova-install-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    Write-Host "Downloading Nova..."
    $zip = Join-Path $tmp 'nova-windows.zip'
    Invoke-WebRequest -UseBasicParsing "$base/nova-windows.zip" -OutFile $zip
    $sums = (Invoke-WebRequest -UseBasicParsing "$base/SHA256SUMS").Content
    if ($sums -is [byte[]]) { $sums = [Text.Encoding]::ASCII.GetString($sums) }
    $want = ($sums -split "`n" | Where-Object { $_ -match '\s\*?nova-windows\.zip\s*$' } | Select-Object -First 1)
    if (-not $want) { throw "the release's checksum list has no entry for nova-windows.zip" }
    $want = ($want.Trim() -split '\s+')[0].ToLower()
    $have = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLower()
    if ($want -ne $have) { throw "the download doesn't match the release's checksum (expected $want, got $have)" }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $inst = Join-Path $tmp 'Nova\windows\install.ps1'
    if (-not (Test-Path $inst)) { throw "the download has no installer in it" }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $inst
    if ($LASTEXITCODE -ne 0) { throw "the installer stopped with an error" }
} catch {
    Write-Host "`nNova wasn't installed: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
