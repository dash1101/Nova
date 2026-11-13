<#
  Nova server for Windows - install or update.

      powershell -ExecutionPolicy Bypass -File install.ps1          (from an administrator PowerShell)

  What it does (all of it undone by uninstall.ps1):
    * copies Nova to  C:\Program Files\Nova   and keeps its settings and data in  C:\ProgramData\Nova
    * makes a private Python environment there (needs Python 3.9 or newer; it's installed with winget if missing)
    * adds three tasks to Task Scheduler's \Nova\ folder that start with Windows:
        Api     the part phones and browsers talk to - runs as the low-privilege "Local Service" account
        Helper  the only part allowed to manage the machine - runs as SYSTEM, reachable from this computer only
        Alerts  the monitor behind the Inbox and Discord alerts - runs as SYSTEM
    * lets the home network reach ports 8095/8495 (and 8496 for finding the server) through Windows Firewall
    * adds the  nova  command and a "Nova Server" entry to the Start menu

  Options:  -NoStart   install without starting      -NoFirewall   leave Windows Firewall alone
            -NoWindow  don't open Nova Server at the end (it opens after a first install, when run by a person)
            -Python C:\path\python.exe               use this Python instead of looking for one

  It never waits for a key. The last line is for programs that run it (hub apps such as Orbit):
      NOVA-RESULT {"ok":true,"version":"...","previous":"...","firstRun":true,"running":true,"devices":0,"ports":{...},"urls":{...}}
  and the exit code is 0 when it worked, 1 when it didn't (then the line has "ok":false and "error").
#>
[CmdletBinding()]
param([switch]$NoStart, [switch]$NoFirewall, [switch]$NoWindow, [string]$Python = "")
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Version = ''; $Prev = ''
function Say($t)  { Write-Host "  $t" }
function Step($t) { Write-Host "`n$t" -ForegroundColor Cyan }
function Result($o) { [Console]::Out.WriteLine("NOVA-RESULT " + ($o | ConvertTo-Json -Compress -Depth 4)) }
function Die($t)  { Write-Host "`n  $t`n" -ForegroundColor Red; Result ([ordered]@{ ok = $false; error = "$t"; version = $Version; previous = $Prev }); exit 1 }

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    # ask Windows for permission and carry on in a new window (declined: nothing happens)
    $a = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', "`"$($MyInvocation.MyCommand.Path)`"")
    if ($NoStart) { $a += '-NoStart' }; if ($NoFirewall) { $a += '-NoFirewall' }; if ($NoWindow) { $a += '-NoWindow' }; if ($Python) { $a += @('-Python', "`"$Python`"") }
    try { Start-Process powershell.exe -Verb RunAs -ArgumentList $a | Out-Null; exit 0 }
    catch { Die "Nova needs administrator permission to install. Run this again and choose Yes when Windows asks." }
}

$Src  = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)      # the folder holding api\, monitor\, web\, store\, windows\
$App  = Join-Path $env:ProgramFiles 'Nova'
$Lib  = Join-Path $App 'lib'
$Data = Join-Path $env:ProgramData 'Nova'
$Py   = Join-Path $App 'venv\Scripts\python.exe'
$PyW  = Join-Path $App 'venv\Scripts\pythonw.exe'
foreach ($need in 'api\server.py', 'web\index.html', 'monitor\nova_alerts.py') {
    if (-not (Test-Path (Join-Path $Src $need))) { Die "This doesn't look like a Nova download: $need is missing next to the windows folder." }
}
$Version = if (Test-Path (Join-Path $Src 'VERSION')) { (Get-Content (Join-Path $Src 'VERSION') -Raw).Trim() }
           else { ([regex]'API_VERSION = "([^"]+)"').Match((Get-Content (Join-Path $Src 'api\server.py') -Raw)).Groups[1].Value }
$Prev  = if (Test-Path (Join-Path $Lib 'VERSION')) { (Get-Content (Join-Path $Lib 'VERSION') -Raw).Trim() } else { '' }
$First = -not (Test-Path (Join-Path $Data 'config\config.json'))          # no settings yet: a first install, not an update
if ($Prev) { Write-Host "`nUpdating Nova $Prev -> $Version  (settings and paired devices stay)" -ForegroundColor White }
else       { Write-Host "`nInstalling Nova $Version for Windows" -ForegroundColor White }

# -- 1. Python --------------------------------------------------------------------
Step "Python"
function Test-Py($exe) {
    if (-not $exe -or -not (Test-Path $exe)) { return $false }
    try { $v = & $exe -c "import sys; print(int(sys.version_info >= (3, 9)))" 2>$null; return ($v -eq '1') } catch { return $false }
}
function Find-Py {
    $c = @()
    if ($Python) { $c += $Python }
    try { $c += (& py -3 -c "import sys; print(sys.executable)" 2>$null) } catch {}
    $c += (Get-Command python.exe -All -ErrorAction SilentlyContinue | ForEach-Object Source)
    $c += (Get-ChildItem "$env:ProgramFiles\Python3*\python.exe", "$env:LocalAppData\Programs\Python\Python3*\python.exe" -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | ForEach-Object FullName)
    foreach ($p in $c) { if ($p -and $p -notmatch 'WindowsApps' -and (Test-Py $p)) { return $p } }
    return $null
}
$base = Find-Py
if (-not $base) {
    Say "Python isn't installed - installing it with winget..."
    if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) { Die "Python 3.9 or newer is needed and winget isn't available to install it. Install Python from https://www.python.org/downloads/windows/ (tick 'Install for all users'), then run this again." }
    & winget install -e --id Python.Python.3.12 --scope machine --silent --accept-package-agreements --accept-source-agreements | Out-Null
    $base = Find-Py
    if (-not $base) { Die "Python was installed but couldn't be found. Open a new administrator PowerShell and run this again." }
}
Say "using $base"

# -- 2. stop what's running, copy the files ----------------------------------------
Step "Files"
foreach ($t in 'Api', 'Alerts', 'Helper') {
    $task = Get-ScheduledTask -TaskPath '\Nova\' -TaskName $t -ErrorAction SilentlyContinue
    if ($task) { Stop-ScheduledTask -TaskPath '\Nova\' -TaskName $t -ErrorAction SilentlyContinue }
}
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Lib*" -and $_.ProcessId -ne $PID } |
    ForEach-Object { & taskkill.exe /T /F /PID $_.ProcessId 2>$null | Out-Null }
New-Item -ItemType Directory -Force -Path $App | Out-Null
foreach ($i in 1..15) {                                   # a part that is still closing can hold the folder for a moment
    if (-not (Test-Path $Lib)) { break }
    try { Remove-Item -Recurse -Force $Lib -ErrorAction Stop } catch { Start-Sleep -Seconds 1 }
}
if (Test-Path $Lib) { Die "Couldn't replace $Lib - something is still using it. Restart the computer and run this again." }
New-Item -ItemType Directory -Force -Path $Lib | Out-Null
Copy-Item -Recurse -Force (Join-Path $Src 'api\*') $Lib -Exclude '__pycache__'
foreach ($d in 'web', 'store', 'monitor') { if (Test-Path (Join-Path $Src $d)) { Copy-Item -Recurse -Force (Join-Path $Src $d) (Join-Path $Lib $d) } }
Get-ChildItem -Recurse -Directory -Filter '__pycache__' $Lib | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
Set-Content -Path (Join-Path $Lib 'VERSION') -Value $Version -Encoding ascii
Copy-Item -Force (Join-Path $Src 'windows\uninstall.ps1') $App
Set-Content -Path (Join-Path $App 'nova.cmd') -Encoding ascii -Value "@echo off`r`n`"%~dp0venv\Scripts\python.exe`" `"%~dp0lib\nova`" %*"
Say "copied to $App"

# -- 3. Python environment ---------------------------------------------------------
Step "Python environment"
if (-not (Test-Py $Py)) {
    if (Test-Path (Join-Path $App 'venv')) { Remove-Item -Recurse -Force (Join-Path $App 'venv') }
    & $base -m venv (Join-Path $App 'venv'); if ($LASTEXITCODE -ne 0) { Die "Couldn't create the Python environment." }
}
& $Py -m pip install --quiet --disable-pip-version-check --upgrade cryptography psutil
if ($LASTEXITCODE -ne 0) { Die "Couldn't download Nova's two Python libraries (cryptography, psutil). Check the internet connection and run this again." }
Say "ready"

# -- 4. settings and data folders (only Nova's accounts and administrators can read them) -------
Step "Settings"
$SYSTEM = '*S-1-5-18'; $ADMINS = '*S-1-5-32-544'; $SERVICE = '*S-1-5-19'       # by ID: the names differ per language
New-Item -ItemType Directory -Force -Path $Data | Out-Null
& icacls $Data /inheritance:r /grant "${SYSTEM}:(OI)(CI)F" "${ADMINS}:(OI)(CI)F" "${SERVICE}:(RX)" | Out-Null
foreach ($d in 'config', 'config\alerts', 'data', 'alerts', 'alerts\www', 'log', 'run', 'run\tasks', 'apps') { New-Item -ItemType Directory -Force -Path (Join-Path $Data $d) | Out-Null }
& icacls (Join-Path $Data 'config') /grant "${SERVICE}:(OI)(CI)RX" | Out-Null          # the API reads its settings...
& icacls (Join-Path $Data 'config\alerts') /inheritance:r /grant "${SYSTEM}:(OI)(CI)F" "${ADMINS}:(OI)(CI)F" | Out-Null   # ...but not the Discord webhook
& icacls (Join-Path $Data 'data')   /grant "${SERVICE}:(OI)(CI)M"  | Out-Null          # devices, pairing, its certificate
& icacls (Join-Path $Data 'log')    /grant "${SERVICE}:(OI)(CI)M"  | Out-Null
& icacls (Join-Path $Data 'alerts\www') /grant "${SERVICE}:(OI)(CI)RX" "*S-1-5-32-545:(OI)(CI)RX" | Out-Null   # status and Inbox, written by the monitor: readable by everyone here, as on Linux (hub apps such as Orbit read status.json)
& icacls (Join-Path $Data 'run')    /grant "${SERVICE}:(OI)(CI)RX" | Out-Null          # where the helper listens, and its key
$r = & $Py (Join-Path $Lib 'winsetup.py'); if ($LASTEXITCODE -ne 0) { Die "Couldn't write the settings: $r" }
$cfg = Get-Content (Join-Path $Data 'config\config.json') -Raw | ConvertFrom-Json
Say "home-network address $($cfg.lan_host)"
if ($cfg.tailnet_host) { Say "Tailscale address    $($cfg.tailnet_host)" }

# -- 5. the three tasks ------------------------------------------------------------
Step "Background tasks"
$set = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
$boot = New-ScheduledTaskTrigger -AtStartup
function Add-NovaTask($name, $part, $user, $about) {
    $act = New-ScheduledTaskAction -Execute $Py -Argument "`"$(Join-Path $Lib 'winrun.py')`" $part" -WorkingDirectory $Lib
    $who = if ($user -eq 'SYSTEM') { New-ScheduledTaskPrincipal -UserId 'S-1-5-18' -LogonType ServiceAccount -RunLevel Highest }
           else { New-ScheduledTaskPrincipal -UserId 'S-1-5-19' -LogonType ServiceAccount }
    Register-ScheduledTask -TaskPath '\Nova\' -TaskName $name -Action $act -Trigger $boot -Principal $who -Settings $set -Description $about -Force | Out-Null
    Say "$name ($user)"
}
Add-NovaTask 'Helper' 'helper' 'SYSTEM'        "Nova: the only part allowed to manage this computer. Reachable from this computer only."
Add-NovaTask 'Api'    'api'    'Local Service' "Nova: what the Nova app and browsers talk to. Runs without administrator rights."
Add-NovaTask 'Alerts' 'alerts' 'SYSTEM'        "Nova: watches the server and fills the Inbox."

# -- 6. firewall, command, Start menu ----------------------------------------------
if (-not $NoFirewall) {
    Step "Windows Firewall"
    Get-NetFirewallRule -Group 'Nova' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName 'Nova (app and web)' -Group 'Nova' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $cfg.port, $cfg.tls_port -RemoteAddress LocalSubnet, '100.64.0.0/10' -Profile Any | Out-Null
    New-NetFirewallRule -DisplayName 'Nova (finding the server)' -Group 'Nova' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8496 -RemoteAddress LocalSubnet -Profile Any | Out-Null
    Say "the home network (and Tailscale) can reach ports $($cfg.port) and $($cfg.tls_port)"
}
$path = [Environment]::GetEnvironmentVariable('Path', 'Machine')
if (($path -split ';') -notcontains $App) { [Environment]::SetEnvironmentVariable('Path', ($path.TrimEnd(';') + ';' + $App), 'Machine') }
try {
    $lnk = Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'Nova Server.lnk'
    $sh = New-Object -ComObject WScript.Shell; $s = $sh.CreateShortcut($lnk)
    $s.TargetPath = $PyW; $s.Arguments = "`"$(Join-Path $Lib 'nova-gui')`""; $s.WorkingDirectory = $Lib
    $s.Description = 'Nova Server: status, pairing, devices and approvals'
    if (Test-Path (Join-Path $Lib 'web\nova.ico')) { $s.IconLocation = (Join-Path $Lib 'web\nova.ico') }
    $s.Save()
} catch { Say "(couldn't add the Start menu entry: $($_.Exception.Message))" }

# -- 7. start ----------------------------------------------------------------------
$ok = $false
if (-not $NoStart) {
    Step "Starting"
    foreach ($t in 'Helper', 'Api', 'Alerts') { Start-ScheduledTask -TaskPath '\Nova\' -TaskName $t }
    $ok = $false
    foreach ($i in 1..40) {
        try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://127.0.0.1:$($cfg.port)/api/v1/ping"; if ($r.StatusCode -eq 200) { $ok = $true; break } } catch {}
        Start-Sleep -Milliseconds 500
    }
    if ($ok) { Say "Nova is running." } else { Write-Host "  Nova didn't answer yet - see $Data\log\api.log" -ForegroundColor Yellow }
}

$devices = 0
try { $devices = @((Get-Content (Join-Path $Data 'data\devices.json') -Raw | ConvertFrom-Json).PSObject.Properties).Count } catch {}
$tls = if ($cfg.tls_port) { $cfg.tls_port } else { 8495 }
Write-Host "`nDone." -ForegroundColor Green
if ($devices -eq 0) { Write-Host "  Next, pair your phone:  'Nova Server' in the Start menu -> Pair a phone   (or in a NEW administrator terminal:  nova add)" }
else                { Write-Host "  Your $devices paired device(s) keep working.   Nova Server in the Start menu shows them." }
Write-Host "  Nova web:     https://$($cfg.lan_host):$tls   (on this PC: https://localhost:$tls)"
if ($cfg.tailnet_host) { Write-Host "  Tailscale:    https://$($cfg.tailnet_host):$tls" }
Write-Host "  Check-up:     nova doctor      Remove:  powershell -ExecutionPolicy Bypass -File `"$App\uninstall.ps1`"`n"
# a person installing for the first time goes straight on to pairing (not when a program runs this and reads its output)
if (-not $NoStart -and -not $NoWindow -and $ok -and $devices -eq 0 -and -not [Console]::IsOutputRedirected) {
    try { Start-Process -FilePath $PyW -ArgumentList "`"$(Join-Path $Lib 'nova-gui')`" --pair" -WorkingDirectory $Lib; Say "Opening Nova Server to pair your phone..." } catch {}
}
$urls = [ordered]@{ local = "https://127.0.0.1:$tls"; home = "https://$($cfg.lan_host):$tls"; plain = "http://127.0.0.1:$($cfg.port)" }
if ($cfg.tailnet_host) { $urls.tailnet = "https://$($cfg.tailnet_host):$tls" }
Result ([ordered]@{ ok = $true; version = $Version; previous = $Prev; firstRun = $First; running = [bool]$ok; devices = $devices
                    ports = [ordered]@{ https = $tls; http = $cfg.port }; urls = $urls })
exit 0
