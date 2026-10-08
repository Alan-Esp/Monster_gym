package com.monstergym.visit;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.visit.VisitDtos.RegisteredVisit;
import com.monstergym.visit.VisitDtos.VisitDay;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/visits")
public class VisitController {

    public record VisitRequest(
            @NotNull(message = "Elige un socio.") Long memberId,
            @NotNull(message = "Indica el método de entrada.")
            @Pattern(regexp = "manual", message = "Por ahora solo se registra la entrada manual.")
            String method,
            Boolean confirm) {
    }

    private final VisitService service;
    private final Clock clock;

    public VisitController(VisitService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public RegisteredVisit register(@AuthenticationPrincipal EmployeePrincipal employee,
                                    @Valid @RequestBody VisitRequest body) {
        return service.registerManual(employee, body.memberId(), Boolean.TRUE.equals(body.confirm()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public VisitDay day(@RequestParam(required = false) String date) {
        LocalDate day;
        try {
            day = date == null || date.isBlank() ? LocalDate.now(clock) : LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.invalidField("date", "La fecha debe tener el formato AAAA-MM-DD.");
        }
        return service.day(day);
    }
}
