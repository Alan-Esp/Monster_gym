package com.monstergym.visit;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.member.Member;
import com.monstergym.member.MemberRepository;
import com.monstergym.setting.SettingRepository;
import com.monstergym.visit.VisitDtos.MemberRef;
import com.monstergym.visit.VisitDtos.RegisteredVisit;
import com.monstergym.visit.VisitDtos.VisitDay;
import com.monstergym.visit.VisitDtos.VisitItem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class VisitService {

    static final Duration DOUBLE_ENTRY_WINDOW = Duration.ofMinutes(5);
    static final int DEFAULT_INSIDE_MINUTES = 120;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final VisitRepository visits;
    private final MemberRepository members;
    private final SettingRepository settings;
    private final Clock clock;

    public VisitService(VisitRepository visits, MemberRepository members, SettingRepository settings, Clock clock) {
        this.visits = visits;
        this.members = members;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Registra una entrada manual. Sin confirm, un socio vencido (SOCIO_VENCIDO) o una entrada del mismo socio
     * hace menos de 5 minutos (DOBLE_REGISTRO) responden 409; con confirm: true se registra de todos modos.
     */
    @Transactional
    public RegisteredVisit registerManual(EmployeePrincipal employee, long memberId, boolean confirm) {
        if (!settings.getBool("manual_access", true)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCESO_MANUAL_DESACTIVADO",
                    "El acceso manual está desactivado en la configuración.");
        }
        Member member = members.findById(memberId)
                .filter(Member::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el socio."));
        MemberRepository.StatusRow status = members.status(memberId);
        LocalDateTime now = LocalDateTime.now(clock);

        List<String> codes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if ("vencida".equals(status.status())) {
            codes.add("SOCIO_VENCIDO");
            warnings.add(status.validUntil() == null
                    ? member.fullName() + " no tiene ninguna membresía pagada."
                    : "La membresía de " + member.fullName() + " venció el "
                            + LocalDate.parse(status.validUntil()).format(DAY_MONTH) + ".");
        }
        visits.lastVisitAt(memberId).map(Times::parse).ifPresent(last -> {
            Duration since = Duration.between(last, now);
            if (since.compareTo(DOUBLE_ENTRY_WINDOW) < 0 && !since.isNegative()) {
                long min = since.toMinutes();
                codes.add("DOBLE_REGISTRO");
                warnings.add(member.fullName() + " ya registró entrada a las " + Times.format(last).substring(11, 16)
                        + (min == 0 ? " (hace menos de un minuto)." : " (hace " + min + (min == 1 ? " minuto)." : " minutos).")));
            }
        });
        if (!confirm && !codes.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, codes.get(0),
                    String.join(" ", warnings) + " ¿Registrar la entrada de todos modos?");
        }

        String checkedInAt = Times.format(now);
        long id = visits.insert(memberId, checkedInAt, "manual", status.status(), employee.id());
        return new RegisteredVisit(id, checkedInAt, "manual", status.status(),
                new MemberRef(member.id(), member.number(), member.fullName(), status.plan(), status.validUntil(),
                        status.daysLeft()));
    }

    @Transactional(readOnly = true)
    public VisitDay day(LocalDate date) {
        String from = date.toString(), to = date.plusDays(1).toString();
        List<VisitItem> items = visits.findBetween(from, to, 1000);
        int total = visits.countBetween(from, to);
        int fingerprint = visits.countFingerprintBetween(from, to);
        String monday = date.with(DayOfWeek.MONDAY).toString();
        return new VisitDay(date.toString(), items, total, total == 0 ? 0 : Math.round(fingerprint * 100f / total),
                insideNow(), visits.countBetween(monday, to), settings.getBool("manual_access", true));
    }

    /** Aforo "dentro ahora": visitas de los últimos setting.inside_minutes minutos (no se registran salidas). */
    public int insideNow() {
        int minutes = settings.getInt("inside_minutes", DEFAULT_INSIDE_MINUTES);
        LocalDateTime now = LocalDateTime.now(clock);
        // El límite superior incluye el segundo actual
        return visits.countBetween(Times.format(now.minusMinutes(minutes)), Times.format(now.plusSeconds(1)));
    }
}
