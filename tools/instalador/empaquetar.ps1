# Genera dist\MonsterGym-<version>.zip: el programa con Java incluido y los scripts de instalación.
# Uso (desde la raíz del proyecto):
#   powershell -ExecutionPolicy Bypass -File tools\instalador\empaquetar.ps1 [-Version 1.0.0] [-SinPruebas]
param(
    [string]$Version = '1.0.0',
    [switch]$SinPruebas
)
$ErrorActionPreference = 'Stop'
$proyecto = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $proyecto

$trabajo = Join-Path $proyecto 'target\paquete'
$entrada = Join-Path $trabajo 'entrada'
$imagen = Join-Path $trabajo 'imagen'
$nombre = "MonsterGym-$Version"
$carpeta = Join-Path $trabajo $nombre
$dist = Join-Path $proyecto 'dist'
$zip = Join-Path $dist "$nombre.zip"

Write-Host "1/4 Compilando$(if (-not $SinPruebas) { ' y corriendo las pruebas' })..."
$mvnArgs = @('-q', 'clean', 'package')
if ($SinPruebas) { $mvnArgs += '-DskipTests' }
& .\mvnw.cmd @mvnArgs
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion o alguna prueba. No se genero el paquete.' }

$jar = Get-ChildItem target\monster-gym-*.jar | Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw 'No se encontro el jar en target\.' }

Write-Host '2/4 Creando el programa con Java incluido (jpackage)...'
New-Item -ItemType Directory -Force $entrada, $imagen | Out-Null
Copy-Item $jar.FullName (Join-Path $entrada 'monster-gym.jar') -Force
& jpackage --type app-image --name MonsterGym --dest $imagen --input $entrada --main-jar monster-gym.jar `
    --icon (Join-Path $PSScriptRoot 'monster.ico') --app-version $Version --vendor 'Monster Gym' `
    --description 'Monster Gym - sistema del gimnasio' `
    --java-options '-Dspring.profiles.active=prod' `
    --java-options '--enable-native-access=ALL-UNNAMED' `
    --java-options '-Dfile.encoding=UTF-8' `
    --java-options '-Xmx512m'
if ($LASTEXITCODE -ne 0) { throw 'Fallo jpackage.' }

Write-Host '3/4 Agregando los scripts de instalacion...'
New-Item -ItemType Directory -Force $carpeta | Out-Null
Copy-Item (Join-Path $imagen 'MonsterGym') (Join-Path $carpeta 'MonsterGym') -Recurse -Force
foreach ($f in 'INSTALAR.bat', 'instalar.ps1', 'abrir.ps1', 'desinstalar.ps1', 'LEEME.txt') {
    Copy-Item (Join-Path $PSScriptRoot $f) $carpeta -Force
}

Write-Host '4/4 Comprimiendo...'
New-Item -ItemType Directory -Force $dist | Out-Null
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $carpeta -DestinationPath $zip -CompressionLevel Optimal

$mb = [Math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ''
Write-Host "Listo: $zip ($mb MB)"
Write-Host 'Copialo a la PC del gimnasio, descomprimelo y abre INSTALAR.bat.'
