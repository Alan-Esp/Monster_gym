# API

## Convenciones
- Base: /api. JSON en español para mensajes; nombres de campo en inglés.
- Dinero siempre en centavos (entero). Fechas ISO 8601.
- Autenticación por cookie de sesión. Las peticiones que modifican datos llevan el token CSRF (cabecera X-XSRF-TOKEN).
- Roles: A = Administrador, R = Recepción, E = Entrenador.
- Errores: HTTP adecuado y cuerpo
  { "error": { "code": "SIN_TURNO", "message": "Abre un turno antes de cobrar." } }
  Códigos usados: NO_AUTENTICADO (401), SIN_PERMISO (403), CSRF_INVALIDO (403, falta o no coincide X-XSRF-TOKEN), NO_ENCONTRADO (404), VALIDACION (422, con "fields"), SIN_TURNO (409), TURNO_ABIERTO (409), STOCK_INSUFICIENTE (409), SOCIO_VENCIDO (409, requiere confirmación), DOBLE_REGISTRO (409, requiere confirmación), CUENTA_BLOQUEADA (423, tras 5 intentos fallidos; 15 minutos).
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
| GET /api/shifts | A | Historial de cortes |

## Inicio
| GET /api/dashboard | todos | |
Respuesta:
{ "aforo": {"inside": 23, "capacity": 120, "peakHour": "18:00"},
  "visitsToday": 84, "visitsDeltaVsYesterday": 12,
  "activeMembers": 412, "expiredCount": 2, "expiringCount": 4,
  "visitsByHour": [4,9,12,...], "currentHourIndex": 10,
  "topProducts": [{"name": "Bebida isotónica", "sold": 11}],
  "recentVisits": [{"time":"16:38","memberId":412,"number":"0412","name":"Daniela Ortega","method":"huella","status":"activa"}],
  "income": {"membershipsCents": 0, "fridgeCents": 0, "totalCents": 0} }

## Socios
| Método y ruta | Roles | Descripción |
|---|---|---|
| GET /api/members?q=&status=&page= | todos | status: activa, por_vencer, vencida. q busca por nombre, número o teléfono (sin espacios ni acentos). Devuelve conteos por estado. |
| POST /api/members | A, R | { fullName, phone, notes, planId? , method? } si trae plan, también cobra |
| GET /api/members/{id} | todos | Datos, vigencia, historial de pagos y visitas |
| PUT /api/members/{id} | A, R | Editar datos |

Ítem de lista: { id, number, fullName, phone, plan, validUntil, status }.

## Cobros
| POST /api/payments | A, R | { memberId, planId, method } (usa el turno abierto) |
| GET /api/payments?shift=current | A, R | Pagos del turno |
| POST /api/payments/{id}/void | A | { reason } |
| GET /api/plans | todos | |
| PUT /api/plans/{id} | A | { priceCents } |

## Visitas
| POST /api/visits | A, R | { memberId, method: "manual", confirm? } |
| GET /api/visits?date=YYYY-MM-DD | todos | Bitácora del día y % de huella |

## Refri
| GET /api/products | todos | Con stock, capacidad, vendido hoy y bandera lowStock/outOfStock |
| POST /api/sales | A, R | { method, items: [{productId, qty}] } |
| GET /api/sales?shift=current | A, R | Últimas ventas |
| POST /api/sales/{id}/void | A | { reason } |
| POST /api/products/{id}/restock | A, R | { qty, reason } |

## Admin
| CRUD /api/employees | A | Alta, edición, activar/desactivar, cambio de PIN |
| GET/PUT /api/settings | A | gymName, capacity, warnDays, manualAccess, sound, insideMinutes |
| GET /api/reports/income-monthly?months=6 | A | [{ label, totalCents }] |

## Impresión de tickets (Fase 5B)
| Método y ruta | Roles | Descripción |
|---|---|---|
| POST /api/payments/{id}/ticket | A, R | Reimprime el ticket de cobro (marcado COPIA) |
| POST /api/shifts/{id}/ticket | A | Reimprime el ticket de cierre (marcado COPIA) |
| GET /api/printer/status | A, R | { enabled, printerName, ready } |
| POST /api/printer/test | A | Imprime un ticket de prueba |

POST /api/payments y POST /api/shifts/close imprimen automáticamente y agregan a su respuesta
{ "printed": true } o { "printed": false, "printError": "Sin papel o impresora apagada" }.
El cobro y el cierre se guardan aunque la impresión falle.

## Huella (Fase 6)
| POST /api/fingerprint/enroll/{memberId} | A, R | Inicia el enrolamiento (captura en el backend) |
| GET /api/fingerprint/status | todos | { connected: true/false } |
| GET /api/fingerprint/events | A, R | Server-Sent Events: identificado, no reconocido, lector desconectado |
