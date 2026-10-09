package com.monstergym.fingerprint;

import com.digitalpersona.onetouch.DPFPCaptureFeedback;
import com.digitalpersona.onetouch.DPFPDataPurpose;
import com.digitalpersona.onetouch.DPFPFeatureSet;
import com.digitalpersona.onetouch.DPFPGlobal;
import com.digitalpersona.onetouch.DPFPSample;
import com.digitalpersona.onetouch.DPFPTemplate;
import com.digitalpersona.onetouch.capture.DPFPCapture;
import com.digitalpersona.onetouch.capture.DPFPCapturePriority;
import com.digitalpersona.onetouch.capture.event.DPFPDataAdapter;
import com.digitalpersona.onetouch.capture.event.DPFPDataEvent;
import com.digitalpersona.onetouch.capture.event.DPFPReaderStatusAdapter;
import com.digitalpersona.onetouch.capture.event.DPFPReaderStatusEvent;
import com.digitalpersona.onetouch.processing.DPFPEnrollment;
import com.digitalpersona.onetouch.processing.DPFPFeatureExtraction;
import com.digitalpersona.onetouch.processing.DPFPImageQualityException;
import com.digitalpersona.onetouch.processing.DPFPTemplateStatus;
import com.digitalpersona.onetouch.readers.DPFPReadersCollection;
import com.digitalpersona.onetouch.verification.DPFPVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * U.are.U 4500 con el One Touch for Windows SDK 1.6.1 (probado en tools/fp-spike).
 * Captura con prioridad baja, que es la que funciona sin ventana; solo un programa a la vez puede usarla, así
 * que el spike no debe correr junto con la app. Un vigilante revisa cada pocos segundos si hay lector y
 * reinicia la captura al reconectarlo. Si faltan las DLL del SDK, la app sigue funcionando sin huella.
 */
public class DigitalPersonaReader implements FingerprintReader {

    private static final Logger log = LoggerFactory.getLogger(DigitalPersonaReader.class);
    private static final long WATCH_SECONDS = 3;
    private static final Object CANCEL = new Object();

    private static final Map<DPFPCaptureFeedback, String> FEEDBACK = Map.ofEntries(
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_LIGHT, "La imagen salió muy clara: presiona un poco más."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_DARK, "La imagen salió muy oscura: presiona más suave o seca el dedo."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_NOISY, "La imagen salió borrosa: limpia el lector."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_LOW_CONTRAST, "Poco contraste: limpia el lector y el dedo."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_NOT_ENOUGH_FEATURES, "No se vio bien la huella: centra el dedo."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_NO_CENTRAL_REGION, "Centra el dedo en el lector."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_HIGH, "Baja un poco el dedo."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_LOW, "Sube un poco el dedo."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_LEFT, "Mueve el dedo a la derecha."),
            Map.entry(DPFPCaptureFeedback.CAPTURE_FEEDBACK_TOO_RIGHT, "Mueve el dedo a la izquierda."));

    private record DpCapture(DPFPFeatureSet features) implements Capture {
    }

    private record DpTemplate(DPFPTemplate template) implements Template {
    }

    private volatile Listener listener;
    private volatile boolean connected;
    private volatile String detail = "Iniciando el lector de huella.";
    private volatile BlockingQueue<Object> enrollQueue;
    private volatile String abortMessage;

    private DPFPCapture capture;
    private DPFPFeatureExtraction extractor;
    private DPFPVerification verifier;
    private ScheduledExecutorService watcher;

    @Override
    public synchronized void start(Listener listener) {
        this.listener = listener;
        try {
            extractor = DPFPGlobal.getFeatureExtractionFactory().createFeatureExtraction();
            verifier = DPFPGlobal.getVerificationFactory().createVerification();
            verifier.setFARRequested(DPFPVerification.MEDIUM_SECURITY_FAR);
            capture = DPFPGlobal.getCaptureFactory().createCapture();
            capture.setPriority(DPFPCapturePriority.CAPTURE_PRIORITY_LOW);  // null = cualquier lector
            capture.addDataListener(new DPFPDataAdapter() {
                @Override
                public void dataAcquired(DPFPDataEvent e) {
                    if (e != null && e.getSample() != null) onSample(e.getSample());
                }
            });
            capture.addReaderStatusListener(new DPFPReaderStatusAdapter() {
                @Override
                public void readerConnected(DPFPReaderStatusEvent e) {
                    setConnected(true, "Lector de huella conectado.");
                }

                @Override
                public void readerDisconnected(DPFPReaderStatusEvent e) {
                    setConnected(false, "Lector de huella desconectado.");
                }
            });
        } catch (LinkageError | RuntimeException e) {
            // Sin SDK/RTE instalado: la app sigue con acceso manual
            log.error("No se pudo iniciar el SDK de DigitalPersona", e);
            detail = "No se encontró el software del lector (instala el RTE de DigitalPersona).";
            capture = null;
            return;
        }
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fingerprint-watch");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(this::watch, 0, WATCH_SECONDS, TimeUnit.SECONDS);
    }

    /** Revisa si hay lector y arranca la captura cuando aparece. */
    private synchronized void watch() {
        if (capture == null) return;
        try {
            DPFPReadersCollection readers = DPFPGlobal.getReadersFactory().getReaders();
            boolean present = readers != null && !readers.isEmpty();
            if (present && !capture.isStarted()) {
                capture.startCapture();
                log.info("Captura de huella iniciada");
            }
            setConnected(present, present ? "Lector de huella conectado." : "No hay lector de huella conectado.");
        } catch (RuntimeException e) {
            log.warn("No se pudo revisar el lector de huella: {}", e.getMessage());
            setConnected(false, "El lector no responde. Revisa que ningún otro programa lo esté usando.");
        }
    }

    private void setConnected(boolean value, String text) {
        detail = text;
        if (connected == value) return;
        connected = value;
        log.info(text);
        if (!value) abortEnroll("Se desconectó el lector de huella.");
        Listener l = listener;
        if (l != null) l.onConnectionChange(value);
    }

    private void onSample(DPFPSample sample) {
        BlockingQueue<Object> q = enrollQueue;
        if (q != null) {
            q.offer(sample);
            return;
        }
        Listener l = listener;
        if (l == null) return;
        try {
            l.onCapture(new DpCapture(extractor.createFeatureSet(sample, DPFPDataPurpose.DATA_PURPOSE_VERIFICATION)));
        } catch (DPFPImageQualityException e) {
            log.debug("Huella de mala calidad: {}", e.getCaptureFeedback());
        } catch (RuntimeException e) {
            log.warn("No se pudo procesar la huella", e);
        }
    }

    @Override
    public synchronized void stop() {
        listener = null;
        abortEnroll("Se detuvo el lector de huella.");
        if (watcher != null) watcher.shutdownNow();
        try {
            if (capture != null && capture.isStarted()) capture.stopCapture();
        } catch (RuntimeException e) {
            log.warn("No se pudo detener la captura de huella", e);
        }
        connected = false;
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public byte[] enroll(EnrollProgress progress, Duration timeoutPerSample) throws FingerprintException {
        if (capture == null) throw new FingerprintException(detail);
        if (!connected) throw new FingerprintException("El lector de huella no está conectado.");
        BlockingQueue<Object> q = new LinkedBlockingQueue<>();
        synchronized (this) {
            if (enrollQueue != null) throw new FingerprintException("Ya hay una captura de huella en curso.");
            abortMessage = null;
            enrollQueue = q;
        }
        try {
            // Extractor propio: el de identificación se usa en el hilo del SDK
            DPFPFeatureExtraction enrollExtractor = DPFPGlobal.getFeatureExtractionFactory().createFeatureExtraction();
            DPFPEnrollment enrollment = DPFPGlobal.getEnrollmentFactory().createEnrollment();
            while (enrollment.getFeaturesNeeded() > 0) {
                Object item = q.poll(timeoutPerSample.toMillis(), TimeUnit.MILLISECONDS);
                if (abortMessage != null || item == CANCEL) throw new FingerprintException(abortMessage);
                if (item == null) throw new FingerprintException("No se colocó el dedo a tiempo. Intenta de nuevo.");
                DPFPFeatureSet features;
                try {
                    features = enrollExtractor.createFeatureSet((DPFPSample) item, DPFPDataPurpose.DATA_PURPOSE_ENROLLMENT);
                } catch (DPFPImageQualityException e) {
                    progress.onPoorQuality(feedback(e.getCaptureFeedback()));
                    continue;
                }
                try {
                    enrollment.addFeatures(features);
                } catch (DPFPImageQualityException e) {
                    if (enrollment.getTemplateStatus() == DPFPTemplateStatus.TEMPLATE_STATUS_FAILED) {
                        throw new FingerprintException("Las muestras no coinciden entre sí. Intenta de nuevo con el mismo dedo.");
                    }
                    progress.onPoorQuality(feedback(e.getCaptureFeedback()));
                    continue;
                }
                progress.onSample(enrollment.getFeaturesNeeded());
            }
            if (enrollment.getTemplateStatus() != DPFPTemplateStatus.TEMPLATE_STATUS_READY) {
                throw new FingerprintException("No se pudo generar la plantilla. Intenta de nuevo.");
            }
            return enrollment.getTemplate().serialize();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FingerprintException("Se interrumpió la captura de huella.");
        } catch (RuntimeException e) {
            log.error("Falla del SDK al enrolar", e);
            throw new FingerprintException("Falla del lector al capturar la huella. Intenta de nuevo.", e);
        } finally {
            enrollQueue = null;
        }
    }

    @Override
    public void cancelEnroll() {
        abortEnroll("Captura de huella cancelada.");
    }

    private void abortEnroll(String message) {
        BlockingQueue<Object> q = enrollQueue;
        if (q == null) return;
        abortMessage = message;
        q.offer(CANCEL);
    }

    private static String feedback(DPFPCaptureFeedback f) {
        return FEEDBACK.getOrDefault(f, "La huella no se leyó bien. Repite.");
    }

    @Override
    public Template load(byte[] serialized) {
        try {
            return new DpTemplate(DPFPGlobal.getTemplateFactory().createTemplate(serialized));
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalArgumentException("Plantilla de huella no válida o SDK no disponible.", e);
        }
    }

    @Override
    public boolean matches(Capture capture, Template template) {
        if (!(capture instanceof DpCapture c) || !(template instanceof DpTemplate t)) return false;
        return verifier.verify(c.features(), t.template()).isVerified();
    }

    @Override
    public String mode() {
        return "real";
    }

    @Override
    public String detail() {
        return detail;
    }
}
