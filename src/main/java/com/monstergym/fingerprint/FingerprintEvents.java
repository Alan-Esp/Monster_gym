package com.monstergym.fingerprint;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events de /api/fingerprint/events. Cada pestaña de Administrador o Recepción con sesión abierta
 * queda suscrita. El suscriptor más reciente es "quien atiende": a su nombre se registran las entradas por
 * huella. Al cerrar sesión (o vencer la sesión) su suscripción se cierra.
 * Eventos: estado, identificado, no_reconocido, enrolamiento.
 */
@Component
public class FingerprintEvents implements HttpSessionListener {

    private static final Logger log = LoggerFactory.getLogger(FingerprintEvents.class);
    private static final long HEARTBEAT_SECONDS = 20;

    private record Subscriber(SseEmitter emitter, long employeeId, String sessionId) {
    }

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fingerprint-sse");
        t.setDaemon(true);
        return t;
    });

    public FingerprintEvents() {
        // El comentario periódico detecta pestañas cerradas y evita que un proxy corte la conexión
        heartbeat.scheduleWithFixedDelay(this::ping, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Nueva suscripción; first es el primer evento que recibe (el estado actual del lector). */
    public SseEmitter subscribe(long employeeId, String sessionId, String firstName, Object firstData) {
        SseEmitter emitter = new SseEmitter(0L);  // sin timeout: la conexión dura lo que la pestaña
        Subscriber s = new Subscriber(emitter, employeeId, sessionId);
        emitter.onCompletion(() -> subscribers.remove(s));
        emitter.onTimeout(() -> subscribers.remove(s));
        emitter.onError(e -> subscribers.remove(s));
        subscribers.add(s);
        deliver(s, SseEmitter.event().name(firstName).data(firstData, MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void send(String name, Object data) {
        for (Subscriber s : subscribers) {
            deliver(s, SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        }
    }

    /** Empleado que atiende: el de la suscripción más reciente que sigue abierta. */
    public OptionalLong attendingEmployee() {
        Subscriber last = null;
        for (Subscriber s : subscribers) last = s;
        return last == null ? OptionalLong.empty() : OptionalLong.of(last.employeeId());
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** Cierra las suscripciones de una sesión (logout o sesión vencida). */
    public void closeSession(String sessionId) {
        for (Subscriber s : subscribers) {
            if (s.sessionId().equals(sessionId)) {
                subscribers.remove(s);
                s.emitter().complete();
            }
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        closeSession(se.getSession().getId());
    }

    private void ping() {
        for (Subscriber s : subscribers) deliver(s, SseEmitter.event().comment("ping"));
    }

    private void deliver(Subscriber s, SseEmitter.SseEventBuilder event) {
        try {
            s.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // Pestaña cerrada o conexión rota
            log.debug("Se cerró una suscripción de huella: {}", e.getMessage());
            subscribers.remove(s);
            try {
                s.emitter().completeWithError(e);
            } catch (RuntimeException ignored) {
                // ya estaba cerrada
            }
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        for (Subscriber s : subscribers) s.emitter().complete();
        subscribers.clear();
    }
}
