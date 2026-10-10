package com.monstergym.auth;

import com.monstergym.common.ApiException;
import com.monstergym.employee.EmployeeAdminService;
import com.monstergym.employee.EmployeeAdminService.EmployeeItem;
import com.monstergym.shift.Shift;
import com.monstergym.shift.ShiftRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AuthController {

    private static final String USERNAME_REGEX = "^\\s*[A-Za-z0-9._-]{3,30}\\s*$";
    private static final String USERNAME_MSG = "Usuario de 3 a 30 caracteres: letras, números, punto, guion o guion bajo.";
    private static final String PIN_MSG = "El PIN o contraseña debe tener entre 4 y 72 caracteres.";

    public record LoginRequest(
            @NotBlank(message = "Escribe tu usuario.") @Size(max = 64, message = "Usuario demasiado largo.")
            String username,
            @NotBlank(message = "Escribe tu PIN o contraseña.") @Size(max = 72, message = "PIN demasiado largo.")
            String pin) {
    }

    public record ShiftRef(long id, String openedAt) {
    }

    public record MeResponse(String name, String initials, String role, ShiftRef shift) {
    }

    public record SetupStatus(boolean needsAdmin) {
    }

    public record SetupRequest(
            @NotBlank(message = "Escribe el nombre.") @Size(max = 60, message = "Nombre demasiado largo.") String name,
            @NotNull(message = USERNAME_MSG) @Pattern(regexp = USERNAME_REGEX, message = USERNAME_MSG) String username,
            @NotNull(message = PIN_MSG) @Size(min = 4, max = 72, message = PIN_MSG) String pin) {
    }

    private final AuthService authService;
    private final ShiftRepository shifts;
    private final SecurityContextRepository contextRepository;
    private final EmployeeRepository employees;
    private final EmployeeAdminService employeeAdmin;

    public AuthController(AuthService authService, ShiftRepository shifts,
                          SecurityContextRepository contextRepository, EmployeeRepository employees,
                          EmployeeAdminService employeeAdmin) {
        this.authService = authService;
        this.shifts = shifts;
        this.contextRepository = contextRepository;
        this.employees = employees;
        this.employeeAdmin = employeeAdmin;
    }

    @PostMapping("/auth/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        EmployeePrincipal principal = authService.login(body.username(), body.pin());
        startSession(principal, request, response);
        return me(principal);
    }

    /** Primer arranque: si no hay ningún administrador activo, la app pide crearlo. */
    @GetMapping("/setup")
    public SetupStatus setupStatus() {
        return new SetupStatus(!employees.existsActiveAdmin());
    }

    /**
     * Crea el primer administrador e inicia su sesión. Solo funciona mientras no exista ningún administrador
     * activo; después responde 409 (los demás empleados se dan de alta en Admin > Empleados).
     */
    @PostMapping("/setup/admin")
    public MeResponse setupAdmin(@Valid @RequestBody SetupRequest body,
                                 HttpServletRequest request, HttpServletResponse response) {
        EmployeeItem created;
        synchronized (this) {
            if (employees.existsActiveAdmin()) {
                throw new ApiException(HttpStatus.CONFLICT, "YA_CONFIGURADO",
                        "El sistema ya tiene un administrador. Inicia sesión.");
            }
            created = employeeAdmin.create(body.name(), body.username(), Role.ADMINISTRADOR.dbValue(), null, body.pin());
        }
        EmployeePrincipal principal = authService.login(created.username(), body.pin());
        startSession(principal, request, response);
        return me(principal);
    }

    private void startSession(EmployeePrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        // Evita fijación de sesión: si ya había una sesión, cambia su id
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(principal.role().authority())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal EmployeePrincipal principal) {
        ShiftRef shift = shifts.findOpenByEmployee(principal.id())
                .map(s -> new ShiftRef(s.id(), s.openedAt()))
                .orElse(null);
        return new MeResponse(principal.name(), initials(principal.name()), principal.role().dbValue(), shift);
    }

    static String initials(String name) {
        return Arrays.stream(name.trim().split("\\s+"))
                .filter(w -> !w.isEmpty())
                .limit(2)
                .map(w -> w.substring(0, 1).toUpperCase())
                .collect(Collectors.joining());
    }
}
