package com.monstergym.product;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.product.ProductService.ProductItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    public record RestockRequest(
            @NotNull(message = "Captura la cantidad.")
            @Min(value = -1000, message = "La cantidad es demasiado grande.")
            @Max(value = 1000, message = "La cantidad es demasiado grande.")
            Integer qty,
            @NotNull(message = "Indica el motivo: reabasto, merma o ajuste.")
            @Pattern(regexp = "reabasto|merma|ajuste", message = "Indica el motivo: reabasto, merma o ajuste.")
            String reason) {
    }

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public List<ProductItem> list() {
        return service.list();
    }

    @PostMapping("/{id}/restock")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public ProductItem restock(@AuthenticationPrincipal EmployeePrincipal employee, @PathVariable long id,
                               @Valid @RequestBody RestockRequest body) {
        if (body.qty() == 0) {
            throw ApiException.invalidField("qty", "La cantidad no puede ser cero.");
        }
        return service.adjust(employee, id, body.qty(), body.reason());
    }
}
