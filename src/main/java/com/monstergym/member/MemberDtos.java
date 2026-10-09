package com.monstergym.member;

import com.monstergym.payment.PaymentReceipt;

import java.util.List;
import java.util.Map;

/** Respuestas de /api/members. */
public final class MemberDtos {

    private MemberDtos() {
    }

    /** Ítem de lista según api.md: { id, number, fullName, phone, plan, validUntil, status }. */
    public record MemberItem(long id, String number, String fullName, String phone, String plan,
                             String validUntil, String status) {
    }

    /**
     * Página de resultados. counts respeta la búsqueda (q) pero no el filtro de estado,
     * para mostrar cuántos hay en cada pestaña. registered = total de socios activos sin filtros.
     */
    public record MemberPage(List<MemberItem> items, int total, int page, int pageSize, int registered,
                             Map<String, Integer> counts) {
    }

    public record Visit(String checkedInAt, String method, String statusAtEntry) {
    }

    public record MemberDetail(long id, String number, String fullName, String phone, String notes,
                               String joinedOn, String plan, String validUntil, Integer daysLeft, String status,
                               boolean hasFingerprint, List<PaymentReceipt> payments, List<Visit> visits) {
    }

    /** Alta de socio: payment es null si no se cobró un plan. */
    public record CreatedMember(MemberDetail member, PaymentReceipt payment) {
    }
}
