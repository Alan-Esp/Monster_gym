-- V3: planes base (también en producción) y clave de búsqueda de socios.

-- ── Planes: solo Semanal, Quincenal, Mensual y Anual ─────────────────────────
INSERT INTO plan (name, days, price_cents, active) VALUES
    ('Semanal',    7,  12000, 1),
    ('Quincenal', 15,  18000, 1),
    ('Mensual',   30,  30000, 1),
    ('Anual',    365, 300000, 1)
ON CONFLICT(name) DO UPDATE SET
    days        = excluded.days,
    price_cents = excluded.price_cents,
    active      = 1;

-- Otros planes (p. ej. Trimestral del seed de dev) quedan inactivos: tienen pagos y no se borran
UPDATE plan SET active = 0 WHERE name NOT IN ('Semanal', 'Quincenal', 'Mensual', 'Anual');

-- ── Búsqueda de socios ────────────────────────────────────────────────────────
-- Nombre en minúsculas, sin acentos ni espacios. La aplicación la calcula al crear o editar
-- un socio y la llena al arrancar para los socios existentes (search_key = '').
ALTER TABLE member ADD COLUMN search_key TEXT NOT NULL DEFAULT '';
CREATE INDEX idx_member_search_key ON member(search_key);
