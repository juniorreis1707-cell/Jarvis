package com.jarvis;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import nu.pattern.OpenCV;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Liga a webcam do computador (via OpenCV) e entrega o quadro mais recente já
 * recortado em quadrado, espelhado (efeito "selfie") e pronto para o JavaFX
 * desenhar dentro do portal holográfico do Jarvis.
 *
 * Tudo roda numa thread própria — se não houver webcam, ou o driver falhar,
 * {@link #isAvailable()} fica falso e a interface simplesmente mostra o núcleo
 * brilhante no lugar da câmera, sem quebrar nada.
 */
public class WebcamTool {

    /** Lado (em pixels) do quadrado entregue à interface. */
    public static final int SIZE = 360;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicLong frameCounter = new AtomicLong(0);
    private static volatile byte[] latestBgra;
    private static volatile boolean available = false;
    private static Thread captureThread;
    private static long lastDrawnFrame = 0;
    private static volatile String status = "iniciando...";

    /** Descrição curta do que está acontecendo com a webcam (para diagnóstico na interface). */
    public static String getStatus() {
        return status;
    }

    /** Tenta abrir a webcam padrão e capturar em segundo plano. Nunca lança exceção. */
    public static synchronized void start() {
        if (running.get()) return;
        if (captureThread != null && captureThread.isAlive()) {
            try { captureThread.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        status = "iniciando...";
        latestBgra = null;
        running.set(true);

        captureThread = new Thread(() -> {
            VideoCapture capture = null;
            Mat frame = new Mat();
            Mat resized = new Mat();
            Mat bgra = new Mat();
            try {
                status = "carregando biblioteca OpenCV...";
                OpenCV.loadLocally();

                // Tenta várias combinações de câmera (0..2) e driver do Windows até uma abrir.
                int[] backends = {Videoio.CAP_DSHOW, Videoio.CAP_MSMF, Videoio.CAP_ANY};
                String[] backendNames = {"DSHOW", "MSMF", "ANY"};
                Mat probe = new Mat();
                outer:
                for (int index = 0; index < 3; index++) {
                    for (int b = 0; b < backends.length; b++) {
                        status = "tentando câmera " + index + " (" + backendNames[b] + ")...";
                        VideoCapture candidate = new VideoCapture(index, backends[b]);
                        boolean ok = candidate.isOpened();
                        if (ok) {
                            // Algumas câmeras abrem mas não entregam imagem: só vale se der um quadro.
                            for (int attempt = 0; attempt < 15 && !(ok = candidate.read(probe) && !probe.empty()); attempt++) {
                                Thread.sleep(100);
                            }
                        }
                        if (ok) {
                            capture = candidate;
                            status = "câmera " + index + " (" + backendNames[b] + ") ativa";
                            break outer;
                        }
                        candidate.release();
                    }
                }
                probe.release();
                if (capture == null) {
                    status = "nenhuma câmera entregou imagem (está em uso por outro app, ou o Windows bloqueou o acesso nas configurações de privacidade > Câmera?)";
                    return;
                }
                available = true;

                while (running.get()) {
                    if (!capture.read(frame) || frame.empty()) {
                        Thread.sleep(100);
                        continue;
                    }
                    int w = frame.cols();
                    int h = frame.rows();
                    int side = Math.min(w, h);
                    Mat square = new Mat(frame, new Rect((w - side) / 2, (h - side) / 2, side, side));
                    Imgproc.resize(square, resized, new Size(SIZE, SIZE));
                    square.release();
                    Core.flip(resized, resized, 1);
                    Imgproc.cvtColor(resized, bgra, Imgproc.COLOR_BGR2BGRA);

                    byte[] buffer = new byte[SIZE * SIZE * 4];
                    bgra.get(0, 0, buffer);
                    latestBgra = buffer;
                    frameCounter.incrementAndGet();

                    Thread.sleep(40); // ~25 quadros por segundo
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                available = false; // driver/biblioteca nativa indisponível: a interface usa o núcleo no lugar
                status = "erro: " + t;
            } finally {
                if (capture != null) capture.release();
                available = false;
                running.set(false);
                frame.release();
                resized.release();
                bgra.release();
            }
        }, "jarvis-webcam");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /** True enquanto a captura está ativa (ligando ou ligada). */
    public static boolean isRunning() {
        return running.get();
    }

    /**
     * Liga/desliga/consulta a webcam do Jarvis (usado pela ferramenta de voz).
     * A imagem aparece dentro do portal do próprio Jarvis, não em outro programa.
     */
    public static String control(String acao) {
        String a = acao == null ? "" : acao.trim().toLowerCase();
        switch (a) {
            case "ligar": {
                if (available) return "A webcam já está ligada e aparecendo no portal do Jarvis.";
                start();
                for (int i = 0; i < 100 && running.get() && !available; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                return available
                        ? "Webcam ligada — a imagem aparece dentro do portal do Jarvis."
                        : "Não consegui ligar a webcam: " + status;
            }
            case "desligar":
                stop();
                status = "desligada";
                return "Webcam desligada.";
            default:
                return "Webcam " + (available ? "ligada" : "desligada") + " — " + status;
        }
    }

    /** True se a webcam foi aberta com sucesso. */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Copia o quadro mais recente para a imagem dada (de tamanho SIZE x SIZE).
     * Retorna true só se havia um quadro novo desde a última chamada.
     * Deve ser chamado na thread da interface JavaFX.
     */
    public static boolean copyLatestInto(WritableImage image) {
        byte[] data = latestBgra;
        long current = frameCounter.get();
        if (data == null || current == lastDrawnFrame) return false;
        lastDrawnFrame = current;
        image.getPixelWriter().setPixels(0, 0, SIZE, SIZE,
                PixelFormat.getByteBgraInstance(), data, 0, SIZE * 4);
        return true;
    }

    /** Para a captura e libera a webcam — chamar ao fechar o app. */
    public static synchronized void stop() {
        running.set(false);
        available = false;
        latestBgra = null;
        if (captureThread != null) captureThread.interrupt();
    }
}
