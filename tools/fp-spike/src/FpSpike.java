import com.digitalpersona.onetouch.DPFPDataPurpose;
import com.digitalpersona.onetouch.DPFPFeatureSet;
import com.digitalpersona.onetouch.DPFPGlobal;
import com.digitalpersona.onetouch.DPFPSample;
import com.digitalpersona.onetouch.DPFPTemplate;
import com.digitalpersona.onetouch.capture.DPFPCapture;
import com.digitalpersona.onetouch.capture.DPFPCapturePriority;
import com.digitalpersona.onetouch.capture.event.DPFPDataAdapter;
import com.digitalpersona.onetouch.capture.event.DPFPDataEvent;
import com.digitalpersona.onetouch.capture.event.DPFPImageQualityAdapter;
import com.digitalpersona.onetouch.capture.event.DPFPImageQualityEvent;
import com.digitalpersona.onetouch.capture.event.DPFPReaderStatusAdapter;
import com.digitalpersona.onetouch.capture.event.DPFPReaderStatusEvent;
import com.digitalpersona.onetouch.processing.DPFPEnrollment;
import com.digitalpersona.onetouch.processing.DPFPFeatureExtraction;
import com.digitalpersona.onetouch.processing.DPFPImageQualityException;
import com.digitalpersona.onetouch.readers.DPFPReaderDescription;
import com.digitalpersona.onetouch.readers.DPFPReadersCollection;
import com.digitalpersona.onetouch.verification.DPFPVerification;
import com.digitalpersona.onetouch.verification.DPFPVerificationResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fase 6A: prueba de concepto del lector U.are.U 4500 con el One Touch for Windows SDK (Java).
 * 1) detecta el lector, 2) enrola con 4 muestras, 3) verifica una quinta huella.
 * Independiente del backend: no usa Spring ni la base de datos.
 */
public class FpSpike {

    private static final long TIMEOUT_SEGUNDOS = 30;
    private static final Path SALIDA = Path.of("out", "plantilla.fpt");

    public static void main(String[] args) throws Exception {
        System.out.println("=== Monster Gym - prueba del lector de huella ===\n");

        // 1) Detectar el lector
        DPFPReadersCollection lectores = DPFPGlobal.getReadersFactory().getReaders();
        if (lectores == null || lectores.isEmpty()) {
            System.out.println("ERROR: no se detectó ningún lector. Revisa el cable USB y el driver.");
            System.exit(1);
        }
        System.out.println("Lectores detectados: " + lectores.size());
        for (DPFPReaderDescription d : lectores) {
            System.out.printf("  - %s (%s) serie=%s tecnología=%s impresión=%s%n",
                    d.getProductName(), d.getVendor(), d.getSerialNumber(),
                    d.getTechnology(), d.getImpressionType());
        }
        String serie = lectores.get(0).getSerialNumber();
        System.out.println("Usando: " + serie + "\n");

        DPFPFeatureExtraction extractor = DPFPGlobal.getFeatureExtractionFactory().createFeatureExtraction();

        // 2) Enrolar: el SDK pide 4 conjuntos de características buenos
        DPFPEnrollment enrolamiento = DPFPGlobal.getEnrollmentFactory().createEnrollment();
        System.out.println("ENROLAMIENTO: el SDK necesita " + enrolamiento.getFeaturesNeeded() + " muestras del mismo dedo.");
        while (enrolamiento.getFeaturesNeeded() > 0) {
            int faltan = enrolamiento.getFeaturesNeeded();
            DPFPSample muestra = capturar(serie, "Pon el dedo en el lector (faltan " + faltan + ")...");
            try {
                DPFPFeatureSet fs = extractor.createFeatureSet(muestra, DPFPDataPurpose.DATA_PURPOSE_ENROLLMENT);
                enrolamiento.addFeatures(fs);
                System.out.println("  Muestra aceptada.");
            } catch (DPFPImageQualityException e) {
                System.out.println("  Calidad insuficiente (" + e.getCaptureFeedback() + "). Repite.");
            }
        }
        System.out.println("Estado de la plantilla: " + enrolamiento.getTemplateStatus());

        DPFPTemplate plantilla = enrolamiento.getTemplate();
        byte[] bytes = plantilla.serialize();
        Files.createDirectories(SALIDA.getParent());
        Files.write(SALIDA, bytes);
        System.out.println("\nPlantilla generada:");
        System.out.println("  Tamaño: " + bytes.length + " bytes");
        System.out.println("  Guardada en: " + SALIDA.toAbsolutePath());
        System.out.println("  Base64: " + Base64.getEncoder().encodeToString(bytes));
        System.out.printf("  Primeros bytes (hex): %s%n%n", hex(bytes, 16));

        // Simular la lectura desde la base de datos: reconstruir la plantilla desde los bytes
        DPFPTemplate desdeBd = DPFPGlobal.getTemplateFactory().createTemplate(Files.readAllBytes(SALIDA));

        // 3) Verificar una quinta huella contra la plantilla reconstruida
        DPFPVerification verificador = DPFPGlobal.getVerificationFactory().createVerification();
        verificador.setFARRequested(DPFPVerification.MEDIUM_SECURITY_FAR);

        DPFPFeatureSet fsVerif = null;
        while (fsVerif == null) {
            DPFPSample muestra = capturar(serie, "VERIFICACIÓN: pon un dedo (el mismo u otro para probar)...");
            try {
                fsVerif = extractor.createFeatureSet(muestra, DPFPDataPurpose.DATA_PURPOSE_VERIFICATION);
            } catch (DPFPImageQualityException e) {
                System.out.println("  Calidad insuficiente (" + e.getCaptureFeedback() + "). Repite.");
            }
        }

        DPFPVerificationResult r = verificador.verify(fsVerif, desdeBd);
        double far = (double) r.getFalseAcceptRate() / DPFPVerification.PROBABILITY_ONE;
        System.out.println();
        System.out.println(r.isVerified() ? ">>> COINCIDE <<<" : ">>> NO COINCIDE <<<");
        System.out.printf("FAR logrado: %.2e (umbral pedido: %.0e)%n", far,
                (double) DPFPVerification.MEDIUM_SECURITY_FAR / DPFPVerification.PROBABILITY_ONE);

        // Dato para la fase 6B: el SDK solo hace 1:1, así que la búsqueda 1:N es un ciclo de verify()
        int n = 1000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) verificador.verify(fsVerif, desdeBd);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%nRendimiento: %d comparaciones en %.0f ms (%.3f ms por comparación)%n", n, ms, ms / n);
        System.exit(0);
    }

    /** Arranca una captura, espera una muestra y detiene la captura. */
    private static DPFPSample capturar(String serie, String mensaje) throws InterruptedException {
        LinkedBlockingQueue<DPFPSample> cola = new LinkedBlockingQueue<>();
        DPFPCapture captura = DPFPGlobal.getCaptureFactory().createCapture();
        captura.setReaderSerialNumber(serie);
        captura.setPriority(DPFPCapturePriority.CAPTURE_PRIORITY_LOW);
        captura.addDataListener(new DPFPDataAdapter() {
            @Override public void dataAcquired(DPFPDataEvent e) {
                if (e != null && e.getSample() != null) cola.offer(e.getSample());
            }
        });
        captura.addImageQualityListener(new DPFPImageQualityAdapter() {
            @Override public void onImageQuality(DPFPImageQualityEvent e) {
                System.out.println("  (lector) calidad: " + e.getFeedback());
            }
        });
        captura.addReaderStatusListener(new DPFPReaderStatusAdapter() {
            @Override public void readerDisconnected(DPFPReaderStatusEvent e) {
                System.out.println("  (lector) DESCONECTADO");
            }
            @Override public void readerConnected(DPFPReaderStatusEvent e) {
                System.out.println("  (lector) conectado");
            }
        });
        try {
            captura.startCapture();
            System.out.println(mensaje);
            while (true) {
                DPFPSample s = cola.poll(TIMEOUT_SEGUNDOS, TimeUnit.SECONDS);
                if (s != null) return s;
                System.out.println("  Sigo esperando el dedo...");
            }
        } finally {
            captura.stopCapture();
        }
    }

    private static String hex(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, max); i++) sb.append(String.format("%02X ", b[i]));
        return sb.toString().trim();
    }
}
