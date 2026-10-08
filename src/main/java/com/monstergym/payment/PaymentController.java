package com.monstergym.payment;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.printing.PrintInfo;
import com.monstergym.printing.PrintService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@RequestMapping("/api/payments")
public class PaymentController {

    public static final String METHOD_REGEX = "efectivo|tarjeta|transferencia";
    public static final String METHOD_MESSAGE = "Elige un método de pago válido: efectivo, tarjeta o transferencia.";

    public record ChargeRequest(
            @NotNull(message = "Elige un socio.") Long memberId,
            @NotNull(message = "Elige un plan.") Long planId,
            @NotNull(message = METHOD_MESSAGE) @Pattern(regexp = METHOD_REGEX, message = METHOD_MESSAGE)
            String method) {
    }

    public record VoidRequest(
            @NotBlank(message = "Escribe el motivo de la cancelación.")
            @Size(max = 200, message = "El motivo es demasiado largo (máximo 200 caracteres).")
            String reason) {
    }

    private final PaymentService service;
    private final PrintService printing;

    public PaymentController(PaymentService service, PrintService printing) {
        this.service = service;
        this.printing = printing;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public PaymentWithPrint charge(@AuthenticationPrincipal EmployeePrincipal employee,
                                   @Valid @RequestBody ChargeRequest body) {
        PaymentReceipt receipt = service.charge(employee, body.memberId(), body.planId(), body.method());
        // El cobro ya está guardado: el ticket se encola aparte y su falla nunca lo revierte
        return new PaymentWithPrint(receipt, printing.autoPayment(receipt.id(), employee));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public List<PaymentReceipt> list(@AuthenticationPrincipal EmployeePrincipal employee,
                                     @RequestParam(defaultValue = "current") String shift) {
        if (!"current".equals(shift)) {
            throw ApiException.invalidField("shift", "Por ahora solo se admite shift=current.");
        }
        return service.currentShiftPayments(employee);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public PaymentReceipt voidPayment(@PathVariable long id, @Valid @RequestBody VoidRequest body) {
        return service.voidPayment(id, body.reason());
    }

    /** Reimpresión del ticket de cobro, marcada COPIA. */
    @PostMapping("/{id}/ticket")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public PrintInfo reprint(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id) {
        return printing.reprintPayment(id, employee);
    }
}
