package com.jarvis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Busca músicas no Spotify (Web API) e manda TOCAR de verdade no app do
 * Spotify instalado no computador — usa o protocolo "spotify:track:<id>",
 * que o Windows abre com o app, e o próprio app já começa a tocar sozinho.
 *
 * Precisa de credenciais gratuitas de um app no Spotify for Developers:
 * SPOTIFY_CLIENT_ID e SPOTIFY_CLIENT_SECRET como variáveis de ambiente
 * (veja o README/instruções de configuração).
 */
public class SpotifyTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String cachedToken;
    private static long tokenExpiresAtMillis = 0;

    /** Busca a música pelo termo informado e manda o app do Spotify tocar a primeira encontrada. */
    public static String playTrack(String query) {
        String clientId = System.getenv("SPOTIFY_CLIENT_ID");
        String clientSecret = System.getenv("SPOTIFY_CLIENT_SECRET");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return "Erro: SPOTIFY_CLIENT_ID e/ou SPOTIFY_CLIENT_SECRET não configurados como "
                    + "variáveis de ambiente. Crie um app gratuito em developer.spotify.com/dashboard.";
        }

        try {
            String token = getAccessToken(clientId, clientSecret);
            if (token == null) {
                return "Erro: não foi possível autenticar com o Spotify. Confira o Client ID/Secret.";
            }

            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest searchRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/search?q=" + encoded + "&type=track&limit=1"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> searchResponse = HTTP.send(searchRequest, HttpResponse.BodyHandlers.ofString());
            if (searchResponse.statusCode() != 200) {
                return "Erro ao buscar no Spotify (" + searchResponse.statusCode() + "): " + searchResponse.body();
            }

            JsonNode root = MAPPER.readTree(searchResponse.body());
            JsonNode tracks = root.path("tracks").path("items");
            if (!tracks.isArray() || tracks.isEmpty()) {
                return "Não encontrei nenhuma música para \"" + query + "\" no Spotify.";
            }

            JsonNode track = tracks.get(0);
            String trackId = track.path("id").asText();
            String trackName = track.path("name").asText();
            JsonNode artists = track.path("artists");
            String artistName = (artists.isArray() && !artists.isEmpty())
                    ? artists.get(0).path("name").asText()
                    : "";

            new ProcessBuilder("cmd", "/c", "start", "\"\"", "spotify:track:" + trackId).start();

            return "Tocando \"" + trackName + "\""
                    + (artistName.isBlank() ? "" : " de " + artistName) + " no Spotify.";
        } catch (Exception e) {
            return "Erro ao tocar música no Spotify: " + e.getMessage();
        }
    }

    /** Pega (ou reaproveita, se ainda válido) um token de acesso via Client Credentials flow. */
    private static String getAccessToken(String clientId, String clientSecret)
            throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAtMillis) {
            return cachedToken;
        }

        String credentials = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }

        JsonNode root = MAPPER.readTree(response.body());
        String token = root.path("access_token").asText();
        int expiresIn = root.path("expires_in").asInt(3600);

        cachedToken = token;
        tokenExpiresAtMillis = now + (long) (expiresIn - 60) * 1000L; // 1 min de margem de segurança

        return token;
    }
}
