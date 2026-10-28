package com.jarvis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Correção leve do texto reconhecido pelo Vosk.
 *
 * O modelo de voz usado (vosk-model-small-pt-0.3, 31MB) é pequeno e treinado
 * majoritariamente com português comum — ele erra bastante em nomes
 * estrangeiros/técnicos (jogos, apps, siglas) porque nunca "ouviu" essas
 * palavras sendo ditas durante o treinamento, e substitui pelo que soa mais
 * parecido em português.
 *
 * Esta classe tenta corrigir isso comparando o que foi ouvido com uma lista
 * de termos importantes conhecidos (por aproximação de texto — distância de
 * edição), e substituindo quando a semelhança é alta o suficiente. Também
 * guarda correções específicas já observadas na prática (ex: o Jarvis ouviu
 * "jeito assis" quando na verdade era "GTA 6").
 *
 * Para melhorar mais: sempre que o Jarvis entender errado algo importante,
 * pode pedir para adicionar aqui — a lista cresce com o uso.
 */
public class VoiceCorrections {

    // Chave: forma normalizada (sem acento, minúscula) do termo ou de uma
    // transcrição errada já observada. Valor: o texto correto a usar.
    private static final Map<String, String> TERM_MAP = new LinkedHashMap<>();
    static {
        // Jogos
        put("gta 6", "GTA 6");
        put("jeito assis", "GTA 6");   // já observado: o Vosk entendeu isso no lugar de "GTA 6"
        put("jeito a seis", "GTA 6");
        put("gta 5", "GTA 5");
        put("gta v", "GTA 5");
        put("call of duty", "Call of Duty");
        put("valorant", "Valorant");
        put("league of legends", "League of Legends");
        put("free fire", "Free Fire");
        put("minecraft", "Minecraft");
        put("fortnite", "Fortnite");

        // Apps e programas
        put("spotify", "Spotify");
        put("steam", "Steam");
        put("intellij idea", "IntelliJ IDEA");
        put("intellij", "IntelliJ");
        put("visual studio code", "Visual Studio Code");
        put("vs code", "VS Code");
        put("microsoft edge", "Microsoft Edge");
        put("youtube", "YouTube");
        put("claude", "Claude");
        put("jarvis", "Jarvis");
        put("whatsapp", "WhatsApp");
        put("instagram", "Instagram");
        put("tiktok", "TikTok");
        put("netflix", "Netflix");
        put("discord", "Discord");
        put("playstation", "PlayStation");
        put("xbox", "Xbox");
    }

    private static void put(String key, String value) {
        TERM_MAP.put(normalize(key), value);
    }

    /** Aplica as correções conhecidas sobre o texto reconhecido, se houver alguma semelhança forte. */
    public static String correct(String heard) {
        if (heard == null || heard.isBlank()) return heard;

        String[] words = heard.trim().split("\\s+");
        List<String> keys = new ArrayList<>(TERM_MAP.keySet());
        // Tenta primeiro casar frases inteiras (mais palavras) antes de termos soltos,
        // para não quebrar "vs code" em pedaços, por exemplo.
        keys.sort((a, b) -> Integer.compare(wordCount(b), wordCount(a)));

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String bestKey = null;
            double bestScore = 0;
            int bestLen = 0;

            for (String key : keys) {
                int len = wordCount(key);
                if (i + len > words.length) continue;
                String window = String.join(" ", Arrays.copyOfRange(words, i, i + len));
                String windowNorm = normalize(window);

                boolean shortKey = key.length() <= 4;
                double score = similarity(windowNorm, key);
                boolean matches = shortKey ? levenshtein(windowNorm, key) <= 1 : score >= 0.62;

                if (matches && score > bestScore) {
                    bestScore = score;
                    bestKey = key;
                    bestLen = len;
                }
            }

            if (bestKey != null) {
                if (result.length() > 0) result.append(' ');
                result.append(TERM_MAP.get(bestKey));
                i += bestLen;
            } else {
                if (result.length() > 0) result.append(' ');
                result.append(words[i]);
                i++;
            }
        }

        return result.toString();
    }

    private static int wordCount(String s) {
        return s.trim().split("\\s+").length;
    }

    private static String normalize(String s) {
        String noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase().trim();
    }

    private static double similarity(String a, String b) {
        int dist = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) dist / maxLen);
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
