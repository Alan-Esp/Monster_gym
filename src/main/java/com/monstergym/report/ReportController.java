package com.monstergym.report;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/income-monthly")
    public List<ReportService.MonthIncome> incomeMonthly(@RequestParam(defaultValue = "6") int months) {
        return service.incomeByMonth(Math.clamp(months, 1, 24));
    }

    @GetMapping("/summary")
    public ReportService.Summary summary() {
        return service.summary();
    }
}
