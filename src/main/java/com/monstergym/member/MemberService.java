package com.monstergym.member;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.SearchText;
import com.monstergym.fingerprint.FingerprintService;
import com.monstergym.member.MemberDtos.CreatedMember;
import com.monstergym.member.MemberDtos.MemberDetail;
import com.monstergym.member.MemberDtos.MemberItem;
import com.monstergym.member.MemberDtos.MemberPage;
import com.monstergym.payment.PaymentController;
import com.monstergym.payment.PaymentReceipt;
import com.monstergym.payment.PaymentRepository;
import com.monstergym.payment.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MemberService {

    static final int PAGE_SIZE = 50;
    static final int MAX_NUMBER = 9999;
    private static final Set<String> STATUSES = Set.of("activa", "por_vencer", "vencida");

    private final MemberRepository members;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final FingerprintService fingerprints;
    private final Clock clock;

    public MemberService(MemberRepository members, PaymentRepository payments, PaymentService paymentService,
                         FingerprintService fingerprints, Clock clock) {
        this.members = members;
        this.payments = payments;
        this.paymentService = paymentService;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MemberPage list(String query, String status, int page) {
        String q = query == null ? "" : query.trim();
        String st = status == null || status.isBlank() ? null : status.trim();
        if (st != null && !STATUSES.contains(st)) {
            throw ApiException.invalidField("status", "Estado no válido: usa activa, por_vencer o vencida.");
        }
        int p = Math.max(page, 1);
        Map<String, Integer> byStatus = members.countByStatus(q);
        int all = byStatus.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("todos", all);
        counts.putAll(byStatus);
        int total = st == null ? all : counts.get(st);
        List<MemberItem> items = members.search(q, st, PAGE_SIZE, (p - 1) * PAGE_SIZE);
        return new MemberPage(items, total, p, PAGE_SIZE, members.countActive(), counts);
    }

    /**
     * Alta de socio. Si trae plan, cobra en la misma transacción: sin turno abierto no se crea nada.
     * fingerprintEnrollmentId: huella capturada antes del alta (POST /api/fingerprint/enroll), se guarda aquí.
     */
    @Transactional
    public CreatedMember create(EmployeePrincipal employee, String fullName, String phone, String notes,
                                Long planId, String method, String fingerprintEnrollmentId) {
        if (planId != null && method == null) {
            throw ApiException.invalidField("method", PaymentController.METHOD_MESSAGE);
        }
        int next = members.nextNumber();
        if (next > MAX_NUMBER) {
            throw new ApiException(HttpStatus.CONFLICT, "NUMERO_AGOTADO",
                    "Se alcanzó el número de socio máximo (9999).");
        }
        long id = members.insert(String.format("%04d", next), cleanName(fullName), cleanPhone(phone),
                cleanNotes(notes), LocalDate.now(clock).toString());
        if (fingerprintEnrollmentId != null && !fingerprintEnrollmentId.isBlank()) {
            fingerprints.attachPending(id, fingerprintEnrollmentId.trim());
        }
        PaymentReceipt receipt = planId == null ? null : paymentService.charge(employee, id, planId, method);
        return new CreatedMember(detail(id), receipt);
    }

    @Transactional(readOnly = true)
    public MemberDetail detail(long id) {
        Member m = members.findById(id)
                .filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el socio."));
        MemberRepository.StatusRow s = members.status(id);
        return new MemberDetail(m.id(), m.number(), m.fullName(), m.phone(), m.notes(), m.joinedOn(),
                s.plan(), s.validUntil(), s.daysLeft(), s.status(), m.hasFingerprint(),
                payments.findByMember(id), members.recentVisits(id, 50));
    }

    @Transactional
    public MemberDetail update(long id, String fullName, String phone, String notes) {
        members.findById(id)
                .filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el socio."));
        members.update(id, cleanName(fullName), cleanPhone(phone), cleanNotes(notes));
        return detail(id);
    }

    private static String cleanName(String name) {
        String n = name.trim().replaceAll("\\s+", " ");
        if (SearchText.key(n).isEmpty()) {
            throw ApiException.invalidField("fullName", "El nombre es obligatorio.");
        }
        return n;
    }

    /** Teléfono opcional: se guardan solo los 10 dígitos. */
    static String cleanPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = SearchText.digits(phone);
        if (digits.length() != 10) {
            throw ApiException.invalidField("phone", "El teléfono debe tener 10 dígitos.");
        }
        return digits;
    }

    private static String cleanNotes(String notes) {
        return notes == null || notes.isBlank() ? null : notes.trim();
    }
}
