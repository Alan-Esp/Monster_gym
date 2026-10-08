package com.monstergym;

import com.monstergym.payment.MembershipPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipPeriodTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 8);

    @Test
    void vigente_empiezaAlDiaSiguienteDelVencimiento() {
        MembershipPeriod p = MembershipPeriod.next(HOY.plusDays(10), HOY, 30);
        assertThat(p.startsOn()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(p.endsOn()).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    void venceHoy_sigueVigente_empiezaManana() {
        MembershipPeriod p = MembershipPeriod.next(HOY, HOY, 7);
        assertThat(p.startsOn()).isEqualTo(HOY.plusDays(1));
        assertThat(p.endsOn()).isEqualTo(HOY.plusDays(7));
    }

    @Test
    void vencida_empiezaHoy() {
        MembershipPeriod p = MembershipPeriod.next(HOY.minusDays(1), HOY, 15);
        assertThat(p.startsOn()).isEqualTo(HOY);
        assertThat(p.endsOn()).isEqualTo(HOY.plusDays(14));
    }

    @Test
    void sinMembresia_empiezaHoy() {
        MembershipPeriod p = MembershipPeriod.next(null, HOY, 365);
        assertThat(p.startsOn()).isEqualTo(HOY);
        assertThat(p.endsOn()).isEqualTo(LocalDate.of(2027, 10, 7));
    }
}
