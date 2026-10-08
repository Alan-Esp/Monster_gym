package com.monstergym.auth;

import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final Clock clock;
    // Hash de relleno para que un usuario inexistente tarde lo mismo que un PIN incorrecto
    private final String dummyHash;

    public AuthService(EmployeeRepository employees, PasswordEncoder encoder, Clock clock) {
        this.employees = employees;
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode("monster-gym-dummy");
    }

    /** Valida usuario y PIN/contraseña. Aplica el bloqueo temporal tras 5 intentos fallidos seguidos. */
    public EmployeePrincipal login(String username, String pin) {
        Optional<Employee> found = employees.findByUsername(username.trim());
        if (found.isEmpty() || !found.get().active()) {
            encoder.matches(pin, dummyHash);
            throw invalidCredentials();
        }
        Employee e = found.get();
        LocalDateTime now = LocalDateTime.now(clock);

        LocalDateTime lockedUntil = Times.parse(e.lockedUntil());
        if (lockedUntil != null && now.isBefore(lockedUntil)) {
            throw locked(lockedUntil, now);
        }

        if (encoder.matches(pin, e.pinHash())) {
            if (e.failedAttempts() > 0 || e.lockedUntil() != null) {
                employees.resetFailedAttempts(e.id());
            }
            return EmployeePrincipal.of(e);
        }

        // Si el bloqueo anterior ya venció, el conteo empieza de nuevo
        int attempts = (lockedUntil != null ? 0 : e.failedAttempts()) + 1;
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            LocalDateTime until = now.plus(LOCK_DURATION);
            employees.recordFailedAttempt(e.id(), attempts, Times.format(until));
            throw locked(until, now);
        }
        employees.recordFailedAttempt(e.id(), attempts, null);
        throw invalidCredentials();
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "NO_AUTENTICADO", "Usuario o PIN incorrectos.");
    }

    private static ApiException locked(LocalDateTime until, LocalDateTime now) {
        long minutes = Math.max(1, (Duration.between(now, until).getSeconds() + 59) / 60);
        return new ApiException(HttpStatus.LOCKED, "CUENTA_BLOQUEADA",
                "Cuenta bloqueada por intentos fallidos. Intenta de nuevo en " + minutes
                        + (minutes == 1 ? " minuto." : " minutos."));
    }
}
