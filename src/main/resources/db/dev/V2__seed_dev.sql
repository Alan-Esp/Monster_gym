-- V2__seed_dev.sql  –  Datos de ejemplo (solo perfil dev).
-- PIN de todos los empleados: 1234  (BCrypt strength 10).

-- ── Planes ────────────────────────────────────────────────────────────────────
INSERT INTO plan (name, days, price_cents) VALUES
    ('Semanal',    7,   15000),
    ('Quincenal',  15,  28000),
    ('Mensual',    30,  45000),
    ('Trimestral', 90,  120000),
    ('Anual',      365, 420000);

-- ── Empleados ─────────────────────────────────────────────────────────────────
INSERT INTO employee (name, username, role, shift_label, pin_hash) VALUES
    ('Sergio Ramírez', 'sergio.ramirez', 'administrador', 'Matutino',      '$2a$10$Mw2sruU3GrC0mNvSOJaII.mjxZNjwx8VFzKcnr3b1TvzFUMLJIASC'),
    ('Ana López',      'ana.lopez',      'recepcion',     'Vespertino',    '$2a$10$Mw2sruU3GrC0mNvSOJaII.mjxZNjwx8VFzKcnr3b1TvzFUMLJIASC'),
    ('Miguel Torres',  'miguel.torres',  'entrenador',    'Matutino',      '$2a$10$Mw2sruU3GrC0mNvSOJaII.mjxZNjwx8VFzKcnr3b1TvzFUMLJIASC'),
    ('Lucía Méndez',   'lucia.mendez',   'recepcion',     'Fin de semana', '$2a$10$Mw2sruU3GrC0mNvSOJaII.mjxZNjwx8VFzKcnr3b1TvzFUMLJIASC');

UPDATE employee SET active = 0 WHERE username = 'lucia.mendez';

-- ── Socios ────────────────────────────────────────────────────────────────────
-- id  number  nombre                   estado esperado
--  1  0412    Daniela Ortega           activa    (+21 días)
--  2  0087    Luis Fernando Cruz       activa   (+158 días)
--  3  0233    Mariana Salas            por_vencer (+0 días = hoy)
--  4  0398    Jorge Medina             vencida   (-2 días)
--  5  0151    Andrea Villalobos        activa    (+54 días)
--  6  0276    Ricardo Peña             activa    (+15 días)
--  7  0019    Karla Domínguez          por_vencer (+1 día)
--  8  0305    Óscar Galindo            por_vencer (+2 días)
--  9  0144    Paola Herrera            por_vencer (+3 días)
-- 10  0360    Tomás Aguilar            vencida   (-5 días)
-- 11  0201    Fernanda Ríos            activa    (+12 días)
-- 12  0500    Hugo Navarro             vencida   (sin membresía)
INSERT INTO member (number, full_name, phone, joined_on) VALUES
    ('0412', 'Daniela Ortega',     '5512348890', date('now', 'localtime', '-90 days')),
    ('0087', 'Luis Fernando Cruz', '5590871123', date('now', 'localtime', '-400 days')),
    ('0233', 'Mariana Salas',      '5533217765', date('now', 'localtime', '-60 days')),
    ('0398', 'Jorge Medina',       '5544102290', date('now', 'localtime', '-30 days')),
    ('0151', 'Andrea Villalobos',  '5577810043', date('now', 'localtime', '-200 days')),
    ('0276', 'Ricardo Peña',       '5522096618', date('now', 'localtime', '-120 days')),
    ('0019', 'Karla Domínguez',    '5566304471', date('now', 'localtime', '-180 days')),
    ('0305', 'Óscar Galindo',      '5511883920', date('now', 'localtime', '-14 days')),
    ('0144', 'Paola Herrera',      '5599025537', date('now', 'localtime', '-45 days')),
    ('0360', 'Tomás Aguilar',      '5544718802', date('now', 'localtime', '-100 days')),
    ('0201', 'Fernanda Ríos',      '5530567719', date('now', 'localtime', '-75 days')),
    ('0500', 'Hugo Navarro',       '5512345678', date('now', 'localtime', '-20 days'));

-- ── Turno abierto (Sergio) ────────────────────────────────────────────────────
INSERT INTO shift (employee_id, opened_at, opening_cash_cents)
VALUES (1, date('now', 'localtime') || 'T08:00:00', 50000);

-- ── Pagos del turno (shift_id=1) ──────────────────────────────────────────────
-- Inserción ordenada por hora (de madrugada a tarde) para facilitar lectura.
-- plan_id: 1=Semanal  2=Quincenal  3=Mensual  4=Trimestral  5=Anual
INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at) VALUES
    (2,  5, 420000, 'transferencia', 1, 1, date('now', 'localtime') || 'T08:00:00'),  -- payment 1  Luis:    Anual
    (3,  3,  45000, 'tarjeta',       1, 1, date('now', 'localtime') || 'T08:30:00'),  -- payment 2  Mariana: Mensual
    (4,  1,  15000, 'efectivo',      1, 1, date('now', 'localtime') || 'T08:45:00'),  -- payment 3  Jorge:   Semanal
    (10, 3,  45000, 'efectivo',      1, 1, date('now', 'localtime') || 'T09:00:00'),  -- payment 4  Tomás:   Mensual
    (7,  3,  45000, 'efectivo',      1, 1, date('now', 'localtime') || 'T09:00:00'),  -- payment 5  Karla:   Mensual
    (9,  2,  28000, 'tarjeta',       1, 1, date('now', 'localtime') || 'T10:47:00'),  -- payment 6  Paola:   Quincenal
    (8,  1,  15000, 'efectivo',      1, 1, date('now', 'localtime') || 'T12:05:00'),  -- payment 7  Óscar:   Semanal
    (1,  3,  45000, 'efectivo',      1, 1, date('now', 'localtime') || 'T13:20:00'),  -- payment 8  Daniela: Mensual
    (5,  4, 120000, 'transferencia', 1, 1, date('now', 'localtime') || 'T14:58:00'),  -- payment 9  Andrea:  Trimestral
    (11, 2,  28000, 'efectivo',      1, 1, date('now', 'localtime') || 'T15:40:00'),  -- payment 10 Fernanda:Quincenal
    (6,  3,  45000, 'tarjeta',       1, 1, date('now', 'localtime') || 'T16:12:00');  -- payment 11 Ricardo: Mensual

-- ── Membresías ─────────────────────────────────────────────────────────────────
-- starts_on = ends_on − (plan.days − 1)
-- payment_id asignados en el orden de inserción anterior:
--   1=Luis  2=Mariana  3=Jorge  4=Tomás  5=Karla  6=Paola
--   7=Óscar  8=Daniela  9=Andrea  10=Fernanda  11=Ricardo
INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on) VALUES
    (2,  5,  1, date('now', 'localtime', '-206 days'), date('now', 'localtime', '+158 days')), -- Luis:    Anual 365d
    (3,  3,  2, date('now', 'localtime',  '-29 days'), date('now', 'localtime')),               -- Mariana: Mensual ends hoy
    (4,  1,  3, date('now', 'localtime',   '-8 days'), date('now', 'localtime',  '-2 days')),   -- Jorge:   Semanal vencida
    (10, 3,  4, date('now', 'localtime',  '-34 days'), date('now', 'localtime',  '-5 days')),   -- Tomás:   Mensual vencida
    (7,  3,  5, date('now', 'localtime',  '-28 days'), date('now', 'localtime',  '+1 day')),    -- Karla:   Mensual por_vencer
    (9,  2,  6, date('now', 'localtime',  '-11 days'), date('now', 'localtime',  '+3 days')),   -- Paola:   Quincenal por_vencer
    (8,  1,  7, date('now', 'localtime',   '-4 days'), date('now', 'localtime',  '+2 days')),   -- Óscar:   Semanal por_vencer
    (1,  3,  8, date('now', 'localtime',   '-8 days'), date('now', 'localtime', '+21 days')),   -- Daniela: Mensual activa
    (5,  4,  9, date('now', 'localtime',  '-35 days'), date('now', 'localtime', '+54 days')),   -- Andrea:  Trimestral activa
    (11, 2, 10, date('now', 'localtime',   '-2 days'), date('now', 'localtime', '+12 days')),   -- Fernanda:Quincenal activa
    (6,  3, 11, date('now', 'localtime',  '-14 days'), date('now', 'localtime', '+15 days'));   -- Ricardo: Mensual activa
-- member_id=12 (Hugo Navarro): sin membresía → vencida en la vista

-- ── Visitas de hoy ─────────────────────────────────────────────────────────────
INSERT INTO visit (member_id, checked_in_at, method, status_at_entry, employee_id) VALUES
    (1,  date('now', 'localtime') || 'T16:38:00', 'huella', 'activa',     1),
    (2,  date('now', 'localtime') || 'T16:21:00', 'huella', 'activa',     1),
    (3,  date('now', 'localtime') || 'T16:05:00', 'manual', 'por_vencer', 1),
    (11, date('now', 'localtime') || 'T15:47:00', 'huella', 'activa',     1),
    (5,  date('now', 'localtime') || 'T15:30:00', 'huella', 'activa',     1),
    (6,  date('now', 'localtime') || 'T15:12:00', 'huella', 'activa',     1),
    (7,  date('now', 'localtime') || 'T14:55:00', 'manual', 'por_vencer', 1),
    (8,  date('now', 'localtime') || 'T14:31:00', 'huella', 'por_vencer', 1);

-- ── Productos del refri ────────────────────────────────────────────────────────
INSERT INTO product (name, size, price_cents, stock, capacity, min_stock) VALUES
    ('Agua natural',      '1 L',    1500,  18, 24, 5),
    ('Agua mineral',      '600 ml', 1800,  10, 24, 5),
    ('Bebida isotónica',  '600 ml', 2500,   4, 18, 5),
    ('Bebida energética', '473 ml', 3800,  12, 24, 5),
    ('Proteína lista',    '330 ml', 5500,   2, 12, 3),
    ('Café frío',         '300 ml', 4000,   8, 12, 3),
    ('Barra de proteína', '60 g',   3500,  15, 20, 4),
    ('Jugo verde',        '500 ml', 4500,   0, 10, 3);

-- ── Ventas del día ─────────────────────────────────────────────────────────────
INSERT INTO sale (shift_id, employee_id, total_cents, method, created_at) VALUES
    (1, 1, 3000, 'efectivo', date('now', 'localtime') || 'T16:32:00'),
    (1, 1, 9000, 'tarjeta',  date('now', 'localtime') || 'T16:10:00'),
    (1, 1, 2500, 'efectivo', date('now', 'localtime') || 'T15:48:00'),
    (1, 1, 3800, 'efectivo', date('now', 'localtime') || 'T15:20:00');

INSERT INTO sale_item (sale_id, product_id, qty, unit_price_cents) VALUES
    (1, 1, 2, 1500),  -- 2× Agua natural
    (2, 5, 1, 5500),  -- 1× Proteína lista
    (2, 7, 1, 3500),  -- 1× Barra de proteína
    (3, 3, 1, 2500),  -- 1× Bebida isotónica
    (4, 4, 1, 3800);  -- 1× Bebida energética

INSERT INTO stock_movement (product_id, delta, reason, employee_id, sale_id, created_at) VALUES
    (1, -2, 'venta', 1, 1, date('now', 'localtime') || 'T16:32:00'),
    (5, -1, 'venta', 1, 2, date('now', 'localtime') || 'T16:10:00'),
    (7, -1, 'venta', 1, 2, date('now', 'localtime') || 'T16:10:00'),
    (3, -1, 'venta', 1, 3, date('now', 'localtime') || 'T15:48:00'),
    (4, -1, 'venta', 1, 4, date('now', 'localtime') || 'T15:20:00');
