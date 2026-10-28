package com.jarvis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Gerencia a pasta "skills/": arquivos .md com instruções passo a passo para
 * tarefas que se repetem. A IA primeiro LISTA as skills disponíveis (só os
 * títulos, pra não gastar contexto à toa) e depois LÊ o conteúdo completo
 * apenas da que for relevante para o pedido do usuário.
 */
public class SkillManager {

    private static final Path SKILLS_DIR = Paths.get("skills");

    /** Retorna um texto com o nome de cada skill disponível (sem o conteúdo). */
    public static String listSkills() {
        if (!Files.isDirectory(SKILLS_DIR)) {
            return "Nenhuma skill disponível (a pasta 'skills' não existe).";
        }
        try (Stream<Path> files = Files.list(SKILLS_DIR)) {
            List<String> names = files
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString().replace(".md", ""))
                    .collect(Collectors.toList());

            if (names.isEmpty()) {
                return "Nenhuma skill encontrada na pasta 'skills'.";
            }
            return "Skills disponíveis: " + String.join(", ", names);
        } catch (IOException e) {
            return "Erro ao listar skills: " + e.getMessage();
        }
    }

    /** Lê o conteúdo completo de uma skill pelo nome (sem a extensão .md). */
    public static String readSkill(String name) {
        Path file = SKILLS_DIR.resolve(name + ".md");
        if (!Files.exists(file)) {
            return "Erro: skill '" + name + "' não encontrada. Use listar_skills para ver as disponíveis.";
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "Erro ao ler skill '" + name + "': " + e.getMessage();
        }
    }
}