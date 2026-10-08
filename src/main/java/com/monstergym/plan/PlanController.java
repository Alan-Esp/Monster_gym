package com.monstergym.plan;

import com.monstergym.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    public record PlanResponse(long id, String name, int days, long priceCents) {
        static PlanResponse of(Plan p) {
            return new PlanResponse(p.id(), p.name(), p.days(), p.priceCents());
        }
    }

    public record PriceRequest(
            @NotNull(message = "Captura el precio.")
            @Min(value = 0, message = "El precio no puede ser negativo.")
            @Max(value = 10_000_000, message = "El precio es demasiado alto.")
            Long priceCents) {
    }

    private final PlanRepository plans;

    public PlanController(PlanRepository plans) {
        this.plans = plans;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public List<PlanResponse> list() {
        return plans.findActive().stream().map(PlanResponse::of).toList();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    @Transactional
    public PlanResponse updatePrice(@PathVariable long id, @Valid @RequestBody PriceRequest body) {
        Plan plan = plans.findById(id)
                .filter(Plan::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el plan."));
        plans.updatePrice(id, body.priceCents());
        return PlanResponse.of(new Plan(plan.id(), plan.name(), plan.days(), body.priceCents(), true));
    }
}
