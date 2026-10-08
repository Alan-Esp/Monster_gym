package com.monstergym.dashboard;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/api/dashboard")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public DashboardService.Dashboard dashboard() {
        return service.build();
    }
}
