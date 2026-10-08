package com.monstergym.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea el administrador inicial a partir de MONSTER_ADMIN_USERNAME, MONSTER_ADMIN_PIN y
 * MONSTER_ADMIN_NAME (opcional). Solo actúa si no existe ningún administrador activo.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    static final int MIN_PIN_LENGTH = 4;
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final String username;
    private final String name;
    private final String pin;

    public AdminBootstrap(EmployeeRepository employees, PasswordEncoder encoder,
                          @Value("${app.admin.username:}") String username,
                          @Value("${app.admin.name:Administrador}") String name,
                          @Value("${app.admin.pin:}") String pin) {
        this.employees = employees;
        this.encoder = encoder;
        this.username = username == null ? "" : username.trim();
        this.name = name == null || name.isBlank() ? "Administrador" : name.trim();
        this.pin = pin == null ? "" : pin;
    }

    @Override
    public void run(ApplicationArguments args) {
        createIfMissing();
    }

    /** Devuelve true si creó el administrador. */
    public boolean createIfMissing() {
        if (employees.existsActiveAdmin()) {
            return false;
        }
        if (username.isEmpty() || pin.isEmpty()) {
            log.warn("No hay ningún administrador activo. Define MONSTER_ADMIN_USERNAME y MONSTER_ADMIN_PIN "
                    + "y reinicia la aplicación para crearlo.");
            return false;
        }
        if (pin.length() < MIN_PIN_LENGTH) {
            log.error("MONSTER_ADMIN_PIN debe tener al menos {} caracteres; no se creó el administrador.",
                    MIN_PIN_LENGTH);
            return false;
        }
        if (employees.existsUsername(username)) {
            log.error("El usuario '{}' ya existe y no es administrador activo; no se creó el administrador.",
                    username);
            return false;
        }
        employees.insert(name, username, Role.ADMINISTRADOR, encoder.encode(pin));
        log.info("Administrador inicial '{}' creado.", username);
        return true;
    }
}
