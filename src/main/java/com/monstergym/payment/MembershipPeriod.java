package com.monstergym.payment;

import java.time.LocalDate;

/** Periodo de una membresía nueva según la vigencia actual del socio. */
public record MembershipPeriod(LocalDate startsOn, LocalDate endsOn) {

    /**
     * Si la membresía sigue vigente (validUntil >= hoy) la nueva empieza al día siguiente del vencimiento;
     * si ya venció o no existe, empieza hoy. Termina en startsOn + días (Mensual del 8 oct al 7 nov).
     */
    public static MembershipPeriod next(LocalDate validUntil, LocalDate today, int planDays) {
        LocalDate start = validUntil != null && !validUntil.isBefore(today) ? validUntil.plusDays(1) : today;
        return new MembershipPeriod(start, start.plusDays(planDays));
    }
}
