CREATE TABLE plan (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    UNIQUE NOT NULL,
    days        INTEGER NOT NULL CHECK (days > 0),
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE employee (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    username        TEXT    UNIQUE NOT NULL,
    role            TEXT    NOT NULL CHECK (role IN ('administrador','recepcion','entrenador')),
    shift_label     TEXT,
    pin_hash        TEXT    NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until    TEXT,
    active          INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE member (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    number                 TEXT    UNIQUE NOT NULL,
    full_name              TEXT    NOT NULL,
    phone                  TEXT,
    notes                  TEXT,
    joined_on              TEXT    NOT NULL,
    fingerprint_template   BLOB,
    fingerprint_consent_at TEXT,
    active                 INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE shift (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_id        INTEGER NOT NULL REFERENCES employee(id),
    opened_at          TEXT    NOT NULL,
    closed_at          TEXT,
    opening_cash_cents INTEGER NOT NULL CHECK (opening_cash_cents >= 0),
    counted_cash_cents INTEGER
);

CREATE UNIQUE INDEX uq_shift_open ON shift(employee_id) WHERE closed_at IS NULL;

CREATE TABLE payment (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id    INTEGER NOT NULL REFERENCES member(id),
    plan_id      INTEGER NOT NULL REFERENCES plan(id),
    amount_cents INTEGER NOT NULL CHECK (amount_cents >= 0),
    method       TEXT    NOT NULL CHECK (method IN ('efectivo','tarjeta','transferencia')),
    shift_id     INTEGER NOT NULL REFERENCES shift(id),
    employee_id  INTEGER NOT NULL REFERENCES employee(id),
    paid_at      TEXT    NOT NULL,
    voided_at    TEXT,
    void_reason  TEXT
);

CREATE TABLE membership (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id  INTEGER NOT NULL REFERENCES member(id),
    plan_id    INTEGER NOT NULL REFERENCES plan(id),
    payment_id INTEGER NOT NULL REFERENCES payment(id),
    starts_on  TEXT    NOT NULL,
    ends_on    TEXT    NOT NULL,
    CHECK (ends_on >= starts_on)
);

CREATE TABLE visit (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    member_id       INTEGER NOT NULL REFERENCES member(id),
    checked_in_at   TEXT    NOT NULL,
    method          TEXT    NOT NULL CHECK (method IN ('huella','manual')),
    status_at_entry TEXT    NOT NULL CHECK (status_at_entry IN ('activa','por_vencer','vencida')),
    employee_id     INTEGER NOT NULL REFERENCES employee(id)
);

CREATE TABLE product (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,
    size        TEXT,
    price_cents INTEGER NOT NULL CHECK (price_cents >= 0),
    stock       INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    capacity    INTEGER NOT NULL CHECK (capacity > 0),
    min_stock   INTEGER NOT NULL CHECK (min_stock >= 0),
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))
);

CREATE TABLE sale (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    shift_id    INTEGER NOT NULL REFERENCES shift(id),
    employee_id INTEGER NOT NULL REFERENCES employee(id),
    total_cents INTEGER NOT NULL CHECK (total_cents >= 0),
    method      TEXT    NOT NULL CHECK (method IN ('efectivo','tarjeta','transferencia')),
    created_at  TEXT    NOT NULL,
    voided_at   TEXT,
    void_reason TEXT
);

CREATE TABLE sale_item (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id          INTEGER NOT NULL REFERENCES sale(id),
    product_id       INTEGER NOT NULL REFERENCES product(id),
    qty              INTEGER NOT NULL CHECK (qty > 0),
    unit_price_cents INTEGER NOT NULL CHECK (unit_price_cents >= 0)
);

CREATE TABLE stock_movement (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id  INTEGER NOT NULL REFERENCES product(id),
    delta       INTEGER NOT NULL CHECK (delta != 0),
    reason      TEXT    NOT NULL CHECK (reason IN ('venta','reabasto','merma','ajuste')),
    employee_id INTEGER NOT NULL REFERENCES employee(id),
    sale_id     INTEGER REFERENCES sale(id),
    created_at  TEXT    NOT NULL
);

CREATE TABLE print_job (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind        TEXT    NOT NULL CHECK (kind IN ('cobro','cierre_turno','prueba')),
    ref_id      INTEGER,
    is_copy     INTEGER NOT NULL DEFAULT 0 CHECK (is_copy IN (0, 1)),
    success     INTEGER NOT NULL CHECK (success IN (0, 1)),
    error       TEXT,
    employee_id INTEGER NOT NULL REFERENCES employee(id),
    printed_at  TEXT    NOT NULL
);

CREATE TABLE setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO setting (key, value) VALUES
    ('gym_name',              'Monster Gym'),
    ('capacity',              '120'),
    ('warn_days',             '3'),
    ('manual_access',         '1'),
    ('sound',                 '1'),
    ('inside_minutes',        '120'),
    ('print_enabled',         '1'),
    ('printer_name',          ''),
    ('ticket_chars_per_line', '32'),
    ('ticket_footer',         'Gracias por entrenar con nosotros.');

CREATE INDEX idx_member_full_name ON member(full_name);
CREATE INDEX idx_member_phone     ON member(phone);
CREATE INDEX idx_member_number    ON member(number);
CREATE INDEX idx_visit_date       ON visit(checked_in_at);
CREATE INDEX idx_visit_member     ON visit(member_id, checked_in_at);
CREATE INDEX idx_payment_shift    ON payment(shift_id);
CREATE INDEX idx_payment_member   ON payment(member_id);
CREATE INDEX idx_sale_shift       ON sale(shift_id);
CREATE INDEX idx_sale_item_sale   ON sale_item(sale_id);
CREATE INDEX idx_membership_ends  ON membership(member_id, ends_on);

CREATE VIEW v_member_status AS
SELECT
    m.id                                                              AS member_id,
    MAX(ms.ends_on)                                                   AS valid_until,
    CAST(
        julianday(MAX(ms.ends_on)) - julianday(date('now', 'localtime'))
    AS INTEGER)                                                       AS days_left,
    CASE
        WHEN MAX(ms.ends_on) IS NULL
          OR MAX(ms.ends_on) < date('now', 'localtime')               THEN 'vencida'
        WHEN CAST(
                 julianday(MAX(ms.ends_on))
                 - julianday(date('now', 'localtime'))
             AS INTEGER)
             <= CAST((SELECT value FROM setting WHERE key = 'warn_days') AS INTEGER)
                                                                      THEN 'por_vencer'
        ELSE                                                               'activa'
    END                                                               AS status
FROM member m
LEFT JOIN (
    SELECT ms2.member_id, ms2.ends_on
    FROM   membership ms2
    JOIN   payment p ON p.id = ms2.payment_id AND p.voided_at IS NULL
) ms ON ms.member_id = m.id
GROUP BY m.id;
