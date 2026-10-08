package com.monstergym.sale;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.payment.PaymentController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
@RequestMapping("/api/sales")
public class SaleController {

    public record ItemRequest(
            @NotNull(message = "Falta el producto.") Long productId,
            @NotNull(message = "Falta la cantidad.")
            @Min(value = 1, message = "La cantidad debe ser al menos 1.")
            @Max(value = 999, message = "La cantidad es demasiado grande.")
            Integer qty) {
    }

    public record SaleRequest(
            @NotNull(message = PaymentController.METHOD_MESSAGE)
            @Pattern(regexp = PaymentController.METHOD_REGEX, message = PaymentController.METHOD_MESSAGE)
            String method,
            @NotEmpty(message = "Agrega al menos un producto.")
            @Size(max = 50, message = "Demasiados productos en una venta.")
            List<@Valid ItemRequest> items) {
    }

    public record VoidRequest(
            @NotBlank(message = "Escribe el motivo de la cancelación.")
            @Size(max = 200, message = "El motivo es demasiado largo (máximo 200 caracteres).")
            String reason) {
    }

    private final SaleService service;

    public SaleController(SaleService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public SaleReceipt create(@AuthenticationPrincipal EmployeePrincipal employee,
                              @Valid @RequestBody SaleRequest body) {
        List<SaleService.Line> lines = body.items().stream()
                .map(i -> new SaleService.Line(i.productId(), i.qty())).toList();
        return service.create(employee, body.method(), lines);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public List<SaleReceipt> list(@AuthenticationPrincipal EmployeePrincipal employee,
                                  @RequestParam(defaultValue = "current") String shift,
                                  @RequestParam(defaultValue = "20") int limit) {
        if (!"current".equals(shift)) {
            throw ApiException.invalidField("shift", "Por ahora solo se admite shift=current.");
        }
        return service.currentShift(employee, Math.clamp(limit, 1, 200));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public SaleReceipt voidSale(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id,
                                @Valid @RequestBody VoidRequest body) {
        return service.voidSale(employee, id, body.reason());
    }
}
