# Quita Monster Gym de esta PC: arranque automático, accesos directos y el programa.
# Los DATOS (C:\MonsterGym\datos: base, respaldos y logs) NO se borran.
param([string]$Raiz = 'C:\MonsterGym')
$ErrorActionPreference = 'Stop'
$tarea = 'Monster Gym'

if (Get-ScheduledTask -TaskName $tarea -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $tarea
    Unregister-ScheduledTask -TaskName $tarea -Confirm:$false
}
Get-Process -Name MonsterGym -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2
foreach ($dir in [Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs')) {
    Remove-Item (Join-Path $dir 'Monster Gym.lnk') -ErrorAction SilentlyContinue
}
$programa = Join-Path $Raiz 'programa'
if (Test-Path $programa) { Remove-Item $programa -Recurse -Force }

Write-Host 'Monster Gym se desinstaló.'
Write-Host "Los datos siguen en $(Join-Path $Raiz 'datos'). Bórralos a mano solo si ya no los necesitas."
