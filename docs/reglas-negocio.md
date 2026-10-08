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
- Impresora térmica de 58 mm: ancho imprimible aproximado de 48 mm, normalmente 32 caracteres por línea (confirmar con el modelo real).
- Son comprobantes internos, no facturas. Llevan la leyenda "Comprobante no fiscal".
- Cabecera de todos los tickets: setting.gym_name, folio, fecha y hora, nombre del empleado. Pie: setting.ticket_footer.
- Los acentos y la ñ requieren configurar la página de códigos de la impresora (por ejemplo CP858).

### Ticket de cobro de membresía
- Se imprime automáticamente al registrar el pago.
- Contenido: folio (payment.id con ceros a la izquierda), socio (número y nombre), plan, periodo (del ... al ...), monto, método de pago, empleado.
- Reimpresión: botón en el pago. El ticket reimpreso lleva la marca "COPIA".

### Ticket de cierre de turno
- Se imprime automáticamente al cerrar el turno.
- Contenido: empleado, hora de apertura y de cierre.
- Membresías cobradas: cantidad y monto por plan (ejemplo: 3 x Mensual = $1,350) y total de meses/membresías cobradas.
- Productos vendidos: producto, cantidad e importe, y total de piezas vendidas.
- Totales por método de pago (efectivo, tarjeta, transferencia) y total general.
- Fondo inicial, efectivo esperado, efectivo contado y diferencia.
- Reimpresión: desde el historial de turnos (solo Administrador), marcada como "COPIA".

### Reglas de impresión
- Una falla de impresión (sin papel, impresora apagada) NUNCA revierte el cobro ni el cierre. Se avisa en pantalla y se puede reimprimir.
- Las ventas del refri no imprimen ticket por ahora (se puede agregar después).
- Cada impresión se registra en print_job (tipo, referencia, éxito, error, si fue copia).

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
- Se guarda solo la plantilla (template) de la huella, nunca la imagen.
- El enrolamiento requiere consentimiento del socio y aviso de privacidad entregado.
- Si el lector falla o se desconecta, el sistema sigue operando con acceso manual.
- Identificación 1 a N con las plantillas cargadas en memoria al arrancar.
