package com.jarvis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Núcleo (harness) do Jarvis: fala com a API da Anthropic e executa
 * ferramentas Java reais quando a IA pede (tool use).
 *
 * Esta classe é a "casca" independente de interface — tanto o modo texto
 * (Main.java) quanto a interface visual (JarvisFxApp.java) chamam os mesmos
 * métodos aqui, cada um decidindo como mostrar o resultado (console ou
 * janela).
 */
public class JarvisAgent {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-5";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Callback para quem está usando o agente (console ou UI) reagir aos eventos de um turno. */
    public interface AgentListener {
        /** Chamado sempre que a IA gera um trecho de texto (pode acontecer mais de uma vez por turno). */
        void onAssistantText(String text);

        /** Chamado sempre que a IA decide usar uma ferramenta, com o nome dela. */
        void onToolUse(String toolName);
    }

    /** Cria um histórico de conversa vazio. */
    public static ArrayNode newConversation() {
        return MAPPER.createArrayNode();
    }

    /** Adiciona uma mensagem do usuário ao histórico. */
    public static void addUserMessage(ArrayNode messages, String text) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.put("content", text);
        messages.add(message);
    }

    /**
     * Executa um "turno" completo: manda a conversa pra IA, e se ela pedir
     * para usar uma ferramenta, executa a ferramenta e manda o resultado de
     * volta, repetindo até a IA responder com texto final (sem mais tool_use).
     */
    public static void processTurn(String apiKey, ArrayNode messages, AgentListener listener)
            throws IOException, InterruptedException {
        while (true) {
            JsonNode response = callClaude(apiKey, messages);

            String stopReason = response.path("stop_reason").asText();
            JsonNode content = response.path("content");

            // Guarda a resposta da IA (texto + tool_use) no histórico
            ObjectNode assistantMessage = MAPPER.createObjectNode();
            assistantMessage.put("role", "assistant");
            assistantMessage.set("content", content);
            messages.add(assistantMessage);

            // Notifica qualquer texto que a IA tenha dito nesse passo
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    listener.onAssistantText(block.path("text").asText());
                }
            }

            if (!"tool_use".equals(stopReason)) {
                // A IA terminou o raciocínio e não precisa de mais ferramentas
                return;
            }

            // Executa cada chamada de ferramenta pedida pela IA e devolve o resultado
            ArrayNode toolResults = MAPPER.createArrayNode();
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    String toolName = block.path("name").asText();
                    String toolUseId = block.path("id").asText();
                    JsonNode input = block.path("input");

                    // IMPORTANTE: nunca deixar essa chamada "vazar" uma exceção pra fora.
                    // Se uma ferramenta falhar de qualquer jeito (Exception OU Error, ex:
                    // driver de navegador que não inicializa), a API exige um tool_result
                    // pra CADA tool_use — se um ficar faltando, a conversa inteira fica
                    // corrompida (todo turno seguinte passa a dar erro 400). Por isso
                    // capturamos Throwable aqui e sempre produzimos algum resultado.
                    String resultText;
                    try {
                        listener.onToolUse(toolName);
                        resultText = executeTool(toolName, input);
                    } catch (Throwable t) {
                        resultText = "Erro inesperado ao executar a ferramenta '" + toolName + "': "
                                + t.getClass().getSimpleName() + " - " + t.getMessage();
                    }

                    ObjectNode toolResult = MAPPER.createObjectNode();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", toolUseId);
                    toolResult.put("content", resultText);
                    toolResults.add(toolResult);
                }
            }

            ObjectNode userMessage = MAPPER.createObjectNode();
            userMessage.put("role", "user");
            userMessage.set("content", toolResults);
            messages.add(userMessage);

            // Volta pro topo do loop: a IA agora vê o resultado da ferramenta
            // e decide se responde ou chama outra ferramenta.
        }
    }

    private static JsonNode callClaude(String apiKey, ArrayNode messages) throws IOException, InterruptedException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", 4096);
        body.put("system", buildSystemPrompt());
        body.set("messages", messages);
        body.set("tools", buildToolDefinitions());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Erro da API (" + response.statusCode() + "): " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    /**
     * Monta o prompt de sistema, incluindo (se existir) a memória permanente
     * sobre o usuário — assim o Jarvis já "sabe" apelidos, preferências etc.
     * desde a primeira mensagem de QUALQUER conversa, sem precisar perguntar.
     */
    private static String buildSystemPrompt() {
        String base = "Você é o Jarvis, um assistente de IA direto e útil. "
                + "Use ferramentas quando fizerem sentido para responder com precisão. "
                + "Antes de tarefas que pareçam recorrentes ou específicas (ex: resumir uma página, "
                + "pesquisar preços), use listar_skills para ver se existe um passo a passo salvo, "
                + "e ler_skill para carregar as instruções antes de agir. "
                + "Quando o usuário pedir para você lembrar de algo permanente sobre ele (como um "
                + "apelido para te chamarem, preferências, ou informações pessoais relevantes para "
                + "toda conversa futura), use a ferramenta lembrar_sobre_mim para salvar. "
                + "Você entende bem gírias, expressões informais e regionalismos do português do "
                + "Brasil (ex: 'mano', 'sinistro', 'trampo', 'bagulho', 'de boa', 'tá ligado', 'rolê', "
                + "'cringe', 'suave') — nunca trate uma gíria como se não entendesse, interprete pelo "
                + "contexto normalmente. Da mesma forma, você também entende e sabe usar palavras mais "
                + "difíceis, técnicas ou formais quando o assunto pedir. Adapte seu próprio jeito de "
                + "falar ao tom do usuário: se ele for mais informal e usar gírias, pode responder de "
                + "forma mais descontraída também, sem forçar demais; se ele for mais formal, vá no "
                + "mesmo tom. Se o usuário perguntar o que significa uma palavra ou gíria, explique de "
                + "forma clara e direta.";

        String memory = MemoryTool.loadAll();
        if (!memory.isBlank()) {
            base += "\n\nInformações permanentes que você já sabe sobre o usuário:\n" + memory;
        }
        return base;
    }

    /**
     * Define as ferramentas que a IA pode chamar. Cada uma precisa de um
     * nome, uma descrição clara (a IA decide QUANDO usar com base nela) e
     * um schema JSON dos parâmetros de entrada.
     */
    private static ArrayNode buildToolDefinitions() {
        ArrayNode tools = MAPPER.createArrayNode();

        ObjectNode getTime = MAPPER.createObjectNode();
        getTime.put("name", "obter_hora_atual");
        getTime.put("description", "Retorna a data e hora atuais do sistema. Use quando o usuário "
                + "perguntar as horas, a data de hoje, ou precisar de um timestamp.");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        getTime.set("input_schema", schema);
        tools.add(getTime);

        ObjectNode abrirPagina = MAPPER.createObjectNode();
        abrirPagina.put("name", "abrir_pagina");
        abrirPagina.put("description", "Abre uma URL no navegador Microsoft Edge de verdade e retorna o "
                + "título da página carregada. Use sempre que precisar visitar um site.");
        ObjectNode abrirPaginaSchema = MAPPER.createObjectNode();
        abrirPaginaSchema.put("type", "object");
        ObjectNode abrirPaginaProps = MAPPER.createObjectNode();
        ObjectNode urlProp = MAPPER.createObjectNode();
        urlProp.put("type", "string");
        urlProp.put("description", "A URL completa a abrir, incluindo https://");
        abrirPaginaProps.set("url", urlProp);
        abrirPaginaSchema.set("properties", abrirPaginaProps);
        ArrayNode abrirPaginaRequired = MAPPER.createArrayNode();
        abrirPaginaRequired.add("url");
        abrirPaginaSchema.set("required", abrirPaginaRequired);
        abrirPagina.set("input_schema", abrirPaginaSchema);
        tools.add(abrirPagina);

        ObjectNode extrairTexto = MAPPER.createObjectNode();
        extrairTexto.put("name", "extrair_texto_pagina");
        extrairTexto.put("description", "Lê e retorna todo o texto visível da página atualmente "
                + "aberta no navegador. Use logo após abrir uma página para saber o que ela contém.");
        ObjectNode extrairTextoSchema = MAPPER.createObjectNode();
        extrairTextoSchema.put("type", "object");
        extrairTextoSchema.set("properties", MAPPER.createObjectNode());
        extrairTexto.set("input_schema", extrairTextoSchema);
        tools.add(extrairTexto);

        ObjectNode clicarLink = MAPPER.createObjectNode();
        clicarLink.put("name", "clicar_link");
        clicarLink.put("description", "Clica no primeiro link da página atual cujo texto contenha o "
                + "trecho informado. Use para navegar dentro de um site (ex: clicar em 'Contato').");
        ObjectNode clicarLinkSchema = MAPPER.createObjectNode();
        clicarLinkSchema.put("type", "object");
        ObjectNode clicarLinkProps = MAPPER.createObjectNode();
        ObjectNode textoProp = MAPPER.createObjectNode();
        textoProp.put("type", "string");
        textoProp.put("description", "Trecho do texto visível do link a clicar");
        clicarLinkProps.set("texto", textoProp);
        clicarLinkSchema.set("properties", clicarLinkProps);
        ArrayNode clicarLinkRequired = MAPPER.createArrayNode();
        clicarLinkRequired.add("texto");
        clicarLinkSchema.set("required", clicarLinkRequired);
        clicarLink.set("input_schema", clicarLinkSchema);
        tools.add(clicarLink);

        ObjectNode listarSkills = MAPPER.createObjectNode();
        listarSkills.put("name", "listar_skills");
        listarSkills.put("description", "Lista os nomes de todas as skills (instruções passo a "
                + "passo salvas) disponíveis. Use no início de tarefas que pareçam recorrentes ou "
                + "específicas, para ver se já existe um procedimento definido.");
        ObjectNode listarSkillsSchema = MAPPER.createObjectNode();
        listarSkillsSchema.put("type", "object");
        listarSkillsSchema.set("properties", MAPPER.createObjectNode());
        listarSkills.set("input_schema", listarSkillsSchema);
        tools.add(listarSkills);

        ObjectNode lerSkill = MAPPER.createObjectNode();
        lerSkill.put("name", "ler_skill");
        lerSkill.put("description", "Lê o conteúdo completo de uma skill específica pelo nome "
                + "(sem a extensão .md), retornando as instruções passo a passo. Use depois de "
                + "listar_skills, quando encontrar uma skill relevante para o pedido do usuário.");
        ObjectNode lerSkillSchema = MAPPER.createObjectNode();
        lerSkillSchema.put("type", "object");
        ObjectNode lerSkillProps = MAPPER.createObjectNode();
        ObjectNode nomeProp = MAPPER.createObjectNode();
        nomeProp.put("type", "string");
        nomeProp.put("description", "Nome exato da skill, sem a extensão .md");
        lerSkillProps.set("nome", nomeProp);
        lerSkillSchema.set("properties", lerSkillProps);
        ArrayNode lerSkillRequired = MAPPER.createArrayNode();
        lerSkillRequired.add("nome");
        lerSkillSchema.set("required", lerSkillRequired);
        lerSkill.set("input_schema", lerSkillSchema);
        tools.add(lerSkill);

        ObjectNode salvarNota = MAPPER.createObjectNode();
        salvarNota.put("name", "salvar_nota");
        salvarNota.put("description", "Salva um lembrete ou anotação, com data e hora, para consulta "
                + "posterior (fica guardado mesmo depois de fechar o Jarvis). Use quando o usuário pedir "
                + "para anotar, lembrar de algo, ou criar um lembrete.");
        ObjectNode salvarNotaSchema = MAPPER.createObjectNode();
        salvarNotaSchema.put("type", "object");
        ObjectNode salvarNotaProps = MAPPER.createObjectNode();
        ObjectNode textoNotaProp = MAPPER.createObjectNode();
        textoNotaProp.put("type", "string");
        textoNotaProp.put("description", "O texto do lembrete/anotação a salvar");
        salvarNotaProps.set("texto", textoNotaProp);
        salvarNotaSchema.set("properties", salvarNotaProps);
        ArrayNode salvarNotaRequired = MAPPER.createArrayNode();
        salvarNotaRequired.add("texto");
        salvarNotaSchema.set("required", salvarNotaRequired);
        salvarNota.set("input_schema", salvarNotaSchema);
        tools.add(salvarNota);

        ObjectNode listarNotas = MAPPER.createObjectNode();
        listarNotas.put("name", "listar_notas");
        listarNotas.put("description", "Lista todos os lembretes/anotações salvos anteriormente, com "
                + "data e hora de cada um. Use quando o usuário pedir para ver, lembrar ou consultar "
                + "suas anotações.");
        ObjectNode listarNotasSchema = MAPPER.createObjectNode();
        listarNotasSchema.put("type", "object");
        listarNotasSchema.set("properties", MAPPER.createObjectNode());
        listarNotas.set("input_schema", listarNotasSchema);
        tools.add(listarNotas);

        ObjectNode lembrarSobreMim = MAPPER.createObjectNode();
        lembrarSobreMim.put("name", "lembrar_sobre_mim");
        lembrarSobreMim.put("description", "Salva PERMANENTEMENTE uma informação sobre o usuário que "
                + "deve valer para todas as conversas futuras, não só a atual — por exemplo, um apelido "
                + "pelo qual ele quer ser chamado, uma preferência, ou um dado pessoal relevante. Use "
                + "sempre que o usuário disser algo como 'me chame de X', 'lembre que eu...', ou pedir "
                + "explicitamente para você lembrar algo sobre ele de forma permanente. Não use isso "
                + "para lembretes/tarefas avulsas (para isso existe salvar_nota).");
        ObjectNode lembrarSobreMimSchema = MAPPER.createObjectNode();
        lembrarSobreMimSchema.put("type", "object");
        ObjectNode lembrarSobreMimProps = MAPPER.createObjectNode();
        ObjectNode fatoProp = MAPPER.createObjectNode();
        fatoProp.put("type", "string");
        fatoProp.put("description", "A informação a lembrar permanentemente, escrita de forma clara "
                + "e objetiva (ex: 'O usuário deve ser chamado de chefe')");
        lembrarSobreMimProps.set("informacao", fatoProp);
        lembrarSobreMimSchema.set("properties", lembrarSobreMimProps);
        ArrayNode lembrarSobreMimRequired = MAPPER.createArrayNode();
        lembrarSobreMimRequired.add("informacao");
        lembrarSobreMimSchema.set("required", lembrarSobreMimRequired);
        lembrarSobreMim.set("input_schema", lembrarSobreMimSchema);
        tools.add(lembrarSobreMim);

        ObjectNode abrirApp = MAPPER.createObjectNode();
        abrirApp.put("name", "abrir_aplicativo");
        abrirApp.put("description", "Abre um aplicativo instalado no computador do usuário DIRETAMENTE "
                + "(não no navegador) — por exemplo o app do Spotify. Use quando o usuário pedir para "
                + "abrir um programa/app específico do computador em vez de um site.");
        ObjectNode abrirAppSchema = MAPPER.createObjectNode();
        abrirAppSchema.put("type", "object");
        ObjectNode abrirAppProps = MAPPER.createObjectNode();
        ObjectNode nomeAppProp = MAPPER.createObjectNode();
        nomeAppProp.put("type", "string");
        nomeAppProp.put("description", "Nome do aplicativo a abrir (ex: 'spotify', 'calculadora', "
                + "'bloco de notas', 'explorador de arquivos', 'steam', 'vscode', 'edge', 'intellij', 'discord')");
        abrirAppProps.set("nome", nomeAppProp);
        abrirAppSchema.set("properties", abrirAppProps);
        ArrayNode abrirAppRequired = MAPPER.createArrayNode();
        abrirAppRequired.add("nome");
        abrirAppSchema.set("required", abrirAppRequired);
        abrirApp.set("input_schema", abrirAppSchema);
        tools.add(abrirApp);

        ObjectNode tocarMusica = MAPPER.createObjectNode();
        tocarMusica.put("name", "tocar_musica_spotify");
        tocarMusica.put("description", "Busca uma música no Spotify pelo nome/artista informado e manda "
                + "o app do Spotify (instalado no computador) tocar ela de verdade. Use quando o usuário "
                + "pedir para tocar, colocar ou dar play em alguma música ou artista.");
        ObjectNode tocarMusicaSchema = MAPPER.createObjectNode();
        tocarMusicaSchema.put("type", "object");
        ObjectNode tocarMusicaProps = MAPPER.createObjectNode();
        ObjectNode buscaProp = MAPPER.createObjectNode();
        buscaProp.put("type", "string");
        buscaProp.put("description", "Nome da música e/ou artista a buscar e tocar (ex: 'Bohemian "
                + "Rhapsody Queen', ou só o nome da música/artista se o usuário não especificar os dois)");
        tocarMusicaProps.set("busca", buscaProp);
        tocarMusicaSchema.set("properties", tocarMusicaProps);
        ArrayNode tocarMusicaRequired = MAPPER.createArrayNode();
        tocarMusicaRequired.add("busca");
        tocarMusicaSchema.set("required", tocarMusicaRequired);
        tocarMusica.set("input_schema", tocarMusicaSchema);
        tools.add(tocarMusica);

        ObjectNode controlarEscuta = MAPPER.createObjectNode();
        controlarEscuta.put("name", "controlar_escuta_automatica");
        controlarEscuta.put("description", "Ativa ou desativa a ESCUTA AUTOMÁTICA (o Jarvis ficar sempre "
                + "ouvindo o microfone sozinho, sem precisar clicar em FALAR). Use quando o usuário pedir "
                + "para 'desativar o reconhecimento de voz', 'parar de ouvir sozinho', 'não ficar escutando', "
                + "ou o contrário ('ativar de novo', 'volta a ouvir sozinho'). Isso NÃO desliga o Jarvis nem "
                + "afeta o botão FALAR, que sempre continua funcionando — só pausa/retoma a escuta automática "
                + "em segundo plano.");
        ObjectNode controlarEscutaSchema = MAPPER.createObjectNode();
        controlarEscutaSchema.put("type", "object");
        ObjectNode controlarEscutaProps = MAPPER.createObjectNode();
        ObjectNode ativarProp = MAPPER.createObjectNode();
        ativarProp.put("type", "boolean");
        ativarProp.put("description", "true para ativar a escuta automática, false para desativar");
        controlarEscutaProps.set("ativar", ativarProp);
        controlarEscutaSchema.set("properties", controlarEscutaProps);
        ArrayNode controlarEscutaRequired = MAPPER.createArrayNode();
        controlarEscutaRequired.add("ativar");
        controlarEscutaSchema.set("required", controlarEscutaRequired);
        controlarEscuta.set("input_schema", controlarEscutaSchema);
        tools.add(controlarEscuta);

        ObjectNode controlarVolume = MAPPER.createObjectNode();
        controlarVolume.put("name", "controlar_volume");
        controlarVolume.put("description", "Controla o VOLUME do computador (Windows). Use quando o usuário "
                + "pedir para aumentar, subir, diminuir, abaixar, tirar o som (mudo) ou colocar o volume em "
                + "um nível específico. Se ele não disser quanto, use a quantidade padrão (10).");
        ObjectNode controlarVolumeSchema = MAPPER.createObjectNode();
        controlarVolumeSchema.put("type", "object");
        ObjectNode controlarVolumeProps = MAPPER.createObjectNode();
        ObjectNode acaoVolumeProp = MAPPER.createObjectNode();
        acaoVolumeProp.put("type", "string");
        ArrayNode acaoVolumeEnum = MAPPER.createArrayNode();
        acaoVolumeEnum.add("aumentar");
        acaoVolumeEnum.add("diminuir");
        acaoVolumeEnum.add("mutar");
        acaoVolumeEnum.add("definir");
        acaoVolumeProp.set("enum", acaoVolumeEnum);
        acaoVolumeProp.put("description", "aumentar, diminuir, mutar (liga/desliga o mudo) ou definir (nível exato)");
        ObjectNode qtdVolumeProp = MAPPER.createObjectNode();
        qtdVolumeProp.put("type", "integer");
        qtdVolumeProp.put("description", "Porcentagem a aumentar/diminuir (padrão 10), ou o nível final de 0 a 100 "
                + "quando a ação for 'definir'. Ignorado em 'mutar'.");
        controlarVolumeProps.set("acao", acaoVolumeProp);
        controlarVolumeProps.set("quantidade", qtdVolumeProp);
        controlarVolumeSchema.set("properties", controlarVolumeProps);
        ArrayNode controlarVolumeRequired = MAPPER.createArrayNode();
        controlarVolumeRequired.add("acao");
        controlarVolumeSchema.set("required", controlarVolumeRequired);
        controlarVolume.set("input_schema", controlarVolumeSchema);
        tools.add(controlarVolume);

        ObjectNode controlarWebcam = MAPPER.createObjectNode();
        controlarWebcam.put("name", "controlar_webcam");
        controlarWebcam.put("description", "Liga, desliga ou consulta a WEBCAM do próprio Jarvis. A imagem da "
                + "câmera aparece DENTRO da interface do Jarvis (no portal circular central), NÃO em outro "
                + "programa. Use quando o usuário pedir para abrir/ligar/ativar a webcam ou câmera, ou para "
                + "fechar/desligar. NÃO use abrir_aplicativo para isso. Se falhar, explique o motivo retornado.");
        ObjectNode controlarWebcamSchema = MAPPER.createObjectNode();
        controlarWebcamSchema.put("type", "object");
        ObjectNode controlarWebcamProps = MAPPER.createObjectNode();
        ObjectNode acaoWebcamProp = MAPPER.createObjectNode();
        acaoWebcamProp.put("type", "string");
        ArrayNode acaoWebcamEnum = MAPPER.createArrayNode();
        acaoWebcamEnum.add("ligar");
        acaoWebcamEnum.add("desligar");
        acaoWebcamEnum.add("status");
        acaoWebcamProp.set("enum", acaoWebcamEnum);
        acaoWebcamProp.put("description", "ligar, desligar ou status");
        controlarWebcamProps.set("acao", acaoWebcamProp);
        controlarWebcamSchema.set("properties", controlarWebcamProps);
        ArrayNode controlarWebcamRequired = MAPPER.createArrayNode();
        controlarWebcamRequired.add("acao");
        controlarWebcamSchema.set("required", controlarWebcamRequired);
        controlarWebcam.set("input_schema", controlarWebcamSchema);
        tools.add(controlarWebcam);

        // Para adicionar uma nova ferramenta:
        // 1) Defina aqui o nome, descrição e schema de entrada.
        // 2) Implemente a execução real no método executeTool() abaixo.
        return tools;
    }

    /**
     * Executa de fato a ferramenta pedida pela IA. É aqui que entram
     * Selenium (navegador), leitura de arquivos, chamadas a outras APIs, etc.
     */
    private static String executeTool(String toolName, JsonNode input) {
        switch (toolName) {
            case "obter_hora_atual":
                return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
            case "abrir_pagina":
                return BrowserTool.openPage(input.path("url").asText());
            case "extrair_texto_pagina":
                return BrowserTool.extractText();
            case "clicar_link":
                return BrowserTool.clickLink(input.path("texto").asText());
            case "listar_skills":
                return SkillManager.listSkills();
            case "ler_skill":
                return SkillManager.readSkill(input.path("nome").asText());
            case "salvar_nota":
                return NotesTool.addNote(input.path("texto").asText());
            case "listar_notas":
                return NotesTool.listNotes();
            case "lembrar_sobre_mim":
                return MemoryTool.rememberFact(input.path("informacao").asText());
            case "abrir_aplicativo":
                return AppTool.openApp(input.path("nome").asText());
            case "tocar_musica_spotify":
                return SpotifyTool.playTrack(input.path("busca").asText());
            case "controlar_escuta_automatica": {
                boolean ativar = input.path("ativar").asBoolean(true);
                VoiceControlState.setAutoListenEnabled(ativar);
                return ativar
                        ? "Escuta automática ativada novamente."
                        : "Escuta automática desativada. O botão FALAR continua funcionando normalmente.";
            }
            case "controlar_webcam":
                return WebcamTool.control(input.path("acao").asText());
            case "controlar_volume":
                return VolumeTool.adjust(input.path("acao").asText(), input.path("quantidade").asInt(0));
            default:
                return "Erro: ferramenta '" + toolName + "' não implementada.";
        }
    }
}
