package com.monstergym.setting;

import com.monstergym.common.ApiException;
import com.monstergym.printing.PrinterConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Configuración general (solo Administrador). PUT actualiza solo los campos que vienen en el cuerpo. */
@RestController
@RequestMapping("/api/settings")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class SettingController {

    public record Settings(String gymName, int capacity, int warnDays, boolean manualAccess, boolean sound,
                           int insideMinutes, Printing printing) {
    }

    /** Impresión de tickets (Fase 5B). */
    public record Printing(boolean enabled, String printerName, int codepage, String charset, String ticketFooter,
                           int timeoutSeconds, int retries) {
    }

    public record SettingsUpdate(
            @Size(max = 60, message = "El nombre es demasiado largo (máximo 60 caracteres).") String gymName,
            @Min(value = 1, message = "El aforo debe ser de 1 a 2000 personas.")
            @Max(value = 2000, message = "El aforo debe ser de 1 a 2000 personas.") Integer capacity,
            @Min(value = 0, message = "Los días de aviso deben ser de 0 a 30.")
            @Max(value = 30, message = "Los días de aviso deben ser de 0 a 30.") Integer warnDays,
            Boolean manualAccess,
            Boolean sound,
            @Min(value = 15, message = "El tiempo dentro debe ser de 15 a 600 minutos.")
            @Max(value = 600, message = "El tiempo dentro debe ser de 15 a 600 minutos.") Integer insideMinutes,
            Boolean printEnabled,
            @Size(max = 120, message = "El nombre de la impresora es demasiado largo.") String printerName,
            @Min(value = 0, message = "La página de códigos debe ser de 0 a 255.")
            @Max(value = 255, message = "La página de códigos debe ser de 0 a 255.") Integer printerCodepage,
            @Size(max = 40, message = "El charset es demasiado largo.") String printerCharset,
            @Size(max = 200, message = "El pie del ticket es demasiado largo (máximo 200 caracteres).")
            String ticketFooter,
            @Min(value = 5, message = "El tiempo de espera debe ser de 5 a 120 segundos.")
            @Max(value = 120, message = "El tiempo de espera debe ser de 5 a 120 segundos.") Integer printTimeoutSeconds,
            @Min(value = 0, message = "Los reintentos deben ser de 0 a 10.")
            @Max(value = 10, message = "Los reintentos deben ser de 0 a 10.") Integer printRetries) {
    }

    private final SettingRepository settings;

    public SettingController(SettingRepository settings) {
        this.settings = settings;
    }

    @GetMapping
    public Settings get() {
        PrinterConfig p = PrinterConfig.load(settings);
        return new Settings(
                settings.get("gym_name").orElse("Monster Gym"),
                settings.getInt("capacity", 120),
                settings.getInt("warn_days", 3),
                settings.getBool("manual_access", true),
                settings.getBool("sound", true),
                settings.getInt("inside_minutes", 120),
                new Printing(p.enabled(), p.printerName(), p.codepage(), p.charsetName(), p.footer(),
                        p.timeoutSeconds(), p.retries()));
    }

    @PutMapping
    @Transactional
    public Settings update(@Valid @RequestBody SettingsUpdate u) {
        if (u.gymName() != null) {
            if (u.gymName().isBlank()) throw ApiException.invalidField("gymName", "El nombre del gimnasio es obligatorio.");
            settings.set("gym_name", u.gymName().trim());
        }
        if (u.capacity() != null) settings.set("capacity", u.capacity().toString());
        if (u.warnDays() != null) settings.set("warn_days", u.warnDays().toString());
        if (u.manualAccess() != null) settings.set("manual_access", u.manualAccess() ? "1" : "0");
        if (u.sound() != null) settings.set("sound", u.sound() ? "1" : "0");
        if (u.insideMinutes() != null) settings.set("inside_minutes", u.insideMinutes().toString());

        if (u.printEnabled() != null) settings.set("print_enabled", u.printEnabled() ? "1" : "0");
        if (u.printerName() != null) {
            if (u.printerName().isBlank()) {
                throw ApiException.invalidField("printerName", "Escribe el nombre de la impresora en Windows.");
            }
            settings.set("printer_name", u.printerName().trim());
        }
        if (u.printerCodepage() != null) settings.set("printer_codepage", u.printerCodepage().toString());
        if (u.printerCharset() != null) {
            if (!PrinterConfig.isSupported(u.printerCharset().trim())) {
                throw ApiException.invalidField("printerCharset",
                        "Ese charset no existe en Java. Ejemplos: US-ASCII (sin acentos), IBM850, windows-1252.");
            }
            settings.set("printer_charset", u.printerCharset().trim());
        }
        if (u.ticketFooter() != null) settings.set("ticket_footer", u.ticketFooter().trim());
        if (u.printTimeoutSeconds() != null) settings.set("print_timeout_seconds", u.printTimeoutSeconds().toString());
        if (u.printRetries() != null) settings.set("print_retries", u.printRetries().toString());
        return get();
    }
}
