package com.monstergym.shift;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.printing.PrintInfo;
import com.monstergym.printing.PrintService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** Respuesta del cierre: el corte más "print" con el trabajo del ticket automático. */
    public record ClosedShift(@JsonUnwrapped ShiftSummary summary, PrintInfo print) {
    }

    private final ShiftService service;
    private final PrintService printing;

    public ShiftController(ShiftService service, PrintService printing) {
        this.service = service;
        this.printing = printing;
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
    public ClosedShift close(@AuthenticationPrincipal EmployeePrincipal employee,
                             @Valid @RequestBody CloseRequest body) {
        ShiftSummary summary = service.close(employee, body.countedCashCents(), body.notes());
        // El cierre ya está guardado: el ticket se encola aparte y su falla nunca lo revierte
        return new ClosedShift(summary, printing.autoShiftClose(summary.id(), employee));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public List<ShiftSummary> history(@RequestParam(defaultValue = "50") int limit) {
        return service.history(Math.clamp(limit, 1, 200));
    }

    /** Reimpresión del ticket de cierre, marcada COPIA (solo Administrador, desde el historial). */
    @PostMapping("/{id}/ticket")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public PrintInfo reprint(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id) {
        return printing.reprintShift(id, employee);
    }
}
