# Instalación en la PC del gimnasio (Fase 7)

La guía para quien instala va dentro del paquete: `tools/instalador/LEEME.txt`.
Este documento explica cómo se arma y cómo funciona.

## Generar el paquete (en la PC de desarrollo)
```powershell
powershell -ExecutionPolicy Bypass -File tools\instalador\empaquetar.ps1 -Version 1.0.0
```
1. `mvnw clean package` con todas las pruebas (`-SinPruebas` las omite; no recomendado).
2. `jpackage --type app-image`: `MonsterGym.exe` con Java incluido (no hace falta instalar Java en la otra PC).
   Opciones fijas: perfil `prod`, `--enable-native-access=ALL-UNNAMED` (DLL del lector), UTF-8, `-Xmx512m`.
3. Resultado: `dist\MonsterGym-<versión>.zip` (~85 MB) con `MonsterGym\`, `INSTALAR.bat`, los scripts y `LEEME.txt`.

Requisitos en la PC de desarrollo: JDK 21+ con `jpackage` (no se necesita WiX) y los jars del SDK de huella en
`~/.m2` (`tools\instalar-sdk-huella.bat`).

## Qué hace INSTALAR.bat (sin permisos de administrador)
| Paso | Detalle |
|---|---|
| Detiene la versión anterior | Tarea "Monster Gym" y proceso `MonsterGym.exe` |
| Copia el programa | `C:\MonsterGym\programa` (robocopy /MIR: se reemplaza completo) |
| Fija la carpeta de datos | Agrega `-Dapp.data-dir=C:/MonsterGym/datos` a `programa\app\MonsterGym.cfg` |
| Arranque automático | Tarea programada al iniciar sesión el usuario actual, sin ventana, sin límite de tiempo |
| Accesos directos | Escritorio y menú Inicio → `abrir.ps1` |
| Abre la app | Primera vez: pantalla "Crea el administrador" |

`abrir.ps1` revisa `http://127.0.0.1:8080/api/setup`; si no responde, inicia la tarea y espera hasta 90 s.
Después abre `msedge --app=http://localhost:8080` (ventana sin barra de direcciones).

### Por qué tarea al iniciar sesión y no servicio de Windows
Un servicio corre en la sesión 0, sin escritorio. Ahí el lector de huella (One Touch SDK, captura
CAPTURE_PRIORITY_LOW) y la impresora de Windows del usuario pueden no estar disponibles. Con la tarea, la app
corre como el mismo usuario que tiene emparejada la PT-210, igual que en desarrollo.
Consecuencia: la PC debe iniciar sesión en Windows (idealmente con inicio de sesión automático).

## Perfil prod (`application-prod.properties`)
- Datos: `C:\MonsterGym\datos` (`MONSTER_DATA_DIR`): `monster-gym.db`, `respaldos\`, `logs\` (30 días).
- `server.address=127.0.0.1`: solo se abre desde la misma PC; no hay aviso del firewall.
- Sin datos de ejemplo (V2 solo existe en dev). Los 4 planes vienen de V3.
- Lector y impresora en modo real.

## Actualizar
Generar un zip con versión nueva (`-Version 1.0.1`), copiarlo y volver a abrir `INSTALAR.bat`.
Los datos no se tocan y Flyway aplica las migraciones nuevas al arrancar.

## Verificado (2026-10-09, en la PC de desarrollo, carpeta de prueba)
Instalación sin permisos de administrador, primer arranque y creación del admin, lector real conectado,
PT-210 detectada, respaldo al arrancar, recuperación tras cerrar el proceso a la fuerza (6 s),
reinstalación conservando datos y desinstalación limpia.
