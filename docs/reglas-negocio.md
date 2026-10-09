# Reglas de negocio

Estas son las decisiones por defecto. Si cambian, se actualiza este archivo primero.

## Estado de membresía
- Se calcula a partir de la fecha de vencimiento (valid_until) y de setting.warn_days.
- Vencida: valid_until es anterior a hoy.
- Por vencer: faltan warn_days días o menos para valid_until (incluido el último día).
- Activa: cualquier otro caso.
- Socio sin ninguna membresía: se muestra como Vencida.
- Nunca se guarda en la base de datos; se obtiene de la vista v_member_status.

## Renovación y cobro de membresía
- Cobrar un plan crea un payment y una membership.
- Si la membresía sigue vigente (valid_until >= hoy): la nueva empieza al día siguiente del vencimiento actual.
- Si ya venció o no existe: la nueva empieza hoy.
- ends_on = starts_on + plan.days días (ejemplo: Mensual cobrado el 8 de oct termina el 7 de nov; Semanal del 8 al 15 de oct).
- Las membresías cobradas antes de este cambio conservan su fecha original.
- El precio cobrado es el del plan al momento del cobro y se guarda en el payment.
- No se puede cobrar sin un turno abierto.
- Métodos de pago: efectivo, tarjeta, transferencia.
- Un pago no se borra. Para corregir, un Administrador lo cancela con motivo (voided_at, void_reason) y queda en el historial.

## Alta de socio
- Número de socio consecutivo de 4 dígitos con ceros a la izquierda (0001, 0002...).
- Nombre obligatorio.

## Planes
- Solo existen cuatro planes: Semanal (7 días) $120, Quincenal (15 días) $180, Mensual (30 días) $300 y Anual (365 días) $3,000.
- El precio del Anual es provisional; confirmarlo con el dueño.
- Solo el Administrador puede modificar los precios. Un cambio de precio no afecta pagos ya registrados.
- Los planes se crean en la migración V3 (existen también en producción, no solo en dev).

## Socios
- Teléfono opcional; si se captura debe tener 10 dígitos (se aceptan espacios y guiones, se guardan solo los dígitos).
- La búsqueda ignora mayúsculas, acentos y espacios; busca por nombre, número de socio o teléfono.
- Una membresía nueva se puede capturar desde "Nuevo socio": el socio se registra y se abre Caja con el socio y el plan elegidos.
- Un pago solo se puede cancelar mientras su turno siga abierto (un turno cerrado no se modifica).


## Acceso y visitas
- Cada entrada crea una visit con method = huella o manual, y status_at_entry con el estado del socio en ese momento.
- Aforo "dentro ahora": visitas de los últimos setting.inside_minutes minutos (120 por defecto). El diseño no registra salidas.
- Socio vencido: el registro manual exige confirmación explícita de quien atiende, se marca en status_at_entry y se muestra alerta roja.
- Doble registro: si el mismo socio ya entró hace menos de 5 minutos, pedir confirmación antes de registrar otra.
- Si setting.manual_access está apagado, el registro manual queda deshabilitado.

## Refri (punto de venta)
- Cada venta es una sola transacción: valida stock, descuenta inventario, guarda el precio unitario del momento y se liga al turno abierto.
- No se vende más de lo que hay en stock. Producto con stock 0 aparece como Agotado y no se puede agregar.
- Stock bajo: stock <= min_stock.
- Reabastecer, mermas y ajustes generan un stock_movement con motivo y empleado.
- "Vendido hoy" se calcula desde las ventas del día, no se guarda.
- Ventas simultáneas del último producto: solo una debe proceder.

## Turnos y corte de caja
- Un empleado abre turno con un fondo inicial en efectivo. Solo puede haber un turno abierto por empleado.
- Todo pago y venta queda ligado al turno abierto.
- Al cerrar: se captura el efectivo contado. El sistema muestra efectivo esperado (fondo + cobros y ventas en efectivo) contra contado, y la diferencia.
- Totales del corte: membresías y refri por separado, y por método de pago.
- Un turno cerrado no se modifica.

## Tickets (impresora térmica de 58 mm)
- Impresora: GOOJPRT PT-210 (58 mm, 384 puntos, 32 columnas en fuente A, ESC/POS, sin cortador ni cajón),
  por Bluetooth SPP. En Windows: "PT-210" con controlador Generic / Text Only sobre un COM saliente (COM4).
- Son comprobantes internos, no facturas. Llevan la leyenda "Comprobante no fiscal".
- Cabecera de todos los tickets: setting.gym_name, folio, fecha y hora, nombre del empleado. Pie: setting.ticket_footer.
- Cada ticket empieza con ESC @ (inicializar), FS . (cancelar modo chino) y ESC t n (página de códigos).
- **Sin acentos.** Probado con la PT-210 real: ignora FS . y se queda en modo chino con cualquier página de códigos,
  así que todo byte >= 0x80 sale como carácter chino. Por eso setting.printer_charset = US-ASCII (V6): los acentos se
  imprimen sin tilde (Ñúñez → Nunez, Atendió → Atendio) y ¿ ¡ se omiten. Si algún día se cambia a una impresora que
  sí acepte acentos, se ajustan setting.printer_codepage (n de ESC t n) y setting.printer_charset (p. ej. 2 + IBM850).
- Nunca se envía corte (GS V) ni apertura de cajón (ESC p). Cada ticket termina con 4 saltos de línea.
- Solo se usan negrita y doble alto (no doble ancho, que bajaría a 16 columnas). Textos largos se parten por palabras
  (nombres, observaciones, pie) o se recortan (nombre de producto o plan junto a un monto); los montos van a la derecha.

### Conexión: solo por la impresora de Windows
- Se mandan los bytes crudos a la impresora instalada en Windows (setting.printer_name = PT-210), que ya funciona
  con el controlador Generic / Text Only sobre el COM4 del Bluetooth.
- Se evaluó escribir directo al puerto COM y se descartó (V6) tras probar con el equipo real:
  la librería jSerialComm no puede abrir el COM Bluetooth de esta PC; escribiendo directo, Windows acepta los datos
  aunque la impresora esté apagada (y los imprime al encenderla); y la impresora no contesta consultas de estado
  (DLE EOT, GS r, GS I). No detectaba nada más que la cola de Windows.
- Qué se puede detectar: que la impresora no esté instalada en Windows y que haya trabajos detenidos en su cola.
- Qué NO se puede detectar: impresora apagada, fuera de alcance o sin papel, ni que el ticket haya salido en papel.
- Un ticket mandado con la impresora apagada puede salir solo al encenderla: al reimprimir se advierte que podría
  quedar duplicado.

### Cola de impresión
- Imprimir es asíncrono: el cobro y el cierre solo registran el trabajo en print_job y responden; nunca esperan a la impresora.
- Un solo worker procesa los trabajos en orden, uno a la vez (la impresora acepta una sola conexión).
- Cada intento tiene timeout (setting.print_timeout_seconds, 20 s). Se reintenta hasta setting.print_retries veces (3),
  esperando 5, 15 y 30 s. Errores que no se arreglan reintentando (impresora no instalada, charset inválido) fallan de inmediato.
- Al arrancar la app, los trabajos sin terminar se marcan como fallidos; se reimprimen a mano si hace falta.
- Con setting.print_enabled = 0 no se encola nada; reimprimir o probar responde IMPRESION_DESACTIVADA.

### Ticket de cobro de membresía
- Se imprime automáticamente al registrar el pago.
- Contenido: folio (payment.id con ceros a la izquierda), socio (número y nombre), plan, periodo (del ... al ...), monto, método de pago, empleado.
- Reimpresión: botón "Ticket" en Caja > Pagos del turno. El ticket reimpreso lleva la marca "COPIA" (arriba y abajo),
  con fecha y quién lo reimprimió. Si el pago está cancelado, la copia lo indica.
- También se imprime al dar de alta un socio con cobro (POST /api/members con plan).

### Ticket de cierre de turno
- Se imprime automáticamente al cerrar el turno.
- Contenido: empleado, hora de apertura y de cierre.
- Membresías cobradas: cantidad y monto por plan (ejemplo: 3 x Mensual = $1,350) y total de meses/membresías cobradas.
- Productos vendidos: producto, cantidad e importe, y total de piezas vendidas.
- Totales por método de pago (efectivo, tarjeta, transferencia) y total general.
- Fondo inicial, efectivo esperado, efectivo contado y diferencia.
- Incluye las observaciones del cierre, si las hay.
- Reimpresión: desde Admin > Turnos (historial, solo Administrador), marcada como "COPIA". Un turno abierto no se reimprime.

### Reglas de impresión
- Una falla de impresión NUNCA revierte el cobro ni el cierre. Se avisa en pantalla y se puede reimprimir.
  - Caja: el aviso del cobro dice que el ticket se envió ("si no sale, enciéndela y reimprímelo"); si Windows
    rechaza el trabajo, aparece otro aviso con el folio.
  - Cierre: la pantalla espera hasta 25 s el resultado del envío antes de cerrar sesión, y avisa si falló.
- Las ventas del refri no imprimen ticket por ahora (se puede agregar después).
- Cada impresión se registra en print_job (tipo, referencia, estado, intentos, error, si fue copia, quién la pidió).
- Admin > Configuración muestra el estado de la impresora, permite imprimir una prueba (incluye la línea
  "áéíóúñÑü¿¡$", que en la PT-210 sale "aeiounNu$") y configurar nombre en Windows, página de códigos, charset y pie.

## Roles y permisos
| Acción | Administrador | Recepción | Entrenador |
|---|---|---|---|
| Ver Inicio | Sí | Sí | Sí |
| Registrar entrada (Acceso) | Sí | Sí | No |
| Consultar socios y visitas | Sí | Sí | Sí |
| Crear o editar socios | Sí | Sí | No |
| Cobrar membresías | Sí | Sí | No |
| Vender en refri y reabastecer | Sí | Sí | No |
| Abrir y cerrar su propio turno | Sí | Sí | No |
| Ver cortes de todos los turnos | Sí | No | No |
| Cancelar pagos o ventas | Sí | No | No |
| Reimprimir ticket de cobro | Sí | Sí | No |
| Reimprimir ticket de cierre y probar impresora | Sí | No | No |
| Admin: empleados, precios, configuración, ingresos por mes | Sí | No | No |

## Autenticación
- Login por empleado con PIN o contraseña, guardado con BCrypt.
- Sesión por cookie HttpOnly con protección CSRF.
- Bloqueo temporal tras 5 intentos fallidos seguidos.
- El administrador inicial se crea con una variable de entorno, nunca en el código.

## Huella digital (Fase 6)
- Se guarda solo la plantilla (template) de la huella, nunca la imagen. Es el formato propio del SDK de
  DigitalPersona (One Touch for Windows 1.6.1), en member.fingerprint_template.
- El enrolamiento requiere consentimiento del socio y aviso de privacidad entregado.
  PENDIENTE: por decisión del 2026-10-08 todavía no se pide ni se guarda (fingerprint_consent_at queda vacío).
  Resolver antes de la entrega (Fase 7).
- Si el lector falla o se desconecta, el sistema sigue operando con acceso manual.
- Identificación 1 a N con las plantillas cargadas en memoria al arrancar. El SDK solo compara 1 a 1, así que se
  recorren las plantillas (~1 ms cada una), primero las de los socios que vinieron más recientemente.
- Entrada por huella: se registra sola, sin confirmación, a nombre de quien atiende (la sesión de Administrador o
  Recepción conectada más reciente). Socio vencido: se registra como vencida y se muestra la alerta roja.
  Si el mismo socio ya entró hace menos de 5 minutos, no se crea otra visita: se avisa "ya registró entrada".
- Admin > Configuración > Lector de huella (setting.fingerprint_enabled) apaga o enciende el lector.
