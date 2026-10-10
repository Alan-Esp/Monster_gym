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

Paso 'Lector de huella (DigitalPersona One Touch RTE)'
# Ya instalado si existen el servicio DpHost y la DLL JNI que usa la app (también lo deja así el SDK completo)
$reiniciar = $false
$lectorListo = (Get-Service -Name DpHost -ErrorAction SilentlyContinue) -and
    (Test-Path "$env:WINDIR\System32\otdpfpjni.dll")
$rteMsi = Join-Path $PSScriptRoot 'huella\Setup.msi'
if ($lectorListo) {
    Write-Host 'Ya está instalado.'
} elseif (-not (Test-Path $rteMsi)) {
    Write-Warning 'El paquete no trae el instalador del lector. La app funcionará con acceso manual hasta instalarlo.'
} else {
    Write-Host 'Instalando... Windows va a pedir permiso de administrador: acepta.'
    New-Item -ItemType Directory -Force (Join-Path $datos 'logs') | Out-Null
    $log = Join-Path $datos 'logs\instalacion-lector-huella.log'
    # Solo los componentes que usa la app (Java); así no hace falta .NET Framework 2.0
    $argumentos = "/i `"$rteMsi`" ADDLOCAL=FpRecLibs,JavaFpRecLibs /qn /norestart /l*v `"$log`""
    try {
        $p = Start-Process msiexec.exe -ArgumentList $argumentos -Verb RunAs -Wait -PassThru
        switch ($p.ExitCode) {
            0 { Write-Host 'Lector de huella instalado.' }
            3010 { Write-Host 'Lector de huella instalado. Hay que REINICIAR la PC al terminar.'; $reiniciar = $true }
            default { Write-Warning "No se pudo instalar el lector (código $($p.ExitCode)). Revisa $log. La app funcionará con acceso manual." }
        }
    } catch {
        Write-Warning 'No se dio permiso de administrador: el lector no se instaló. Vuelve a abrir INSTALAR.bat para intentarlo.'
    }
}

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
if ($reiniciar) {
    Write-Host ''
    Write-Host 'IMPORTANTE: reinicia la PC para que el lector de huella empiece a funcionar.' -ForegroundColor Yellow
}
