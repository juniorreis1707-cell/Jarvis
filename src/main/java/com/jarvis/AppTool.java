package com.jarvis;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Abre aplicativos instalados no Windows diretamente (não no navegador).
 *
 * Usa o comando "start" do Windows, que sabe abrir tanto programas quanto
 * protocolos especiais registrados por apps (ex: "spotify:" abre o app do
 * Spotify de verdade, se ele estiver instalado, em vez do site).
 *
 * Para adicionar um novo app: só incluir uma entrada no mapa APPS abaixo,
 * com o nome que o usuário vai falar e o comando/protocolo do Windows.
 */
public class AppTool {

    private static final Map<String, String> APPS = Map.ofEntries(
            Map.entry("spotify", "spotify:"),
            Map.entry("WhatsApp", "WhatsApp"),
            Map.entry("calculadora", "calc"),
            Map.entry("bloco de notas", "notepad"),
            Map.entry("explorador de arquivos", "explorer"),
            Map.entry("steam", "steam:"),
            Map.entry("vscode", "code"),
            Map.entry("vs code", "code"),
            Map.entry("visual studio code", "code"),
            Map.entry("edge", "msedge"),
            Map.entry("microsoft edge", "msedge"),
            Map.entry("intellij", "idea"),
            Map.entry("intellij idea", "idea"),
            Map.entry("claude", "https://claude.ai"),
            Map.entry("discord", "discord:")
    );

    /** Abre um aplicativo pelo nome (ver chaves de APPS). */
    public static String openApp(String name) {
        if (name == null || name.isBlank()) {
            return "Erro: informe o nome do aplicativo a abrir.";
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        String command = APPS.get(key);

        if (command == null) {
            return "Erro: não conheço o aplicativo '" + name + "'. Aplicativos disponíveis: "
                    + String.join(", ", APPS.keySet());
        }

        try {
            // "start" com string vazia de título evita problema quando o comando tem espaços/aspas
            new ProcessBuilder("cmd", "/c", "start", "\"\"", command).start();
            return "Abrindo " + name + "...";
        } catch (IOException e) {
            return "Erro ao abrir " + name + ": " + e.getMessage();
        }
    }
}
