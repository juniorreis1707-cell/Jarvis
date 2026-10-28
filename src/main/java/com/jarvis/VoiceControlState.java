package com.jarvis;

/**
 * Guarda se a "escuta automática" (o Jarvis ficar sempre ouvindo o microfone
 * sozinho, sem precisar clicar em FALAR) está ligada ou desligada.
 *
 * É uma classe simples e compartilhada porque quem PEDE a mudança é a IA
 * (via ferramenta, ao ouvir "desative o reconhecimento de voz"), mas quem
 * PRECISA saber o estado é o loop de escuta automática, que roda na
 * interface visual (JarvisFxApp) — duas partes diferentes do programa que
 * não se conhecem diretamente.
 *
 * Importante: isso só afeta a escuta AUTOMÁTICA. O botão FALAR continua
 * funcionando normalmente mesmo com a escuta automática desativada.
 */
public class VoiceControlState {

    private static volatile boolean autoListenEnabled = true;

    public static boolean isAutoListenEnabled() {
        return autoListenEnabled;
    }

    public static void setAutoListenEnabled(boolean enabled) {
        autoListenEnabled = enabled;
    }
}
