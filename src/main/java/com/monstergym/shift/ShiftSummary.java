package com.monstergym.shift;

import java.util.List;
import java.util.Map;

/**
 * Corte de caja de un turno. Todo el dinero en centavos.
 * countedCashCents y differenceCents son null mientras el turno siga abierto.
 */
public record ShiftSummary(
        long id,
        String employeeName,
        String openedAt,
        String closedAt,
        long openingCashCents,
        Memberships memberships,
        Fridge fridge,
        Map<String, Long> byMethod,
        long totalCents,
        long expectedCashCents,
        Long countedCashCents,
        Long differenceCents) {

    public record Memberships(int count, long totalCents, Map<String, Long> byMethod, List<PlanLine> byPlan) {
    }

    public record Fridge(int salesCount, int units, long totalCents, Map<String, Long> byMethod,
                         List<ProductLine> byProduct) {
    }

    public record PlanLine(String plan, int count, long totalCents) {
    }

    public record ProductLine(String product, int qty, long totalCents) {
    }
}
