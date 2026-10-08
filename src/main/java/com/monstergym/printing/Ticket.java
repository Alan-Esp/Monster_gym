package com.monstergym.printing;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ticket ya formateado: cada línea trae su texto final (alineado y recortado al ancho de la impresora).
 * bold y tall son estilos ESC/POS que no cambian el número de columnas (no se usa doble ancho).
 */
public record Ticket(List<Line> lines) {

    public record Line(String text, boolean bold, boolean tall) {
        public static Line plain(String text) {
            return new Line(text, false, false);
        }
    }

    /** Texto plano tal como sale en papel (para FakePrinter y pruebas). */
    public String toText() {
        return lines.stream().map(Line::text).collect(Collectors.joining("\n", "", "\n"));
    }
}
