# Modelo de datos (SQLite)

## Convenciones
- PRAGMA foreign_keys = ON y journal_mode = WAL en cada conexión.
- Claves primarias: id INTEGER PRIMARY KEY AUTOINCREMENT.
- Dinero: columnas *_cents INTEGER (centavos). Nunca REAL.
- Fechas: TEXT ISO 8601. Fecha = YYYY-MM-DD. Fecha y hora = YYYY-MM-DDTHH:MM:SS en hora local de México.
- Booleanos: INTEGER 0/1 con CHECK.
- Valores cerrados (método de pago, rol, etc.) con CHECK (col IN (...)).
- Migraciones Flyway: V1__schema.sql, V2__seed_dev.sql (solo perfil dev). Nunca editar una ya aplicada.

## Tablas

### plan
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| name | TEXT UNIQUE NOT NULL | Semanal, Quincenal, Mensual, Trimestral, Anual |
| days | INTEGER NOT NULL | Duración: 7, 15, 30, 90, 365 |
| price_cents | INTEGER NOT NULL | CHECK >= 0 |
| active | INTEGER NOT NULL DEFAULT 1 | |

### employee
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| name | TEXT NOT NULL | |
| username | TEXT UNIQUE NOT NULL | Para el login |
| role | TEXT NOT NULL | CHECK IN ('administrador','recepcion','entrenador') |
| shift_label | TEXT | Matutino, Vespertino, Fin de semana |
| pin_hash | TEXT NOT NULL | BCrypt |
| failed_attempts | INTEGER NOT NULL DEFAULT 0 | |
| locked_until | TEXT | |
| active | INTEGER NOT NULL DEFAULT 1 | |

### member
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| number | TEXT UNIQUE NOT NULL | '0412', consecutivo de 4 dígitos |
| full_name | TEXT NOT NULL | |
| phone | TEXT | 10 dígitos |
| notes | TEXT | Campo "Opcional" del formulario |
| joined_on | TEXT NOT NULL | Fecha de alta |
| fingerprint_template | BLOB | NULL hasta enrolar |
| fingerprint_consent_at | TEXT | Fecha del consentimiento |
| active | INTEGER NOT NULL DEFAULT 1 | Baja lógica, nunca borrar |

### shift
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| employee_id | INTEGER FK employee | |
| opened_at | TEXT NOT NULL | |
| closed_at | TEXT | NULL = turno abierto |
| opening_cash_cents | INTEGER NOT NULL | Fondo inicial |
| counted_cash_cents | INTEGER | Al cerrar |

Índice único parcial: un solo turno abierto por empleado (WHERE closed_at IS NULL).

### payment
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| member_id | INTEGER FK member | |
| plan_id | INTEGER FK plan | |
| amount_cents | INTEGER NOT NULL | Precio al momento del cobro |
| method | TEXT NOT NULL | CHECK IN ('efectivo','tarjeta','transferencia') |
| shift_id | INTEGER FK shift | |
| employee_id | INTEGER FK employee | |
| paid_at | TEXT NOT NULL | |
| voided_at | TEXT | Cancelación |
| void_reason | TEXT | |

### membership
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| member_id | INTEGER FK member | |
| plan_id | INTEGER FK plan | |
| payment_id | INTEGER FK payment | |
| starts_on | TEXT NOT NULL | |
| ends_on | TEXT NOT NULL | |

La vigencia actual de un socio es el MAX(ends_on) de sus membresías cuyo pago no esté cancelado.

### visit
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| member_id | INTEGER FK member | |
| checked_in_at | TEXT NOT NULL | |
| method | TEXT NOT NULL | CHECK IN ('huella','manual') |
| status_at_entry | TEXT NOT NULL | CHECK IN ('activa','por_vencer','vencida') |
| employee_id | INTEGER FK employee | Quien atendía |

### product
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| name | TEXT NOT NULL | |
| size | TEXT | '1 L', '600 ml' |
| price_cents | INTEGER NOT NULL | |
| stock | INTEGER NOT NULL | CHECK >= 0 |
| capacity | INTEGER NOT NULL | Capacidad del refri para ese producto |
| min_stock | INTEGER NOT NULL | Umbral de stock bajo |
| active | INTEGER NOT NULL DEFAULT 1 | |

### sale y sale_item
sale: id, shift_id, employee_id, total_cents, method (efectivo/tarjeta/transferencia), created_at, voided_at, void_reason.
sale_item: id, sale_id, product_id, qty (CHECK > 0), unit_price_cents.

### stock_movement
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| product_id | INTEGER FK product | |
| delta | INTEGER NOT NULL | Negativo = sale, positivo = entra |
| reason | TEXT NOT NULL | CHECK IN ('venta','reabasto','merma','ajuste') |
| employee_id | INTEGER FK employee | |
| sale_id | INTEGER FK sale | Solo si reason = 'venta' |
| created_at | TEXT NOT NULL | |

### print_job
| Columna | Tipo | Nota |
|---|---|---|
| id | INTEGER PK | |
| kind | TEXT NOT NULL | CHECK IN ('cobro','cierre_turno','prueba') |
| ref_id | INTEGER | payment.id o shift.id según kind |
| is_copy | INTEGER NOT NULL DEFAULT 0 | 1 = reimpresión (COPIA) |
| success | INTEGER NOT NULL | |
| error | TEXT | Mensaje si falló |
| employee_id | INTEGER FK employee | |
| printed_at | TEXT NOT NULL | |

### setting
key TEXT PK, value TEXT NOT NULL. Valores iniciales:
gym_name = Monster Gym, capacity = 120, warn_days = 3, manual_access = 1, sound = 1, inside_minutes = 120,
print_enabled = 1, printer_name = (nombre de la impresora en Windows), ticket_chars_per_line = 32, ticket_footer = Gracias por entrenar con nosotros.

## Vista v_member_status
Columnas: member_id, valid_until, days_left, status.
- valid_until: MAX(ends_on) de membresías con pago no cancelado.
- days_left: días entre hoy y valid_until.
- status: 'vencida' si valid_until es NULL o anterior a hoy; 'por_vencer' si days_left <= warn_days; 'activa' en otro caso.
- warn_days se lee de setting.

## Índices
- member(full_name), member(phone), member(number)
- visit(checked_in_at), visit(member_id, checked_in_at)
- payment(shift_id), payment(member_id)
- sale(shift_id), sale_item(sale_id)
- membership(member_id, ends_on)

## Datos de ejemplo (V2__seed_dev.sql)
- Tomar como base web/js/data.js (socios, planes, productos, ventas, pagos, empleados).
- Las fechas de vencimiento se calculan relativas a hoy (date('now', '+N day')) para que siempre haya socios activos, por vencer y vencidos.
- Usuario inicial de prueba con PIN conocido, solo en dev.
