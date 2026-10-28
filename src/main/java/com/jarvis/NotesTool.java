package com.jarvis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Guarda lembretes/anotações em um arquivo de texto simples (notas.txt) na
 * raiz do projeto, para que sobrevivam entre execuções do Jarvis (diferente
 * do histórico de conversa, que se perde quando o programa fecha).
 */
public class NotesTool {

    private static final Path NOTES_FILE = Paths.get("notas.txt");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Adiciona uma nova nota/lembrete com data e hora, uma por linha. */
    public static String addNote(String text) {
        if (text == null || text.isBlank()) {
            return "Erro: o texto da nota não pode estar vazio.";
        }
        String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + text.trim();
        try {
            Files.writeString(
                    NOTES_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return "Nota salva: " + line;
        } catch (IOException e) {
            return "Erro ao salvar nota: " + e.getMessage();
        }
    }

    /** Lista todas as notas salvas, mais recentes por último (ordem em que foram criadas). */
    public static String listNotes() {
        if (!Files.exists(NOTES_FILE)) {
            return "Nenhuma nota salva ainda.";
        }
        try {
            List<String> lines = Files.readAllLines(NOTES_FILE);
            if (lines.isEmpty()) {
                return "Nenhuma nota salva ainda.";
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "Erro ao ler notas: " + e.getMessage();
        }
    }
}
