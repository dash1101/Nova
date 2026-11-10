<#
  Nova server for Windows - remove it.

      powershell -ExecutionPolicy Bypass -File "C:\Program Files\Nova\uninstall.ps1"            keeps your settings and paired devices
      powershell -ExecutionPolicy Bypass -File "C:\Program Files\Nova\uninstall.ps1" -Purge     removes those too
#>
[CmdletBinding()]
param([switch]$Purge)
$ErrorActionPreference = 'Continue'
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Write-Host "Run this from an administrator PowerShell." -ForegroundColor Red; exit 1 }
$App = Join-Path $env:ProgramFiles 'Nova'; $Data = Join-Path $env:ProgramData 'Nova'
Get-ScheduledTask -TaskPath '\Nova\' -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-ScheduledTask -TaskPath '\Nova\' -TaskName $_.TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskPath '\Nova\' -TaskName $_.TaskName -Confirm:$false
}
try { $s = New-Object -ComObject Schedule.Service; $s.Connect(); $s.GetFolder('\').DeleteFolder('Nova', 0) } catch {}
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -and $_.CommandLine -like "*$App\*" -and $_.ProcessId -ne $PID } |
    ForEach-Object { & taskkill.exe /T /F /PID $_.ProcessId 2>$null | Out-Null }
Get-NetFirewallRule -Group 'Nova' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
$path = [Environment]::GetEnvironmentVariable('Path', 'Machine')
[Environment]::SetEnvironmentVariable('Path', (($path -split ';' | Where-Object { $_ -and $_ -ne $App }) -join ';'), 'Machine')
Remove-Item -Force (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'Nova Server.lnk') -ErrorAction SilentlyContinue
Set-Location $env:SystemRoot
foreach ($i in 1..15) {                                   # a part that is still closing can hold the folder for a moment
    Remove-Item -Recurse -Force $App -ErrorAction SilentlyContinue
    if (-not (Test-Path $App)) { break }
    Start-Sleep -Seconds 1
}
if (Test-Path $App) { Write-Host "Couldn't remove everything in $App (something is still using it) - delete that folder after a restart." -ForegroundColor Yellow }
if ($Purge) { Remove-Item -Recurse -Force $Data -ErrorAction SilentlyContinue; Write-Host "Nova and its data are removed." }
else { Write-Host "Nova is removed. Your settings and paired devices are kept in $Data (add -Purge to remove them too)." }
