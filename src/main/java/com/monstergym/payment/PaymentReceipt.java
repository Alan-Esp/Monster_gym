package com.monstergym.payment;

/**
 * Resultado de un cobro con todo lo que lleva el ticket (Fase 5B):
 * folio, socio, plan, periodo, monto, método, empleado y fecha y hora.
 */
public record PaymentReceipt(
        long id,
        String folio,
        Member member,
        Plan plan,
        String startsOn,
        String endsOn,
        long amountCents,
        String method,
        String employeeName,
        String paidAt,
        long shiftId,
        String voidedAt,
        String voidReason) {

    public record Member(long id, String number, String fullName) {
    }

    public record Plan(long id, String name, int days) {
    }

    public static String folio(long paymentId) {
        return String.format("%06d", paymentId);
    }
}
