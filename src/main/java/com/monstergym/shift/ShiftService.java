package com.monstergym.shift;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.shift.ShiftSummary.Fridge;
import com.monstergym.shift.ShiftSummary.Memberships;
import com.monstergym.shift.ShiftSummary.PlanLine;
import com.monstergym.shift.ShiftSummary.ProductLine;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ShiftService {

    static final List<String> METHODS = List.of("efectivo", "tarjeta", "transferencia");

    private final ShiftRepository shifts;
    private final Clock clock;

    public ShiftService(ShiftRepository shifts, Clock clock) {
        this.shifts = shifts;
        this.clock = clock;
    }

    @Transactional
    public ShiftSummary open(EmployeePrincipal employee, long openingCashCents) {
        if (shifts.findOpenByEmployee(employee.id()).isPresent()) {
            throw alreadyOpen();
        }
        long id;
        try {
            id = shifts.insert(employee.id(), Times.format(LocalDateTime.now(clock)), openingCashCents);
        } catch (DataAccessException e) {
            // El índice único uq_shift_open protege contra dos aperturas simultáneas
            if (shifts.findOpenByEmployee(employee.id()).isPresent()) throw alreadyOpen();
            throw e;
        }
        return summary(shifts.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ShiftSummary current(EmployeePrincipal employee) {
        return summary(shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurno));
    }

    @Transactional
    public ShiftSummary close(EmployeePrincipal employee, long countedCashCents, String notes) {
        Shift open = shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurno);
        String cleanNotes = notes == null || notes.isBlank() ? null : notes.trim();
        if (!shifts.close(open.id(), Times.format(LocalDateTime.now(clock)), countedCashCents, cleanNotes)) {
            throw ApiException.sinTurno();
        }
        return summary(shifts.findById(open.id()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<ShiftSummary> history(int limit) {
        return shifts.findRecent(limit).stream().map(this::summary).toList();
    }

    /** Efectivo esperado = fondo inicial + cobros de membresía y ventas del refri en efectivo. */
    ShiftSummary summary(Shift shift) {
        Map<String, Long> payByMethod = withAllMethods(shifts.paymentTotalsByMethod(shift.id()));
        Map<String, Long> saleByMethod = withAllMethods(shifts.saleTotalsByMethod(shift.id()));
        List<PlanLine> byPlan = shifts.paymentsByPlan(shift.id());
        List<ProductLine> byProduct = shifts.salesByProduct(shift.id());

        long memTotal = sum(payByMethod);
        long fridgeTotal = sum(saleByMethod);
        Map<String, Long> byMethod = new LinkedHashMap<>();
        METHODS.forEach(m -> byMethod.put(m, payByMethod.get(m) + saleByMethod.get(m)));

        var memberships = new Memberships(
                byPlan.stream().mapToInt(PlanLine::count).sum(), memTotal, payByMethod, byPlan);
        var fridge = new Fridge(
                shifts.saleCount(shift.id()), byProduct.stream().mapToInt(ProductLine::qty).sum(),
                fridgeTotal, saleByMethod, byProduct);

        long expected = shift.openingCashCents() + byMethod.get("efectivo");
        Long counted = shift.countedCashCents();
        return new ShiftSummary(
                shift.id(), shift.employeeName(), shift.openedAt(), shift.closedAt(),
                shift.openingCashCents(), memberships, fridge, byMethod, memTotal + fridgeTotal,
                expected, counted, counted == null ? null : counted - expected, shift.notes());
    }

    private static Map<String, Long> withAllMethods(Map<String, Long> totals) {
        Map<String, Long> out = new LinkedHashMap<>();
        METHODS.forEach(m -> out.put(m, totals.getOrDefault(m, 0L)));
        return out;
    }

    private static long sum(Map<String, Long> totals) {
        return totals.values().stream().mapToLong(Long::longValue).sum();
    }

    private static ApiException alreadyOpen() {
        return new ApiException(HttpStatus.CONFLICT, "TURNO_ABIERTO", "Ya tienes un turno abierto.");
    }
}
