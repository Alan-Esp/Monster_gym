package com.monstergym.fingerprint;

import com.monstergym.common.ApiException;
import com.monstergym.fingerprint.FingerprintReader.Capture;
import com.monstergym.fingerprint.FingerprintReader.Template;
import com.monstergym.member.Member;
import com.monstergym.member.MemberRepository;
import com.monstergym.setting.SettingRepository;
import com.monstergym.visit.VisitDtos.FingerprintEntry;
import com.monstergym.visit.VisitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Huella digital: identificación 1 a N y enrolamiento.
 * <ul>
 * <li>Al arrancar carga en memoria las plantillas de los socios activos, primero los que vinieron más
 *     recientemente; cada socio identificado pasa al principio (casi siempre vuelven los mismos).</li>
 * <li>Cada huella se identifica en un solo hilo, se registra la visita a nombre de quien atiende
 *     (FingerprintEvents) y se avisa por SSE. Sin nadie con sesión abierta, la huella se ignora.</li>
 * <li>Enrolamiento de uno en uno. Con memberId se guarda directo en el socio; sin memberId (Nuevo socio)
 *     queda pendiente en memoria con un enrollmentId hasta que POST /api/members la reclama.</li>
 * <li>Si el lector falla o se desconecta, nada de esto rompe la app: el acceso manual sigue igual.</li>
 * </ul>
 */
@Service
public class FingerprintService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FingerprintService.class);
    static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(30);
    static final Duration PENDING_TTL = Duration.ofMinutes(30);
    static final String NOT_RECOGNIZED = "Huella no reconocida. Intenta de nuevo o usa el acceso manual.";

    /** Estado para el indicador "Huella conectada / Sin huella" y para Admin. enrolled = socios con huella. */
    public record Status(boolean enabled, boolean connected, String mode, String detail, int enrolled) {
    }

    /** Evento "enrolamiento". status: muestra, calidad, listo o error. remaining = muestras que faltan. */
    public record EnrollEvent(String enrollmentId, String status, int remaining, String message) {
    }

    private record Entry(long memberId, Template template) {
    }

    private record Pending(byte[] bytes, Template template, Instant createdAt) {
    }

    private record Session(String id, Long memberId) {
    }

    private final FingerprintReader reader;
    private final FingerprintRepository repository;
    private final MemberRepository members;
    private final VisitService visits;
    private final FingerprintEvents events;
    private final SettingRepository settings;
    private final Clock clock;

    /** Más reciente primero. Se protege con su propio candado. */
    private final List<Entry> cache = new ArrayList<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Session> session = new AtomicReference<>();
    private ExecutorService identifier;
    private ExecutorService enroller;
    private volatile boolean running;
    private boolean listening;

    private final FingerprintReader.Listener listener = new FingerprintReader.Listener() {
        @Override
        public void onCapture(Capture capture) {
            ExecutorService ex = identifier;
            if (ex != null && !ex.isShutdown()) ex.execute(() -> identify(capture));
        }

        @Override
        public void onConnectionChange(boolean connected) {
            events.send("estado", status());
        }
    };

    public FingerprintService(FingerprintReader reader, FingerprintRepository repository, MemberRepository members,
                              VisitService visits, FingerprintEvents events, SettingRepository settings, Clock clock) {
        this.reader = reader;
        this.repository = repository;
        this.members = members;
        this.visits = visits;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    // ── Ciclo de vida ──────────────────────────────────────────────────────────

    @Override
    public synchronized void start() {
        identifier = Executors.newSingleThreadExecutor(r -> daemon(r, "fingerprint-identify"));
        enroller = Executors.newSingleThreadExecutor(r -> daemon(r, "fingerprint-enroll"));
        reload();
        if (enabled()) startListening();
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        stopListening();
        identifier.shutdownNow();
        enroller.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void startListening() {
        reader.start(listener);
        listening = true;
    }

    private void stopListening() {
        if (listening) reader.stop();
        listening = false;
    }

    /** Vuelve a cargar todas las plantillas desde la base. */
    public void reload() {
        List<Entry> loaded = new ArrayList<>();
        int invalid = 0;
        for (FingerprintRepository.Row row : repository.findAllActive()) {
            try {
                loaded.add(new Entry(row.memberId(), reader.load(row.template())));
            } catch (IllegalArgumentException e) {
                invalid++;
            }
        }
        if (invalid > 0) log.warn("{} plantilla(s) de huella no se pudieron cargar", invalid);
        synchronized (cache) {
            cache.clear();
            cache.addAll(loaded);
        }
        log.info("Huella: {} plantilla(s) en memoria", loaded.size());
    }

    // ── Estado y configuración ────────────────────────────────────────────────

    public boolean enabled() {
        return settings.getBool("fingerprint_enabled", true);
    }

    /** Admin > Configuración > Lector de huella. Apagado, el lector no captura y todo es acceso manual. */
    public synchronized void setEnabled(boolean on) {
        settings.set("fingerprint_enabled", on ? "1" : "0");
        if (on && !listening) startListening();
        if (!on) stopListening();
        events.send("estado", status());
    }

    public Status status() {
        boolean on = enabled();
        int enrolled;
        synchronized (cache) {
            enrolled = cache.size();
        }
        return new Status(on, on && reader.connected(), reader.mode(),
                on ? reader.detail() : "Lector desactivado en la configuración.", enrolled);
    }

    // ── Identificación 1 a N ──────────────────────────────────────────────────

    void identify(Capture capture) {
        try {
            OptionalLong employee = events.attendingEmployee();
            if (employee.isEmpty()) {
                log.info("Huella ignorada: nadie de recepción tiene sesión abierta");
                return;
            }
            OptionalLong member = find(capture);
            if (member.isEmpty()) {
                events.send("no_reconocido", Map.of("message", NOT_RECOGNIZED));
                return;
            }
            Optional<FingerprintEntry> entry = visits.registerFingerprint(member.getAsLong(), employee.getAsLong());
            if (entry.isEmpty()) {
                remove(member.getAsLong());  // dado de baja después de cargar
                events.send("no_reconocido", Map.of("message", NOT_RECOGNIZED));
                return;
            }
            events.send("identificado", entry.get());
        } catch (RuntimeException e) {
            log.error("No se pudo registrar la entrada por huella", e);
            events.send("no_reconocido",
                    Map.of("message", "No se pudo registrar la entrada por huella. Usa el acceso manual."));
        }
    }

    /** Recorre las plantillas (el SDK solo compara 1 a 1). El socio encontrado pasa al principio. */
    OptionalLong find(Capture capture) {
        List<Entry> snapshot;
        synchronized (cache) {
            snapshot = List.copyOf(cache);
        }
        for (Entry e : snapshot) {
            if (reader.matches(capture, e.template())) {
                put(e.memberId(), e.template());
                return OptionalLong.of(e.memberId());
            }
        }
        return OptionalLong.empty();
    }

    private void put(long memberId, Template template) {
        synchronized (cache) {
            cache.removeIf(e -> e.memberId() == memberId);
            cache.add(0, new Entry(memberId, template));
        }
    }

    private void remove(long memberId) {
        synchronized (cache) {
            cache.removeIf(e -> e.memberId() == memberId);
        }
    }

    // ── Enrolamiento ──────────────────────────────────────────────────────────

    /**
     * Inicia la captura de 4 muestras en segundo plano; el progreso llega por SSE (evento enrolamiento).
     * memberId null = socio nuevo: la plantilla queda pendiente hasta POST /api/members.
     */
    public String startEnroll(Long memberId) {
        if (!enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "HUELLA_DESACTIVADA",
                    "El lector de huella está desactivado en la configuración.");
        }
        if (!reader.connected()) {
            throw new ApiException(HttpStatus.CONFLICT, "LECTOR_DESCONECTADO",
                    "El lector de huella no está conectado. Revisa el cable USB.");
        }
        if (memberId != null && members.findById(memberId).filter(Member::active).isEmpty()) {
            throw ApiException.notFound("No se encontró el socio.");
        }
        Session s = new Session(UUID.randomUUID().toString(), memberId);
        if (!session.compareAndSet(null, s)) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPTURA_EN_CURSO", "Ya hay una captura de huella en curso.");
        }
        purgeExpired();
        enroller.execute(() -> runEnroll(s));
        return s.id();
    }

    private void runEnroll(Session s) {
        EnrollEvent result;
        try {
            byte[] bytes = reader.enroll(new FingerprintReader.EnrollProgress() {
                @Override
                public void onSample(int remaining) {
                    events.send("enrolamiento", new EnrollEvent(s.id(), "muestra", remaining,
                            remaining == 0 ? "Muestra aceptada." : "Muestra aceptada. Faltan " + remaining + "."));
                }

                @Override
                public void onPoorQuality(String detail) {
                    events.send("enrolamiento", new EnrollEvent(s.id(), "calidad", -1, detail + " Repite."));
                }
            }, SAMPLE_TIMEOUT);
            Template template = reader.load(bytes);
            if (s.memberId() != null) {
                repository.save(s.memberId(), bytes);
                put(s.memberId(), template);
            } else {
                pending.put(s.id(), new Pending(bytes, template, clock.instant()));
            }
            result = new EnrollEvent(s.id(), "listo", 0, "Huella capturada.");
        } catch (FingerprintException e) {
            result = new EnrollEvent(s.id(), "error", -1, e.getMessage());
        } catch (RuntimeException e) {
            log.error("No se pudo guardar la huella", e);
            result = new EnrollEvent(s.id(), "error", -1, "No se pudo guardar la huella. Intenta de nuevo.");
        }
        session.compareAndSet(s, null);  // antes de avisar, para que se pueda reintentar de inmediato
        events.send("enrolamiento", result);
    }

    /** Cancela la captura en curso si es la indicada (p. ej. al salir de Nuevo socio). */
    public void cancelEnroll(String enrollmentId) {
        Session s = session.get();
        if (s != null && s.id().equals(enrollmentId)) reader.cancelEnroll();
    }

    /**
     * Guarda en el socio la huella pendiente de un enrolamiento sin memberId. Se llama dentro de la transacción
     * del alta: el caché se actualiza solo si la transacción se confirma.
     */
    public void attachPending(long memberId, String enrollmentId) {
        Pending p = pending.get(enrollmentId);
        if (p == null || expired(p)) {
            throw ApiException.invalidField("fingerprintEnrollmentId",
                    "La huella capturada ya no es válida. Captúrala de nuevo.");
        }
        repository.save(memberId, p.bytes());
        Runnable publish = () -> {
            pending.remove(enrollmentId);
            put(memberId, p.template());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private boolean expired(Pending p) {
        return p.createdAt().plus(PENDING_TTL).isBefore(clock.instant());
    }

    private void purgeExpired() {
        pending.values().removeIf(this::expired);
    }

    /** Para pruebas: espera a que terminen las identificaciones encoladas. */
    public void awaitIdle() throws Exception {
        identifier.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
