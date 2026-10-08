package com.monstergym.visit;

import java.util.List;

/** Respuestas de /api/visits y /api/dashboard. */
public final class VisitDtos {

    private VisitDtos() {
    }

    /** Entrada de la bitácora. status = estado del socio al momento de entrar (status_at_entry). */
    public record VisitItem(long id, String time, String checkedInAt, long memberId, String number, String name,
                            String method, String status) {
    }

    public record MemberRef(long id, String number, String fullName, String plan, String validUntil,
                            Integer daysLeft) {
    }

    /** Resultado de registrar una entrada. */
    public record RegisteredVisit(long id, String checkedInAt, String method, String statusAtEntry, MemberRef member) {
    }

    /**
     * Bitácora de un día. insideNow siempre es el aforo actual; weekCount cuenta desde el lunes de esa semana
     * hasta el final del día pedido.
     */
    public record VisitDay(String date, List<VisitItem> items, int total, int fingerprintPct, int insideNow,
                           int weekCount, boolean manualAccess) {
    }
}
