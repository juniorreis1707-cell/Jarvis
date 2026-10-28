package com.jarvis;

import java.util.concurrent.TimeUnit;

/**
 * Controla o volume do Windows simulando as teclas de mídia do teclado
 * (cada toque sobe/desce 2%), via PowerShell — sem instalar nada extra.
 */
public class VolumeTool {

    private static final int KEY_MUTE = 173;
    private static final int KEY_DOWN = 174;
    private static final int KEY_UP = 175;

    /**
     * @param acao       "aumentar", "diminuir", "mutar" (liga/desliga o mudo) ou "definir"
     * @param quantidade porcentagem a subir/descer (padrão 10), ou o nível final (0-100) em "definir"
     */
    public static String adjust(String acao, int quantidade) {
        if (acao == null) acao = "";
        String a = acao.trim().toLowerCase();
        try {
            switch (a) {
                case "aumentar": {
                    int presses = pressesFor(quantidade <= 0 ? 10 : quantidade);
                    press(KEY_UP, presses);
                    return "Volume aumentado em cerca de " + presses * 2 + "%.";
                }
                case "diminuir": {
                    int presses = pressesFor(quantidade <= 0 ? 10 : quantidade);
                    press(KEY_DOWN, presses);
                    return "Volume diminuído em cerca de " + presses * 2 + "%.";
                }
                case "mutar":
                    press(KEY_MUTE, 1);
                    return "Mudo alternado (se estava com som, agora está mudo, e vice-versa).";
                case "definir": {
                    int level = Math.max(0, Math.min(100, quantidade));
                    press(KEY_DOWN, 50);              // zera primeiro
                    if (level > 0) press(KEY_UP, level / 2);
                    return "Volume ajustado para cerca de " + (level / 2) * 2 + "%.";
                }
                default:
                    return "Erro: ação de volume desconhecida '" + acao
                            + "'. Use aumentar, diminuir, mutar ou definir.";
            }
        } catch (Exception e) {
            return "Erro ao ajustar o volume: " + e.getMessage();
        }
    }

    private static int pressesFor(int percent) {
        return Math.max(1, Math.min(50, Math.round(percent / 2f)));
    }

    private static void press(int keyCode, int times) throws Exception {
        String script = "$w = New-Object -ComObject WScript.Shell; "
                + "1.." + times + " | ForEach-Object { $w.SendKeys([char]" + keyCode + ") }";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("PowerShell demorou demais para responder.");
        }
    }
}
