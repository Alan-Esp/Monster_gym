package com.monstergym.printing;

/**
 * Lo que agregan el cobro, el cierre y las reimpresiones a su respuesta ("print").
 * status: pendiente (en la cola), desactivada (setting.print_enabled = 0) o fallido (ni siquiera se encoló).
 * El resultado final se consulta con GET /api/print-jobs/{jobId}.
 */
public record PrintInfo(Long jobId, String status, String error) {

    public static PrintInfo queued(long jobId) {
        return new PrintInfo(jobId, PrintJob.PENDIENTE, null);
    }

    public static PrintInfo disabled() {
        return new PrintInfo(null, "desactivada", null);
    }

    public static PrintInfo failed(String error) {
        return new PrintInfo(null, PrintJob.FALLIDO, error);
    }
}
