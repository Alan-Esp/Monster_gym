package com.monstergym.shift;

public record Shift(
        long id,
        long employeeId,
        String employeeName,
        String openedAt,
        String closedAt,
        long openingCashCents,
        Long countedCashCents,
        String notes) {

    public boolean isOpen() {
        return closedAt == null;
    }
}
