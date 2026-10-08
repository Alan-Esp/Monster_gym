package com.monstergym.payment;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.member.Member;
import com.monstergym.member.MemberRepository;
import com.monstergym.plan.Plan;
import com.monstergym.plan.PlanRepository;
import com.monstergym.shift.Shift;
import com.monstergym.shift.ShiftRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final MemberRepository members;
    private final PlanRepository plans;
    private final ShiftRepository shifts;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, MemberRepository members, PlanRepository plans,
                          ShiftRepository shifts, Clock clock) {
        this.payments = payments;
        this.members = members;
        this.plans = plans;
        this.shifts = shifts;
        this.clock = clock;
    }

    /** Cobra un plan: crea el payment y la membership en el turno abierto del empleado. */
    @Transactional
    public PaymentReceipt charge(EmployeePrincipal employee, long memberId, long planId, String method) {
        Shift shift = shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurnoParaCobrar);
        Member member = members.findById(memberId)
                .filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el socio."));
        Plan plan = plans.findById(planId)
                .filter(Plan::active)
                .orElseThrow(() -> ApiException.invalidField("planId", "El plan elegido no existe."));

        MembershipPeriod period = MembershipPeriod.next(
                payments.validUntil(member.id()).orElse(null), LocalDate.now(clock), plan.days());
        // El precio se toma del plan en este momento y queda guardado en el pago
        long paymentId = payments.insertPayment(member.id(), plan.id(), plan.priceCents(), method,
                shift.id(), employee.id(), Times.format(LocalDateTime.now(clock)));
        payments.insertMembership(member.id(), plan.id(), paymentId, period.startsOn(), period.endsOn());
        return payments.findReceipt(paymentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<PaymentReceipt> currentShiftPayments(EmployeePrincipal employee) {
        Shift shift = shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurno);
        return payments.findByShift(shift.id());
    }

    @Transactional
    public PaymentReceipt voidPayment(long paymentId, String reason) {
        PaymentReceipt receipt = payments.findReceipt(paymentId)
                .orElseThrow(() -> ApiException.notFound("No se encontró el pago."));
        if (receipt.voidedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAGO_CANCELADO", "El pago ya estaba cancelado.");
        }
        boolean shiftOpen = shifts.findById(receipt.shiftId()).map(Shift::isOpen).orElse(false);
        if (!shiftOpen) {
            throw new ApiException(HttpStatus.CONFLICT, "TURNO_CERRADO",
                    "No se puede cancelar un pago de un turno ya cerrado.");
        }
        payments.voidPayment(paymentId, Times.format(LocalDateTime.now(clock)), reason.trim());
        return payments.findReceipt(paymentId).orElseThrow();
    }
}
