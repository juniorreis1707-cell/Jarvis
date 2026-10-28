package com.jarvis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entrada e saída de voz do Jarvis.
 *
 * Entrada (ouvir): usa o Vosk, que roda 100% offline. Precisa de um modelo
 * baixado manualmente (veja o README) na pasta "vosk-model" na raiz do
 * projeto.
 *
 * Saída (falar): usa o Piper TTS (motor local, sem nuvem) com a voz "Razo"
 * (masculina, pt-BR). O Piper gera um arquivo .wav a partir do texto, e o
 * próprio Java toca esse .wav.
 */
public class AudioTool {

    private static final String MODEL_PATH = "vosk-model";
    private static final int SAMPLE_RATE = 16000;

    // Detecção de silêncio: em vez de gravar um tempo fixo, o Jarvis grava
    // até perceber que a pessoa parou de falar. Isso evita cortar a fala no
    // meio (se ela demorar) e evita esperar o tempo todo (se ela for rápida).
    //
    // O limiar de "silêncio" é CALIBRADO automaticamente nos primeiros
    // instantes de cada gravação (mede o ruído de fundo do ambiente/mic),
    // em vez de um número fixo — um valor fixo que funciona num ambiente
    // silencioso pode nunca ser atingido num ambiente com mais ruído,
    // fazendo o Jarvis "não perceber" que a pessoa parou de falar.
    private static final int CALIBRATION_CHUNKS = 4;      // ~0,5s de calibração no início de cada gravação
    private static final double SILENCE_MULTIPLIER = 2.2; // fala precisa ser N vezes mais alta que o ruído de fundo
    private static final double MIN_SILENCE_THRESHOLD = 250; // piso mínimo, mesmo em ambiente muito silencioso
    private static final long SILENCE_TIMEOUT_MS = 1100;   // silêncio contínuo para considerar que a pessoa parou
    private static final long MIN_SPEECH_MS = 400;         // tempo mínimo antes de poder encerrar por silêncio

    // Caminhos do Piper (veja o README para onde baixar e colocar cada um).
    private static final Path PIPER_EXE = Paths.get("piper", "piper.exe");
    private static final Path PIPER_VOICE = Paths.get("piper", "voices", "pt-BR-razo-medium.onnx");
    private static final Path TEMP_WAV = Paths.get("piper", "jarvis-fala-temp.wav");

    private static Model model;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Model getModel() throws IOException {
        if (model == null) {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(MODEL_PATH);
        }
        return model;
    }

    /**
     * Grava do microfone padrão até perceber que a pessoa parou de falar
     * (silêncio por SILENCE_TIMEOUT_MS), com "maxSeconds" como limite de
     * segurança caso o silêncio nunca seja detectado. Devolve o texto
     * transcrito, ou string vazia se não entender nada.
     */
    public static String listen(int maxSeconds) {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            return "Erro: nenhum microfone compatível foi encontrado.";
        }

        TargetDataLine microphone = null;
        // "try-with-resources" no Recognizer: garante que ele é sempre fechado ao
        // sair deste bloco (mesmo se der erro no meio). Sem isso, cada vez que o
        // Jarvis ouvia algo, um pedaço de memória nativa do Vosk ficava preso pra
        // sempre — com a escuta automática rodando o tempo todo, isso estourava a
        // memória em pouco tempo e derrubava o programa inteiro (crash da JVM).
        try (Recognizer recognizer = new Recognizer(getModel(), SAMPLE_RATE)) {
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            // Buffer maior (~2 segundos) evita perder pedaços de áudio se o sistema
            // atrasar um instante para ler o microfone (ex: com a interface ou outros
            // programas consumindo CPU), o que ajuda a reconhecer melhor.
            int bufferSize = format.getFrameSize() * SAMPLE_RATE * 2;
            microphone.open(format, bufferSize);
            microphone.start();

            System.out.println("[Ouvindo... fale à vontade, o Jarvis para sozinho quando você terminar]");
            byte[] chunk = new byte[4096];

            long startTime = System.currentTimeMillis();
            long hardLimit = startTime + (maxSeconds * 1000L);
            long lastSoundTime = startTime;
            boolean speechDetected = false;

            int calibrationCount = 0;
            double calibrationSum = 0;
            double silenceThreshold = MIN_SILENCE_THRESHOLD;

            while (System.currentTimeMillis() < hardLimit) {
                int bytesRead = microphone.read(chunk, 0, chunk.length);
                if (bytesRead <= 0) continue;

                recognizer.acceptWaveForm(chunk, bytesRead);

                long now = System.currentTimeMillis();
                double rms = computeRms(chunk, bytesRead);

                if (calibrationCount < CALIBRATION_CHUNKS) {
                    // Primeiros instantes: só mede o ruído de fundo, não conta como fala nem silêncio.
                    calibrationSum += rms;
                    calibrationCount++;
                    lastSoundTime = now;
                    if (calibrationCount == CALIBRATION_CHUNKS) {
                        double noiseFloor = calibrationSum / CALIBRATION_CHUNKS;
                        silenceThreshold = Math.max(noiseFloor * SILENCE_MULTIPLIER, MIN_SILENCE_THRESHOLD);
                    }
                    continue;
                }

                if (rms > silenceThreshold) {
                    lastSoundTime = now;
                    speechDetected = true;
                } else if (speechDetected
                        && (now - lastSoundTime) > SILENCE_TIMEOUT_MS
                        && (now - startTime) > MIN_SPEECH_MS) {
                    // A pessoa já falou algo e está em silêncio há tempo suficiente: encerra.
                    break;
                }
            }

            JsonNode result = MAPPER.readTree(recognizer.getFinalResult());
            String heard = result.path("text").asText("");
            return VoiceCorrections.correct(heard);
        } catch (Exception e) {
            return "Erro ao ouvir o microfone: " + e.getMessage();
        } finally {
            if (microphone != null) {
                microphone.stop();
                microphone.close();
            }
        }
    }

    /** Calcula a intensidade (RMS) de um bloco de áudio PCM 16-bit para detectar silêncio. */
    private static double computeRms(byte[] buffer, int length) {
        int sampleCount = length / 2;
        if (sampleCount == 0) return 0;
        long sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            int low = buffer[2 * i] & 0xFF;
            int high = buffer[2 * i + 1];
            short sample = (short) ((high << 8) | low);
            sumSquares += (long) sample * sample;
        }
        return Math.sqrt((double) sumSquares / sampleCount);
    }

    /**
     * Fala um texto em voz alta usando o Piper TTS (voz Razo, pt-BR).
     * 1) Chama piper.exe, mandando o texto pela entrada padrão, pedindo
     *    para gravar o áudio gerado num arquivo .wav temporário.
     * 2) Toca esse .wav com o player de áudio nativo do Java.
     * Silencioso em caso de erro (só avisa no console), para não travar o
     * loop principal do Jarvis.
     */
    public static void speak(String text) {
        if (text == null || text.isBlank()) return;
        try {
            if (!java.nio.file.Files.exists(PIPER_EXE)) {
                System.err.println("[Aviso: piper.exe não encontrado em " + PIPER_EXE.toAbsolutePath() + "]");
                return;
            }
            if (!java.nio.file.Files.exists(PIPER_VOICE)) {
                System.err.println("[Aviso: modelo de voz não encontrado em " + PIPER_VOICE.toAbsolutePath() + "]");
                return;
            }

            String cleanText = cleanForSpeech(text);
            if (cleanText.isBlank()) return;

            ProcessBuilder pb = new ProcessBuilder(
                    PIPER_EXE.toAbsolutePath().toString(),
                    "--model", PIPER_VOICE.toAbsolutePath().toString(),
                    "--output_file", TEMP_WAV.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Manda o texto pela entrada padrão do piper.exe (é assim que ele espera receber)
            process.getOutputStream().write(cleanText.getBytes("UTF-8"));
            process.getOutputStream().close();
            process.waitFor();

            playWav(TEMP_WAV.toFile());
        } catch (Exception e) {
            System.err.println("[Aviso: não foi possível falar em voz alta - " + e.getMessage() + "]");
        }
    }

    /**
     * Remove emojis e símbolos de formatação markdown (que a IA às vezes usa
     * na resposta) antes de mandar o texto pro Piper, para ele não tentar
     * "pronunciar" esses símbolos literalmente (ex: falar "asterisco",
     * "sustenido", ou soletrar o emoji).
     */
    private static String cleanForSpeech(String text) {
        String result = text;

        // Remove emojis (faixas Unicode onde eles vivem)
        result = result.replaceAll(
                "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}\\x{FE0F}]", "");

        // Remove marcações de markdown comuns: **negrito**, *itálico*, `código`,
        // # títulos, > citações, e marcadores de lista no início de linha
        result = result.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        result = result.replaceAll("\\*(.*?)\\*", "$1");
        result = result.replaceAll("`(.*?)`", "$1");
        result = result.replaceAll("(?m)^#{1,6}\\s*", "");
        result = result.replaceAll("(?m)^>\\s*", "");
        result = result.replaceAll("(?m)^[-*•]\\s+", "");

        // Remove links markdown [texto](url), mantendo só o texto
        result = result.replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1");

        // Normaliza espaços/linhas em excesso
        result = result.replaceAll("[ \\t]+", " ");
        result = result.replaceAll("\\n{2,}", ". ");
        result = result.replaceAll("\\n", " ");

        return result.trim();
    }

    // Guarda o áudio tocando no momento, para poder cortar na hora se uma
    // nova pergunta/fala chegar antes dessa terminar (evita duas respostas
    // "faladas por cima" uma da outra).
    private static volatile Clip currentClip;

    /** Interrompe imediatamente qualquer fala do Jarvis que esteja tocando agora. */
    public static void stopSpeaking() {
        Clip clip = currentClip;
        if (clip != null) {
            try {
                if (clip.isRunning()) {
                    clip.stop();
                }
                clip.close();
            } catch (Exception ignored) {
                // Não tem problema se já tiver fechado/parado sozinho.
            }
        }
        currentClip = null;
    }

    /** Toca um arquivo .wav do início ao fim, esperando terminar antes de retornar (ou ser interrompido). */
    private static void playWav(File wavFile) {
        try {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(wavFile);
            Clip clip = AudioSystem.getClip();
            clip.open(audioStream);
            currentClip = clip;

            Object lock = new Object();
            clip.addLineListener(event -> {
                if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            });

            // Espera o tempo real do áudio (+ folga), em vez de um limite fixo curto
            // que cortaria respostas mais longas no meio da fala. Se stopSpeaking()
            // for chamado por fora, o clip.stop() dispara o LineEvent STOP acima e
            // acorda esse wait() na hora, então a interrupção é imediata.
            long audioDurationMs = clip.getMicrosecondLength() / 1000;
            long waitMs = Math.min(audioDurationMs + 3000, 5 * 60 * 1000L); // até 5 minutos, no limite

            synchronized (lock) {
                clip.start();
                lock.wait(waitMs);
            }
            clip.close();
            audioStream.close();
            currentClip = null;
        } catch (Exception e) {
            System.err.println("[Aviso: não foi possível tocar o áudio - " + e.getMessage() + "]");
        }
    }
}
