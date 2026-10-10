# Acceso directo "Monster Gym": si el servidor no está corriendo lo arranca, espera a que responda
# y abre la app en una ventana propia de Edge (sin barra de direcciones).
$url = 'http://localhost:8080'
$prueba = 'http://127.0.0.1:8080/api/setup'
$tarea = 'Monster Gym'

function Responde {
    try { (Invoke-WebRequest $prueba -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch { $false }
}

if (-not (Responde)) {
    Start-ScheduledTask -TaskName $tarea -ErrorAction SilentlyContinue
    $limite = (Get-Date).AddSeconds(90)
    while (-not (Responde) -and (Get-Date) -lt $limite) { Start-Sleep -Milliseconds 500 }
    if (-not (Responde)) {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show(
            "Monster Gym no arrancó.`n`nReinicia la PC. Si sigue igual, revisa el log en C:\MonsterGym\datos\logs.",
            'Monster Gym', 'OK', 'Error') | Out-Null
        exit 1
    }
}

try {
    Start-Process msedge -ArgumentList "--app=$url"
} catch {
    Start-Process $url  # sin Edge: el navegador predeterminado
}
