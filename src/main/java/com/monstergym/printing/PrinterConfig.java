package com.monstergym.printing;

import com.monstergym.setting.SettingRepository;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Configuración de impresión leída de la tabla setting en cada trabajo
 * (así un cambio en Admin aplica desde el siguiente ticket, sin reiniciar).
 */
public record PrinterConfig(
        boolean enabled,
        String printerName,     // nombre de la impresora en Windows
        int codepage,           // n de ESC t n
        String charsetName,     // charset de Java; US-ASCII = sin acentos (la PT-210 no sale del modo chino)
        int timeoutSeconds,
        int retries,
        int width,
        String gymName,
        String footer) {

    public static PrinterConfig load(SettingRepository s) {
        return new PrinterConfig(
                s.getBool("print_enabled", true),
                s.get("printer_name").orElse("").trim(),
                Math.clamp(s.getInt("printer_codepage", 0), 0, 255),
                s.get("printer_charset").orElse("US-ASCII").trim(),
                Math.clamp(s.getInt("print_timeout_seconds", 20), 1, 120),
                Math.clamp(s.getInt("print_retries", 3), 0, 10),
                Math.clamp(s.getInt("ticket_chars_per_line", 32), 24, 64),
                s.get("gym_name").orElse("Monster Gym"),
                s.get("ticket_footer").orElse(""));
    }

    /** Charset configurado; si no existe en Java se usa ASCII (los acentos salen sin tilde, nunca basura). */
    public Charset charset() {
        return isSupported(charsetName) ? Charset.forName(charsetName) : StandardCharsets.US_ASCII;
    }

    public static boolean isSupported(String charsetName) {
        try {
            return charsetName != null && !charsetName.isBlank() && Charset.isSupported(charsetName);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Resumen para el ticket de prueba y el estado en Admin. */
    public String describe() {
        return "Windows: " + printerName + ", ESC t " + codepage + ", " + charsetName + ", " + width + " col";
    }
}
