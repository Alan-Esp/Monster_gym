# Instala o actualiza Monster Gym en esta PC. Lo ejecuta INSTALAR.bat.
#   C:\MonsterGym\programa  el programa (se reemplaza en cada actualización)
#   C:\MonsterGym\datos     base de datos, respaldos y logs (nunca se tocan al actualizar)
# La app arranca sola al iniciar sesión en Windows (tarea programada, sin ventana) y se abre con el
# acceso directo "Monster Gym" del escritorio.
param([string]$Raiz = 'C:\MonsterGym')
$ErrorActionPreference = 'Stop'

$programa = Join-Path $Raiz 'programa'
$datos = Join-Path $Raiz 'datos'
$origen = Join-Path $PSScriptRoot 'MonsterGym'
$tarea = 'Monster Gym'
$usuario = "$env:USERDOMAIN\$env:USERNAME"

function Paso($texto) { Write-Host ''; Write-Host "== $texto" -ForegroundColor Cyan }

if (-not (Test-Path (Join-Path $origen 'MonsterGym.exe'))) {
    throw "No se encontró la carpeta MonsterGym junto a este script. Descomprime todo el .zip antes de instalar."
}

Write-Host 'Instalación de Monster Gym' -ForegroundColor Green
Write-Host "Programa: $programa"
Write-Host "Datos:    $datos"
Write-Host "Usuario:  $usuario (la app arranca cuando este usuario inicia sesión)"

Paso 'Deteniendo la versión anterior (si existe)'
if (Get-ScheduledTask -TaskName $tarea -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $tarea
}
Get-Process -Name MonsterGym -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 2

Paso 'Copiando el programa'
New-Item -ItemType Directory -Force $programa, $datos | Out-Null
$datosPrevios = Test-Path (Join-Path $datos 'monster-gym.db')
robocopy $origen $programa /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "No se pudieron copiar los archivos (robocopy $LASTEXITCODE)." }
Copy-Item (Join-Path $PSScriptRoot 'abrir.ps1'), (Join-Path $PSScriptRoot 'desinstalar.ps1') $programa -Force
# La carpeta de datos va fija en la configuración del programa (aunque alguien abra MonsterGym.exe directo)
$cfg = Join-Path $programa 'app\MonsterGym.cfg'
Add-Content -Path $cfg -Value "java-options=-Dapp.data-dir=$($datos.Replace('\', '/'))" -Encoding ASCII

Paso 'Registrando el arranque automático'
$accion = New-ScheduledTaskAction -Execute (Join-Path $programa 'MonsterGym.exe') -WorkingDirectory $datos
$disparador = New-ScheduledTaskTrigger -AtLogOn -User $usuario
$ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId $usuario -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $tarea -Action $accion -Trigger $disparador -Settings $ajustes `
    -Principal $principal -Description 'Servidor local de Monster Gym (http://localhost:8080)' -Force | Out-Null

Paso 'Creando accesos directos'
$shell = New-Object -ComObject WScript.Shell
foreach ($dir in [Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs')) {
    $acceso = $shell.CreateShortcut((Join-Path $dir 'Monster Gym.lnk'))
    $acceso.TargetPath = 'powershell.exe'
    $acceso.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$programa\abrir.ps1`""
    $acceso.IconLocation = "$programa\MonsterGym.exe,0"
    $acceso.WorkingDirectory = $programa
    $acceso.Description = 'Abrir Monster Gym'
    $acceso.Save()
}

Paso 'Iniciando Monster Gym'
Start-ScheduledTask -TaskName $tarea
& (Join-Path $programa 'abrir.ps1')

Write-Host ''
Write-Host 'Instalación terminada.' -ForegroundColor Green
if ($datosPrevios) {
    Write-Host 'Se conservaron los datos existentes (socios, cobros, respaldos).'
} else {
    Write-Host 'Primera instalación: en la ventana que se abrió, crea la cuenta del administrador.'
}
Write-Host 'Desde ahora abre la app con el acceso directo "Monster Gym" del escritorio.'
