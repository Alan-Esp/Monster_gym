-- V6: la PT-210 se usa solo por la cola de Windows y sin acentos.
-- Probado con el equipo real (2026-10-08):
--  * El puerto COM del Bluetooth acepta datos aunque la impresora esté apagada (los suelta al encenderla)
--    y la impresora no contesta consultas de estado: el transporte directo por COM no detectaba nada más
--    que la cola de Windows, así que se quitó.
--  * La impresora no sale del modo chino con FS . en ninguna página de códigos: cualquier byte >= 0x80
--    sale como carácter chino. Con US-ASCII los acentos se imprimen sin tilde (Ñ → N) y ¿ ¡ se omiten.

DELETE FROM setting WHERE key IN ('printer_transport', 'printer_port', 'printer_status_check');

UPDATE setting SET value = 'US-ASCII' WHERE key = 'printer_charset';
UPDATE setting SET value = '0'        WHERE key = 'printer_codepage';
