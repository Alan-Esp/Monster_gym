package com.monstergym.printing;

import java.util.ArrayList;
import java.util.List;

/** Arma las líneas de un ticket a un ancho fijo de columnas: centrar, izquierda-derecha, recortar y partir. */
public class TicketBuilder {

    private final int width;
    private final List<Ticket.Line> lines = new ArrayList<>();

    public TicketBuilder(int width) {
        this.width = width;
    }

    public int width() {
        return width;
    }

    public TicketBuilder text(String text) {
        lines.add(Ticket.Line.plain(fit(text, width)));
        return this;
    }

    public TicketBuilder center(String text) {
        return add(center(text, width), false, false);
    }

    public TicketBuilder title(String text) {
        return add(center(text, width), true, true);
    }

    public TicketBuilder bold(String text) {
        return add(fit(text, width), true, false);
    }

    /** Etiqueta a la izquierda y valor (monto) pegado a la derecha; la etiqueta se recorta si no cabe. */
    public TicketBuilder leftRight(String left, String right) {
        return add(leftRight(left, right, width), false, false);
    }

    public TicketBuilder total(String left, String right) {
        return add(leftRight(left, right, width), true, true);
    }

    /** Texto largo partido en varias líneas por palabras. */
    public TicketBuilder wrap(String text) {
        wrap(text, width).forEach(l -> lines.add(Ticket.Line.plain(l)));
        return this;
    }

    public TicketBuilder separator() {
        return add("-".repeat(width), false, false);
    }

    public TicketBuilder section(String name) {
        String label = " " + name + " ";
        if (label.length() >= width) return bold(name);
        int left = (width - label.length()) / 2;
        return add("-".repeat(left) + label + "-".repeat(width - left - label.length()), true, false);
    }

    public TicketBuilder blank() {
        return add("", false, false);
    }

    public Ticket build() {
        return new Ticket(List.copyOf(lines));
    }

    private TicketBuilder add(String text, boolean bold, boolean tall) {
        lines.add(new Ticket.Line(text, bold, tall));
        return this;
    }

    // ---------- Funciones puras (se prueban por separado) ----------

    static String fit(String text, int width) {
        String clean = clean(text);
        return clean.length() <= width ? clean : clean.substring(0, width);
    }

    static String center(String text, int width) {
        String t = fit(text, width);
        return " ".repeat((width - t.length()) / 2) + t;
    }

    static String leftRight(String left, String right, int width) {
        String r = fit(right, width);
        int room = width - r.length() - 1;
        if (room <= 0) return " ".repeat(width - r.length()) + r;
        String l = fit(left, room);
        return l + " ".repeat(width - l.length() - r.length()) + r;
    }

    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.replace("\r", "").split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : clean(paragraph).split(" +")) {
                if (word.isEmpty()) continue;
                while (word.length() > width) {  // palabra más larga que el renglón
                    if (!line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
                    out.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (line.isEmpty()) line.append(word);
                else if (line.length() + 1 + word.length() <= width) line.append(' ').append(word);
                else { out.add(line.toString()); line.setLength(0); line.append(word); }
            }
            if (!line.isEmpty()) out.add(line.toString());
        }
        return out;
    }

    /** Quita saltos de línea, tabuladores y caracteres de control que romperían el formato o el ESC/POS. */
    private static String clean(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(c -> sb.append(Character.isISOControl(c) ? " " : Character.toString(c)));
        return sb.toString().strip();
    }
}
