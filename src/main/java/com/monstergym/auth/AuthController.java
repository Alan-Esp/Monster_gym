package com.monstergym.auth;

import com.monstergym.shift.Shift;
import com.monstergym.shift.ShiftRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    private final AuthService authService;
    private final ShiftRepository shifts;
    private final SecurityContextRepository contextRepository;

    public AuthController(AuthService authService, ShiftRepository shifts,
                          SecurityContextRepository contextRepository) {
        this.authService = authService;
        this.shifts = shifts;
        this.contextRepository = contextRepository;
    }

    @PostMapping("/auth/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body,
                            HttpServletRequest request, HttpServletResponse response) {
        EmployeePrincipal principal = authService.login(body.username(), body.pin());

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

        return me(principal);
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
