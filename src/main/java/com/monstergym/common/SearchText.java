package com.monstergym.common;

import java.text.Normalizer;
import java.util.Locale;

/** Normalización de texto para búsquedas: sin acentos, sin espacios y en minúsculas. */
public final class SearchText {

    private SearchText() {
    }

    /** "Óscar  Galindo" → "oscargalindo"; "Peña" → "pena". */
    public static String key(String text) {
        if (text == null) return "";
        String noMarks = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /** Solo los dígitos: "55 1234-8890" → "5512348890"; "#0412" → "0412". */
    public static String digits(String text) {
        return text == null ? "" : text.replaceAll("\\D", "");
    }

    /** Escapa los comodines de LIKE (usar con ESCAPE '\'). */
    public static String likeContains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
