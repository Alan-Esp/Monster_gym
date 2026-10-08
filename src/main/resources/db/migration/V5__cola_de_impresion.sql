-- V5: cola de impresión asíncrona (Fase 5B).
-- print_job pasa de success 0/1 a un estado, porque la impresión ocurre después de responder.
-- SQLite no permite cambiar un CHECK: se reconstruye la tabla conservando las filas.

CREATE TABLE print_job_v5 (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind        TEXT    NOT NULL CHECK (kind IN ('cobro','cierre_turno','prueba')),
    ref_id      INTEGER,
    is_copy     INTEGER NOT NULL DEFAULT 0 CHECK (is_copy IN (0, 1)),
    -- pendiente: en la cola; enviando: el worker lo está intentando (error = último intento fallido);
    -- entregado: la impresora recibió los bytes (puerto COM o archivo de desarrollo);
    -- en_cola_windows: Windows lo aceptó, sin confirmar que la impresora esté encendida;
    -- fallido: se agotaron los reintentos.
    status      TEXT    NOT NULL CHECK (status IN ('pendiente','enviando','entregado','en_cola_windows','fallido')),
    transport   TEXT    CHECK (transport IN ('serial','windows','archivo')),
    attempts    INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    employee_id INTEGER NOT NULL REFERENCES employee(id),
    created_at  TEXT    NOT NULL,
    finished_at TEXT
);

INSERT INTO print_job_v5 (id, kind, ref_id, is_copy, status, attempts, error, employee_id, created_at, finished_at)
SELECT id, kind, ref_id, is_copy, CASE success WHEN 1 THEN 'entregado' ELSE 'fallido' END, 1, error,
       employee_id, printed_at, printed_at
FROM print_job;

DROP TABLE print_job;
ALTER TABLE print_job_v5 RENAME TO print_job;

CREATE INDEX idx_print_job_status ON print_job(status, id);
CREATE INDEX idx_print_job_ref ON print_job(kind, ref_id);

-- Configuración de la impresora (GOOJPRT PT-210 por Bluetooth SPP)
INSERT OR IGNORE INTO setting (key, value) VALUES
    ('printer_transport',     'serial'),
    ('printer_port',          'COM4'),
    ('printer_codepage',      '2'),
    ('printer_charset',       'IBM850'),
    ('printer_status_check',  '1'),
    ('print_timeout_seconds', '20'),
    ('print_retries',         '3');

UPDATE setting SET value = 'PT-210' WHERE key = 'printer_name' AND value = '';
