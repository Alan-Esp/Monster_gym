package com.monstergym.shift;

import com.monstergym.auth.EmployeePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/shifts")
public class ShiftController {

    /** Tope de captura: $1,000,000.00 */
    private static final long MAX_CASH_CENTS = 100_000_000L;

    public record OpenRequest(
            @NotNull(message = "Captura el fondo inicial.")
            @Min(value = 0, message = "El fondo inicial no puede ser negativo.")
            @Max(value = MAX_CASH_CENTS, message = "El fondo inicial es demasiado alto.")
            Long openingCashCents) {
    }

    public record CloseRequest(
            @NotNull(message = "Captura el efectivo contado.")
            @Min(value = 0, message = "El efectivo contado no puede ser negativo.")
            @Max(value = MAX_CASH_CENTS, message = "El efectivo contado es demasiado alto.")
            Long countedCashCents,
            @Size(max = 500, message = "Las observaciones son demasiado largas (máximo 500 caracteres).")
            String notes) {
    }

    private final ShiftService service;

    public ShiftController(ShiftService service) {
        this.service = service;
    }

    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public ShiftSummary open(@AuthenticationPrincipal EmployeePrincipal employee,
                             @Valid @RequestBody OpenRequest body) {
        return service.open(employee, body.openingCashCents());
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public ShiftSummary current(@AuthenticationPrincipal EmployeePrincipal employee) {
        return service.current(employee);
    }

    @PostMapping("/close")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public ShiftSummary close(@AuthenticationPrincipal EmployeePrincipal employee,
                              @Valid @RequestBody CloseRequest body) {
        return service.close(employee, body.countedCashCents(), body.notes());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public List<ShiftSummary> history(@RequestParam(defaultValue = "50") int limit) {
        return service.history(Math.clamp(limit, 1, 200));
    }
}
