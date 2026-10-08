package com.monstergym.printing;

import com.monstergym.auth.EmployeePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Estado de la impresora, prueba y seguimiento de trabajos. Las reimpresiones viven en pagos y turnos. */
@RestController
public class PrintController {

    private final PrintService service;

    public PrintController(PrintService service) {
        this.service = service;
    }

    @GetMapping("/api/print-jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public PrintJob job(@PathVariable long id) {
        return service.job(id);
    }

    @GetMapping("/api/printer/status")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public PrintService.PrinterStatus status() {
        return service.status();
    }

    @PostMapping("/api/printer/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public PrintInfo test(@AuthenticationPrincipal EmployeePrincipal employee) {
        return service.test(employee);
    }
}
