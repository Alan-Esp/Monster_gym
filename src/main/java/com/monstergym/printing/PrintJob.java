package com.monstergym.printing;

/** Fila de print_job tal como la devuelve GET /api/print-jobs/{id}. */
public record PrintJob(
        long id,
        String kind,            // cobro | cierre_turno | prueba
        Long refId,             // payment.id o shift.id
        boolean isCopy,
        String status,          // pendiente | enviando | entregado | en_cola_windows | fallido
        String transport,       // serial | windows | archivo
        int attempts,
        String error,           // último error (también mientras se reintenta)
        long employeeId,
        String createdAt,
        String finishedAt) {

    public static final String PENDIENTE = "pendiente";
    public static final String ENVIANDO = "enviando";
    public static final String FALLIDO = "fallido";
}
