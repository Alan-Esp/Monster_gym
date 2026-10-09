# API

## Convenciones
- Base: /api. JSON en español para mensajes; nombres de campo en inglés.
- Dinero siempre en centavos (entero). Fechas ISO 8601.
- Autenticación por cookie de sesión. Las peticiones que modifican datos llevan el token CSRF (cabecera X-XSRF-TOKEN).
- Roles: A = Administrador, R = Recepción, E = Entrenador.
- Errores: HTTP adecuado y cuerpo
  { "error": { "code": "SIN_TURNO", "message": "Abre un turno antes de cobrar." } }
  Códigos usados: NO_AUTENTICADO (401), SIN_PERMISO (403), CSRF_INVALIDO (403, falta o no coincide X-XSRF-TOKEN), NO_ENCONTRADO (404), VALIDACION (422, con "fields"), SIN_TURNO (409), TURNO_ABIERTO (409), STOCK_INSUFICIENTE (409), SOCIO_VENCIDO (409, requiere confirmación), DOBLE_REGISTRO (409, requiere confirmación), CUENTA_BLOQUEADA (423, tras 5 intentos fallidos; 15 minutos), PAGO_CANCELADO (409), TURNO_CERRADO (409, no se cancela un pago de un turno cerrado), NUMERO_AGOTADO (409, se pasó del socio 9999), ACCESO_MANUAL_DESACTIVADO (409, setting.manual_access = 0), VENTA_CANCELADA (409), OPERACION_NO_PERMITIDA (409, p. ej. desactivarse a sí mismo), IMPRESION_DESACTIVADA (409), HUELLA_DESACTIVADA (409, setting.fingerprint_enabled = 0), LECTOR_DESCONECTADO (409), CAPTURA_EN_CURSO (409, ya hay un enrolamiento en curso).
- Acciones que requieren confirmación (SOCIO_VENCIDO, DOBLE_REGISTRO) se repiten con "confirm": true.

## Autenticación y turnos
| Método y ruta | Roles | Descripción |
|---|---|---|
| POST /api/auth/login | público | { username, pin } |
| POST /api/auth/logout | todos | |
| GET /api/me | todos | { name, initials, role, shift: {id, openedAt} o null } |
| POST /api/shifts/open | A, R | { openingCashCents } |
| GET /api/shifts/current | A, R | Turno abierto y resumen del corte parcial |
| POST /api/shifts/close | A, R | { countedCashCents } devuelve el corte final |
| GET /api/shifts?limit=50 | A | Historial de cortes (Admin > Turnos) |

## Inicio
| GET /api/dashboard | todos | |
Respuesta:
{ "aforo": {"inside": 23, "capacity": 120, "peakHour": "18:00"},
  "visitsToday": 84, "visitsDeltaVsYesterday": 12,
  "activeMembers": 412, "expiredCount": 2, "expiringCount": 4,
  "firstHour": 6, "visitsByHour": [4,9,12,...] (16 valores, de 06:00 a 21:00), "currentHourIndex": 10 (-1 fuera de horario),
  "topProducts": [{"name": "Bebida isotónica", "sold": 11}],
  "recentVisits": [{"time":"16:38","memberId":412,"number":"0412","name":"Daniela Ortega","method":"huella","status":"activa"}],
  "income": {"membershipsCents": 0, "fridgeCents": 0, "totalCents": 0} }
- aforo.inside: visitas de los últimos setting.inside_minutes minutos; peakHour: hora con más visitas hoy (null si no hay).
- visitsDeltaVsYesterday: visitas de hoy menos las de ayer hasta la misma hora.
- activeMembers = activa + por_vencer; expiredCount = todos los vencidos (incluye sin membresía); expiringCount = por_vencer.
- recentVisits: últimas 6 entradas; status es el estado al momento de entrar.
- income: cobros y ventas de hoy (todos los turnos), sin cancelados.

## Socios
| Método y ruta | Roles | Descripción |
|---|---|---|
| GET /api/members?q=&status=&page= | todos | status: activa, por_vencer, vencida. q busca por nombre, número o teléfono (sin espacios ni acentos). Devuelve conteos por estado. |
| POST /api/members | A, R | { fullName, phone, notes, planId? , method?, fingerprintEnrollmentId? } si trae plan, también cobra |
| GET /api/members/{id} | todos | Datos, vigencia, historial de pagos y visitas |
| PUT /api/members/{id} | A, R | Editar datos |

Ítem de lista: { id, number, fullName, phone, plan, validUntil, status }.
Respuesta de la lista: { items, total, page, pageSize (50), registered, counts: { todos, activa, por_vencer, vencida } }.
counts respeta q pero no status; registered es el total de socios activos sin filtros.
Teléfono: 10 dígitos (acepta espacios, guiones y paréntesis; se guarda solo con dígitos).
POST /api/members responde { member: <detalle>, payment: <cobro> o null }. Si trae planId exige method;
sin turno abierto responde SIN_TURNO y no crea el socio.
fingerprintEnrollmentId: huella capturada antes del alta con POST /api/fingerprint/enroll; si no existe o ya venció
(30 minutos) responde VALIDACION en ese campo y no crea el socio. El detalle del socio incluye hasFingerprint.

## Cobros
| POST /api/payments | A, R | { memberId, planId, method } (usa el turno abierto) |
| GET /api/payments?shift=current | A, R | Pagos del turno |
| POST /api/payments/{id}/void | A | { reason } |
| GET /api/plans | todos | |
| PUT /api/plans/{id} | A | { priceCents } |

Respuesta de un cobro (también cada elemento de GET /api/payments y del historial del socio), con los datos del ticket:
{ id, folio ("000123"), member: {id, number, fullName}, plan: {id, name, days}, startsOn, endsOn,
  amountCents, method, employeeName, paidAt, shiftId, voidedAt, voidReason }
Solo se cancela un pago si su turno sigue abierto.

## Visitas
| POST /api/visits | A, R | { memberId, method: "manual", confirm? } |
| GET /api/visits?date=YYYY-MM-DD | todos | Bitácora del día y % de huella |

POST /api/visits: sin confirm responde 409 SOCIO_VENCIDO (socio vencido o sin membresía) o DOBLE_REGISTRO
(el mismo socio entró hace menos de 5 minutos). Si aplican ambos, el código es SOCIO_VENCIDO y el mensaje
menciona los dos; un solo "confirm": true los cubre. Respuesta:
{ id, checkedInAt, method, statusAtEntry, member: { id, number, fullName, plan, validUntil, daysLeft } }.
GET /api/visits (date por defecto hoy) responde
{ date, items: [{ id, time, checkedInAt, memberId, number, name, method, status }], total, fingerprintPct,
  insideNow, weekCount (desde el lunes), manualAccess }.

## Refri
| GET /api/products | todos | Con stock, capacidad, vendido hoy y bandera lowStock/outOfStock |
| POST /api/sales | A, R | { method, items: [{productId, qty}] } |
| GET /api/sales?shift=current | A, R | Últimas ventas |
| POST /api/sales/{id}/void | A | { reason } |
| POST /api/products/{id}/restock | A, R | { qty, reason } |

- Producto: { id, name, size, priceCents, stock, capacity, minStock, soldToday, soldTodayCents, lowStock (stock <= minStock), outOfStock }.
- restock: reason = reabasto (suma qty > 0), merma (resta qty > 0) o ajuste (suma qty con signo, distinto de 0).
  El stock nunca queda negativo (409 STOCK_INSUFICIENTE). Cada movimiento queda en stock_movement con el empleado.
- POST /api/sales: una sola transacción; exige turno abierto (SIN_TURNO); el mismo producto en varias líneas se suma;
  si un producto no alcanza responde 409 STOCK_INSUFICIENTE y no guarda nada. Respuesta:
  { id, folio, createdAt, method, totalCents, employeeName, shiftId, voidedAt, voidReason,
    items: [{ productId, name, qty, unitPriceCents, subtotalCents }] }.
- POST /api/sales/{id}/void regresa el stock (movimiento "ajuste") y solo procede si el turno de la venta sigue abierto.
- POST /api/shifts/close acepta además { notes } (observaciones, máximo 500 caracteres); el corte las devuelve en "notes".

## Admin
| CRUD /api/employees | A | Alta, edición, activar/desactivar, cambio de PIN |
| GET/PUT /api/settings | A | gymName, capacity, warnDays, manualAccess, sound, insideMinutes, fingerprintEnabled |
| GET /api/reports/income-monthly?months=6 | A | [{ month, label, membershipsCents, fridgeCents, totalCents, current }] |
| GET /api/reports/summary | A | { activeMembers, newThisMonth, renewalRate, expiredLast30Days } |

- /api/employees: GET lista { id, name, username, role, shiftLabel, active, locked }; POST { name, username, role, shiftLabel, pin };
  PUT /{id} { name, username, role, shiftLabel, active }; PUT /{id}/pin { pin } (también quita el bloqueo).
  Nadie puede desactivarse ni quitarse el rol de administrador; siempre queda al menos un administrador activo.
  Un empleado desactivado pierde su sesión en la siguiente petición.
- PUT /api/settings actualiza solo los campos enviados. Rangos: capacity 1-2000, warnDays 0-30, insideMinutes 15-600.
- renewalRate: de los socios cuya membresía venció en los últimos 30 días, % que hoy está vigente (null si no hay).

## Impresión de tickets (Fase 5B)
| Método y ruta | Roles | Descripción |
|---|---|---|
| POST /api/payments/{id}/ticket | A, R | Reimprime el ticket de cobro (marcado COPIA). 202 con print |
| POST /api/shifts/{id}/ticket | A | Reimprime el ticket de cierre (marcado COPIA). 202; 409 TURNO_ABIERTO si sigue abierto |
| GET /api/print-jobs/{id} | A, R | Estado de un trabajo de impresión |
| GET /api/printer/status | A, R | Estado de la impresora (no abre la conexión Bluetooth) |
| POST /api/printer/test | A | Encola un ticket de prueba (acentos, regla de columnas y configuración). 202 con print |

La impresión es asíncrona: un solo worker procesa print_job en orden, con timeout y reintentos.
POST /api/payments, POST /api/members (si cobra) y POST /api/shifts/close no esperan a la impresora:
devuelven sus campos de siempre más
"print": { "jobId": 12, "status": "pendiente", "error": null }
o { "jobId": null, "status": "desactivada" } (setting.print_enabled = 0)
o { "jobId": null, "status": "fallido", "error": "..." } (ni siquiera se pudo encolar).
El cobro y el cierre se guardan aunque la impresión falle. La pantalla consulta el resultado con GET /api/print-jobs/{id}.

- Trabajo: { id, kind (cobro|cierre_turno|prueba), refId, isCopy, status, transport (windows|archivo),
  attempts, error, employeeId, createdAt, finishedAt }.
- status: pendiente → enviando → en_cola_windows | fallido (entregado solo en desarrollo). Mientras se reintenta
  queda "enviando" con el error del último intento. Ningún estado significa "salió en papel": con la PT-210 por
  Bluetooth no se puede saber si está encendida.
- Reimpresión o prueba con la impresión desactivada: 409 IMPRESION_DESACTIVADA.
- Estado: { enabled, mode (real|fake), transport, printerName, codepage, charset, ready (true, false o
  null = no se puede saber), detail, pending, failedToday, lastJob, availablePrinters }. ready = false solo si la
  impresora no está instalada, hay trabajos detenidos en la cola de Windows o el último ticket falló.
- GET /api/settings incluye "printing": { enabled, printerName, codepage, charset, ticketFooter, timeoutSeconds,
  retries }. PUT /api/settings acepta printEnabled, printerName (no vacío), printerCodepage (0-255), printerCharset
  (debe existir en Java; US-ASCII = sin acentos), ticketFooter (máx. 200), printTimeoutSeconds (5-120), printRetries (0-10).

## Huella (Fase 6)
| POST /api/fingerprint/enroll | A, R | Captura para un socio nuevo (Nuevo socio). Responde 202 { enrollmentId } |
| POST /api/fingerprint/enroll/{memberId} | A, R | Captura (o reemplaza) la huella de un socio existente. 202 { enrollmentId } |
| POST /api/fingerprint/enroll/{enrollmentId}/cancel | A, R | Cancela la captura en curso (204) |
| GET /api/fingerprint/status | todos | { enabled, connected, mode (real\|fake), detail, enrolled } |
| GET /api/fingerprint/events | A, R | Server-Sent Events (ver abajo) |

- La captura ocurre en el backend: 4 muestras del mismo dedo, hasta 30 s de espera por muestra. Una sola a la vez
  (CAPTURA_EN_CURSO). Sin lector: LECTOR_DESCONECTADO; apagado en configuración: HUELLA_DESACTIVADA.
- Sin memberId la plantilla queda pendiente en memoria 30 minutos, hasta que POST /api/members la reclama con
  fingerprintEnrollmentId. Nunca se envía la plantilla al navegador.
- Eventos SSE (data en JSON). Al conectarse, el primer evento es "estado".
  - estado: igual que GET /api/fingerprint/status. Se manda también al conectar o desconectar el lector.
  - enrolamiento: { enrollmentId, status: muestra\|calidad\|listo\|error, remaining (-1 si no aplica), message }.
  - identificado: { duplicate, message, visit: <respuesta de POST /api/visits> }. duplicate = true si el socio ya
    entró hace menos de 5 minutos: no se crea otra visita y visit es la anterior.
  - no_reconocido: { message }.
- La entrada por huella la registra el backend con method = huella, a nombre del Administrador o Recepción con la
  suscripción SSE más reciente. Sin nadie suscrito, la huella se ignora.
- Solo con el lector simulado (perfil dev), A y R: POST /api/fingerprint/fake/touch { finger } simula poner un dedo
  ("" = mala calidad) y POST /api/fingerprint/fake/connected { connected } simula conectar o desconectar el lector.
  Con el lector real responden 404.
