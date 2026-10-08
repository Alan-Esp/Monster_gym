package com.monstergym.employee;

import com.monstergym.auth.Employee;
import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.auth.EmployeeRepository;
import com.monstergym.auth.Role;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** Alta, edición, activación y cambio de PIN de empleados (solo Administrador). */
@Service
public class EmployeeAdminService {

    /** Empleado para Admin; nunca incluye el hash del PIN. locked = bloqueado ahora por intentos fallidos. */
    public record EmployeeItem(long id, String name, String username, String role, String shiftLabel,
                               boolean active, boolean locked) {
    }

    private final EmployeeRepository employees;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public EmployeeAdminService(EmployeeRepository employees, PasswordEncoder encoder, Clock clock) {
        this.employees = employees;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EmployeeItem> list() {
        return employees.findAll().stream().map(this::item).toList();
    }

    @Transactional
    public EmployeeItem create(String name, String username, String role, String shiftLabel, String pin) {
        String user = cleanUsername(username);
        if (employees.existsUsername(user)) {
            throw ApiException.invalidField("username", "Ese usuario ya existe.");
        }
        long id = employees.insert(name.trim(), user, Role.fromDb(role), blankToNull(shiftLabel), encoder.encode(pin));
        return item(employees.findById(id).orElseThrow());
    }

    /**
     * Nadie puede desactivarse ni quitarse el rol de administrador a sí mismo, y siempre debe quedar
     * al menos un administrador activo.
     */
    @Transactional
    public EmployeeItem update(EmployeePrincipal actor, long id, String name, String username, String role,
                               String shiftLabel, boolean active) {
        Employee current = find(id);
        Role newRole = Role.fromDb(role);
        boolean stopsBeingAdmin = current.role() == Role.ADMINISTRADOR && current.active()
                && (newRole != Role.ADMINISTRADOR || !active);
        if (actor.id() == id && stopsBeingAdmin) {
            throw new ApiException(HttpStatus.CONFLICT, "OPERACION_NO_PERMITIDA",
                    "No puedes desactivarte ni quitarte el rol de administrador.");
        }
        if (stopsBeingAdmin && employees.countActiveAdminsExcept(id) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "OPERACION_NO_PERMITIDA",
                    "Debe quedar al menos un administrador activo.");
        }
        String user = cleanUsername(username);
        if (employees.usernameTakenByOther(user, id)) {
            throw ApiException.invalidField("username", "Ese usuario ya existe.");
        }
        employees.update(id, name.trim(), user, newRole, blankToNull(shiftLabel), active);
        return item(find(id));
    }

    @Transactional
    public EmployeeItem changePin(long id, String pin) {
        find(id);
        employees.updatePin(id, encoder.encode(pin));
        return item(find(id));
    }

    private Employee find(long id) {
        return employees.findById(id).orElseThrow(() -> ApiException.notFound("No se encontró el empleado."));
    }

    private EmployeeItem item(Employee e) {
        LocalDateTime lockedUntil = Times.parse(e.lockedUntil());
        boolean locked = lockedUntil != null && LocalDateTime.now(clock).isBefore(lockedUntil);
        return new EmployeeItem(e.id(), e.name(), e.username(), e.role().dbValue(), e.shiftLabel(), e.active(), locked);
    }

    private static String cleanUsername(String username) {
        return username.trim().toLowerCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
