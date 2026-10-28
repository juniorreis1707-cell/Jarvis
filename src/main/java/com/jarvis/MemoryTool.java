package com.jarvis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Memória PERMANENTE do Jarvis sobre o usuário: apelidos, preferências,
 * informações pessoais que ele deve lembrar em toda conversa futura —
 * diferente de SkillManager (passo a passo de tarefas) e de NotesTool
 * (lembretes avulsos que a IA só consulta se for perguntada).
 *
 * O conteúdo deste arquivo é injetado automaticamente no system prompt em
 * TODA conversa (veja JarvisAgent.callClaude), então o Jarvis já "sabe"
 * essas informações desde a primeira mensagem, sem precisar que ninguém
 * pergunte ou que ele chame uma ferramenta para consultar.
 */
public class MemoryTool {

    private static final Path MEMORY_FILE = Paths.get("memoria.txt");

    /** Adiciona uma informação permanente sobre o usuário (uma por linha). */
    public static String rememberFact(String fact) {
        if (fact == null || fact.isBlank()) {
            return "Erro: a informação a lembrar não pode estar vazia.";
        }
        String line = "- " + fact.trim();
        try {
            Files.writeString(
                    MEMORY_FILE,
                    line + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return "Vou lembrar disso a partir de agora: " + fact.trim();
        } catch (IOException e) {
            return "Erro ao salvar na memória: " + e.getMessage();
        }
    }

    /**
     * Carrega tudo que já foi salvo, para injetar no system prompt.
     * Retorna string vazia se ainda não há nada salvo (o chamador decide
     * se inclui ou não o bloco no prompt).
     */
    public static String loadAll() {
        if (!Files.exists(MEMORY_FILE)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(MEMORY_FILE);
            return String.join("\n", lines);
        } catch (IOException e) {
            return "";
        }
    }
}
