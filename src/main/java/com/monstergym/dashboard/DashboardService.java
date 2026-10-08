package com.monstergym.dashboard;

import com.monstergym.common.Times;
import com.monstergym.setting.SettingRepository;
import com.monstergym.visit.VisitDtos.VisitItem;
import com.monstergym.visit.VisitRepository;
import com.monstergym.visit.VisitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    /** Horas que muestra la gráfica de visitas: de 06:00 a 21:00. */
    static final int FIRST_HOUR = 6, LAST_HOUR = 21;

    public record Aforo(int inside, int capacity, String peakHour) {
    }

    public record Income(long membershipsCents, long fridgeCents, long totalCents) {
    }

    public record Dashboard(Aforo aforo, int visitsToday, int visitsDeltaVsYesterday, int activeMembers,
                            int expiredCount, int expiringCount, int firstHour, List<Integer> visitsByHour,
                            int currentHourIndex, List<DashboardRepository.TopProduct> topProducts,
                            List<VisitItem> recentVisits, Income income) {
    }

    private final VisitRepository visits;
    private final VisitService visitService;
    private final DashboardRepository repo;
    private final SettingRepository settings;
    private final Clock clock;

    public DashboardService(VisitRepository visits, VisitService visitService, DashboardRepository repo,
                            SettingRepository settings, Clock clock) {
        this.visits = visits;
        this.visitService = visitService;
        this.repo = repo;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard build() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        String from = today.toString(), to = today.plusDays(1).toString();

        int visitsToday = visits.countBetween(from, to);
        // Ayer hasta la misma hora, para comparar parejo
        LocalDate yesterday = today.minusDays(1);
        int visitsYesterday = visits.countBetween(yesterday.toString(), Times.format(now.minusDays(1)));

        Map<Integer, Integer> byHour = visits.countByHour(from, to);
        List<Integer> bars = new ArrayList<>();
        for (int h = FIRST_HOUR; h <= LAST_HOUR; h++) bars.add(byHour.getOrDefault(h, 0));
        String peak = byHour.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> String.format("%02d:00", e.getKey()))
                .orElse(null);
        int hour = now.getHour();
        int currentIndex = hour >= FIRST_HOUR && hour <= LAST_HOUR ? hour - FIRST_HOUR : -1;

        Map<String, Integer> status = repo.membersByStatus();
        int expiring = status.getOrDefault("por_vencer", 0);

        long mem = repo.membershipIncome(from, to), fridge = repo.fridgeIncome(from, to);

        return new Dashboard(
                new Aforo(visitService.insideNow(), settings.getInt("capacity", 120), peak),
                visitsToday, visitsToday - visitsYesterday,
                status.getOrDefault("activa", 0) + expiring, status.getOrDefault("vencida", 0), expiring,
                FIRST_HOUR, bars, currentIndex,
                repo.topProducts(from, to, 3),
                visits.recent(6),
                new Income(mem, fridge, mem + fridge));
    }
}
