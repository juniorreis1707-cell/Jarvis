package com.jarvis;

import com.fasterxml.jackson.databind.node.ArrayNode;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.animation.Transition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interface visual do Jarvis: painel HUD holográfico sci-fi com um portal
 * circular único — sua webcam aparece de verdade dentro de um anel de "fios"
 * de energia que muda de cor e pulsa conforme o estado (ocioso, ouvindo,
 * pensando, falando) — ao lado de um painel de leitura que alterna sozinho
 * entre cartões biométricos decorativos, mais relógio digital, cantos
 * estilo mira/HUD, e um chat com balões de mensagem.
 *
 * Reaproveita a mesma lógica de conversa/ferramentas do JarvisAgent, usada
 * também pela versão em texto (Main.java) — só a "casca" visual muda.
 */
public class JarvisFxApp extends Application {

    private ArrayNode messages;
    private String apiKey;

    // Garante que só um "turno" (pergunta + resposta) roda de cada vez, na
    // ordem em que chegaram — evita duas respostas sendo processadas/faladas
    // ao mesmo tempo, que causava a fala "por cima" quando o usuário mandava
    // duas mensagens seguidas.
    private final ExecutorService turnExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jarvis-turn");
        t.setDaemon(true);
        return t;
    });
    // Sempre que uma nova mensagem é enviada, esse número sobe. Um turno só
    // fala/atualiza a tela se ainda for o mais recente quando a resposta
    // chegar — senão, é "esquecido" silenciosamente (o usuário já seguiu
    // para outra coisa).
    private final AtomicLong turnCounter = new AtomicLong(0);

    // Suporte ao "modo sempre ouvindo": evita que duas gravações tentem usar
    // o microfone ao mesmo tempo (manual + automática), e deixa qualquer
    // thread saber o estado atual sem precisar estar na thread da interface.
    private final AtomicBoolean micLock = new AtomicBoolean(false);
    private volatile JarvisState currentState = JarvisState.IDLE;

    /** Peças visuais do portal central (anéis, fios de energia, câmera e núcleo de reserva). */
    private Circle ringOuter;
    private Circle ringInner;
    private Circle rim;
    private Circle fallbackCore;
    private final List<Ellipse> swirls = new ArrayList<>();
    private final List<RotateTransition> spins = new ArrayList<>();
    private ScaleTransition corePulse;
    private Color currentCoreColor = Color.web("#29b6f6");

    // Câmera dentro do portal
    private ImageView webcamView;
    private WritableImage webcamImage;

    // Painel de leitura biométrica (decorativo) que alterna sozinho
    private record HudState(String title, String subtitle, String icon, Color color) {}
    private static final HudState[] HUD_STATES = {
            new HudState("Panorama de saúde", "Cruzando os sinais mais recentes das suas fontes pessoais.", "◎", Color.web("#1fd6ff")),
            new HudState("Prontidão fisiológica", "Estado de recuperação do corpo para o dia.", "⚡", Color.web("#b6ff3c")),
            new HudState("Oxigênio durante o sono", "Saturação de oxigênio ao longo da noite.", "≋", Color.web("#3cf0d0")),
            new HudState("Ciclo de sono", "Fases do sono registradas na última noite.", "☾", Color.web("#b388ff"))
    };
    private int hudStateIndex = 0;
    private VBox hudTextBlock;
    private Label hudTitleLabel;
    private Label hudSubtitleLabel;
    private Label hudIconLabel;

    private Label statusLabel;
    private Label clockLabel;
    private VBox messagesBox;
    private ScrollPane chatScroll;
    private TextField input;
    private Button sendButton;
    private Button talkButton;
    private Canvas gridCanvas;
    private Circle voiceDot;
    private Label voiceValueLabel;

    private enum JarvisState { IDLE, LISTENING, THINKING, SPEAKING }

    private static final double CAM_RADIUS = 120;
    private static final double PORTAL_SIZE = 370;

    @Override
    public void start(Stage stage) {
        apiKey = System.getenv("ANTHROPIC_API_KEY");
        messages = JarvisAgent.newConversation();

        // --- Barra superior: título com brilho + relógio digital ---
        Label title = new Label("J . A . R . V . I . S .");
        title.getStyleClass().add("hud-title");

        clockLabel = new Label();
        clockLabel.getStyleClass().add("hud-clock");
        updateClock();
        Timeline clockTick = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock()));
        clockTick.setCycleCount(Timeline.INDEFINITE);
        clockTick.play();

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(title, titleSpacer, clockLabel);
        titleRow.setAlignment(Pos.CENTER);
        titleRow.setPadding(new Insets(18, 34, 0, 34));

        // --- Painel principal: leitura biométrica (esq.) + portal com webcam (centro) + ícone (dir.) ---
        HBox hudPanel = buildHudPanel();

        applyCoreColor(currentCoreColor);

        statusLabel = new Label("SISTEMA OCIOSO");
        statusLabel.setStyle("-fx-text-fill: #7fdfff; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 15px; -fx-letter-spacing: 2px;");

        // --- Painel de diagnóstico: pequenos indicadores estilo HUD, com o status real de cada subsistema ---
        HBox readoutsRow = buildReadoutsRow();

        VBox orbBox = new VBox(18, titleRow, hudPanel, statusLabel, readoutsRow);
        orbBox.setAlignment(Pos.CENTER);
        orbBox.setPadding(new Insets(6, 0, 20, 0));

        // --- Chat: balões de mensagem em vez de texto corrido, com rolagem automática ---
        messagesBox = new VBox(10);
        messagesBox.setPadding(new Insets(10, 6, 10, 6));

        chatScroll = new ScrollPane(messagesBox);
        chatScroll.getStyleClass().add("chat-scroll");
        chatScroll.setFitToWidth(true);
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // --- Barra de entrada ---
        input = new TextField();
        input.setPromptText("Digite um comando...");
        input.getStyleClass().add("hud-field");
        input.setOnAction(e -> handleSend());

        sendButton = new Button("ENVIAR");
        sendButton.getStyleClass().add("hud-button");
        sendButton.setOnAction(e -> handleSend());

        talkButton = new Button("🎤 FALAR");
        talkButton.getStyleClass().add("hud-button");
        talkButton.setOnAction(e -> handleTalk());

        HBox inputBox = new HBox(10, input, sendButton, talkButton);
        inputBox.setAlignment(Pos.CENTER);
        inputBox.setPadding(new Insets(14, 20, 20, 20));
        HBox.setHgrow(input, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(orbBox);
        root.setCenter(chatScroll);
        root.setBottom(inputBox);
        // Fundo levemente translúcido (em vez de opaco) para o grid tecnológico por trás aparecer sutilmente.
        root.setStyle("-fx-background-color: transparent;");
        BorderPane.setMargin(chatScroll, new Insets(0, 20, 0, 20));

        // --- Grid tecnológico de fundo (estilo central de comando/sala de operações) ---
        gridCanvas = new Canvas();
        gridCanvas.setMouseTransparent(true);

        // --- Cantos decorativos estilo HUD/mira, sobrepostos por cima de tudo ---
        // --- Feixe de varredura holográfico: uma faixa de luz que desce lentamente pela tela ---
        Rectangle scanBeam = new Rectangle();
        scanBeam.setHeight(150);
        scanBeam.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#4fd0ff", 0)),
                new Stop(0.5, Color.web("#4fd0ff", 0.09)),
                new Stop(1, Color.web("#4fd0ff", 0))));
        scanBeam.setMouseTransparent(true);

        StackPane sceneRoot = new StackPane(gridCanvas, scanBeam, root, buildCornerBrackets());
        StackPane.setAlignment(scanBeam, Pos.TOP_LEFT);
        scanBeam.widthProperty().bind(sceneRoot.widthProperty());
        TranslateTransition scanMove = new TranslateTransition(Duration.seconds(7), scanBeam);
        scanMove.setFromY(-150);
        scanMove.setToY(1300);
        scanMove.setCycleCount(TranslateTransition.INDEFINITE);
        scanMove.setInterpolator(Interpolator.LINEAR);
        scanMove.play();
        gridCanvas.widthProperty().bind(sceneRoot.widthProperty());
        gridCanvas.heightProperty().bind(sceneRoot.heightProperty());
        gridCanvas.widthProperty().addListener((obs, o, n) -> drawGrid());
        gridCanvas.heightProperty().addListener((obs, o, n) -> drawGrid());

        Scene scene = new Scene(sceneRoot, 900, 720);
        scene.getStylesheets().add(getClass().getResource("/jarvis.css").toExternalForm());
        stage.setTitle("JARVIS");
        stage.setOnCloseRequest(e -> WebcamTool.stop());
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        WebcamTool.start();
        startWebcamRenderLoop();
        // Se depois de alguns segundos a câmera ainda não deu imagem, mostra o motivo no chat.
        PauseTransition webcamCheck = new PauseTransition(Duration.seconds(8));
        webcamCheck.setOnFinished(e -> {
            if (!webcamView.isVisible()) {
                addSystemLine("Webcam sem imagem — " + WebcamTool.getStatus());
            }
        });
        webcamCheck.play();

        if (apiKey == null || apiKey.isBlank()) {
            addSystemLine("[ERRO] Defina a variável de ambiente ANTHROPIC_API_KEY antes de rodar.");
            input.setDisable(true);
            sendButton.setDisable(true);
            talkButton.setDisable(true);
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "A variável ANTHROPIC_API_KEY não está definida. Configure-a na Run Configuration do IntelliJ.");
            alert.show();
        } else {
            playBootSequence(() -> {
                addSystemLine("Jarvis online. Pronto para receber comandos.");
                addSystemLine("Escuta automática ativada — pode falar a qualquer momento, sem precisar clicar em FALAR.");
                startAutoListenLoop();
            });
        }
    }

    /**
     * Desenha o fundo holográfico: gradiente azul profundo com luz no centro
     * (como a tela do vídeo de referência) e um "chão" de grade em perspectiva,
     * com linhas convergindo para o horizonte e um brilho na linha do horizonte.
     */
    private void drawGrid() {
        double w = gridCanvas.getWidth();
        double h = gridCanvas.getHeight();
        if (w <= 0 || h <= 0) return;
        GraphicsContext gc = gridCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        gc.setFill(new RadialGradient(0, 0, w * 0.5, h * 0.38, Math.max(w, h) * 0.8, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#1450b4")),
                new Stop(0.45, Color.web("#0b2a6e")),
                new Stop(1, Color.web("#040a1f"))));
        gc.fillRect(0, 0, w, h);

        double horizon = h * 0.52;
        double floorHeight = h - horizon;

        // brilho suave na linha do horizonte
        gc.setFill(new LinearGradient(0, horizon - 50, 0, horizon + 70, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5cc8ff", 0)),
                new Stop(0.45, Color.web("#5cc8ff", 0.16)),
                new Stop(1, Color.web("#5cc8ff", 0))));
        gc.fillRect(0, horizon - 50, w, 120);

        // linhas horizontais do chão (espaçamento cresce com a proximidade)
        gc.setLineWidth(1);
        int rows = 16;
        for (int i = 1; i <= rows; i++) {
            double t = i / (double) rows;
            double y = horizon + floorHeight * t * t;
            gc.setStroke(Color.web("#6fd0ff", 0.05 + 0.22 * t));
            gc.strokeLine(0, y, w, y);
        }
        // linhas de fuga: saem de perto do ponto de fuga e abrem para a borda de baixo
        int cols = 22;
        for (int i = -cols; i <= cols; i++) {
            double xTop = w * 0.5 + i * (w * 0.006);
            double xBottom = w * 0.5 + i * (w * 0.075);
            gc.setStroke(Color.web("#6fd0ff", 0.17));
            gc.strokeLine(xTop, horizon, xBottom, h);
        }
    }

    /**
     * Pequena sequência de "inicialização de sistemas" ao ligar o Jarvis — puramente
     * visual/cosmética (o carregamento de verdade já aconteceu antes da tela abrir),
     * mas dá aquele clima de painel de comando ligando aos poucos.
     */
    private void playBootSequence(Runnable onFinished) {
        String[] bootLines = {
                "> Inicializando núcleo do Jarvis...",
                "> Carregando reconhecimento de voz...",
                "> Sincronizando memória permanente...",
                "> Conectando à inteligência central...",
                "> Todos os sistemas operacionais."
        };
        SequentialTransition sequence = new SequentialTransition();
        for (String line : bootLines) {
            PauseTransition pause = new PauseTransition(Duration.millis(260));
            pause.setOnFinished(e -> addSystemLine(line));
            sequence.getChildren().add(pause);
        }
        sequence.setOnFinished(e -> onFinished.run());
        sequence.play();
    }

    /** Monta a fileira de indicadores de status (voz, IA, memória), com o estado real de cada um. */
    private HBox buildReadoutsRow() {
        boolean connected = apiKey != null && !apiKey.isBlank();
        int memoryFacts = countMemoryFacts();

        HBox row = new HBox(26,
                buildVoiceChip(),
                statusChip("IA CLAUDE", connected ? "CONECTADO" : "OFFLINE",
                        connected ? Color.web("#00ff9d") : Color.web("#ff5f5f")),
                statusChip("MEMÓRIA", memoryFacts + " REGISTROS", Color.web("#1fd6ff"))
        );
        row.setAlignment(Pos.CENTER);
        return row;
    }

    /**
     * Chip de "RECONHEC. DE VOZ", igual aos outros, mas guarda referências pra
     * poder atualizar (bolinha + texto) ao vivo quando a escuta automática for
     * ativada/desativada pelo usuário (por voz ou texto).
     */
    private HBox buildVoiceChip() {
        voiceDot = new Circle(4);
        voiceDot.setEffect(new DropShadow(8, Color.web("#00ff9d")));

        Label labelNode = new Label("RECONHEC. DE VOZ:");
        labelNode.setStyle("-fx-text-fill: #4d7a8c; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 10px; -fx-letter-spacing: 1px;");

        voiceValueLabel = new Label();
        voiceValueLabel.setStyle("-fx-text-fill: #cdeeff; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 10px; -fx-font-weight: bold; -fx-letter-spacing: 1px;");

        refreshVoiceReadout();

        HBox chip = new HBox(6, voiceDot, labelNode, voiceValueLabel);
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** Atualiza a bolinha/texto do indicador de voz para refletir o estado atual da escuta automática. */
    private void refreshVoiceReadout() {
        boolean enabled = VoiceControlState.isAutoListenEnabled();
        Color color = enabled ? Color.web("#00ff9d") : Color.web("#ff5f5f");
        voiceDot.setFill(color);
        ((DropShadow) voiceDot.getEffect()).setColor(color);
        voiceValueLabel.setText(enabled ? "ONLINE" : "DESATIVADO");
    }

    private int countMemoryFacts() {
        String memory = MemoryTool.loadAll();
        if (memory == null || memory.isBlank()) return 0;
        return (int) java.util.Arrays.stream(memory.split("\n")).filter(l -> !l.isBlank()).count();
    }

    /** Um "chip" de diagnóstico: uma bolinha colorida com brilho + rótulo + valor, estilo painel de status. */
    private HBox statusChip(String label, String value, Color color) {
        Circle dot = new Circle(4, color);
        DropShadow glow = new DropShadow(8, color);
        dot.setEffect(glow);

        Label labelNode = new Label(label + ":");
        labelNode.setStyle("-fx-text-fill: #4d7a8c; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 10px; -fx-letter-spacing: 1px;");

        Label valueNode = new Label(value);
        valueNode.setStyle("-fx-text-fill: #cdeeff; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 10px; -fx-font-weight: bold; -fx-letter-spacing: 1px;");

        HBox chip = new HBox(6, dot, labelNode, valueNode);
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }

    /** Monta os 4 cantos decorativos (estilo mira/HUD) que ficam sobrepostos na janela, sem capturar cliques. */
    private Pane buildCornerBrackets() {
        Pane overlay = new Pane();
        overlay.setMouseTransparent(true);
        overlay.setPickOnBounds(false);

        double size = 34;
        double margin = 14;
        Color color = Color.web("#1fd6ff", 0.55);

        Polyline topLeft = bracket(size, color, true, true);
        Polyline topRight = bracket(size, color, false, true);
        Polyline bottomLeft = bracket(size, color, true, false);
        Polyline bottomRight = bracket(size, color, false, false);

        StackPane.setAlignment(overlay, Pos.CENTER);
        overlay.getChildren().addAll(topLeft, topRight, bottomLeft, bottomRight);

        // Reposiciona os cantos sempre que o tamanho do overlay mudar (janela redimensionada).
        overlay.widthProperty().addListener((obs, o, n) -> {
            double w = n.doubleValue();
            topRight.setTranslateX(w - size - margin);
            bottomRight.setTranslateX(w - size - margin);
        });
        overlay.heightProperty().addListener((obs, o, n) -> {
            double h = n.doubleValue();
            bottomLeft.setTranslateY(h - size - margin);
            bottomRight.setTranslateY(h - size - margin);
        });
        topLeft.setTranslateX(margin);
        topLeft.setTranslateY(margin);
        topRight.setTranslateY(margin);
        bottomLeft.setTranslateX(margin);

        return overlay;
    }

    private Polyline bracket(double size, Color color, boolean left, boolean top) {
        double x1 = left ? 0 : size;
        double xm = left ? 0 : size;
        double x2 = left ? size : 0;
        double y1 = top ? size : 0;
        double ym = top ? 0 : size;
        double y2 = top ? 0 : size;
        Polyline line = new Polyline(x1, y1, xm, ym, x2, y2);
        line.setStroke(color);
        line.setStrokeWidth(2);
        return line;
    }

    private void updateClock() {
        clockLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /**
     * Loop em segundo plano que fica sempre ouvindo o microfone, sem precisar
     * clicar em FALAR. Só escuta quando o Jarvis está OCIOSO (não enquanto
     * pensa ou fala, para não se ouvir a si mesmo pelo alto-falante), e usa o
     * mesmo micLock do botão FALAR para nunca disputar o microfone com ele.
     */
    private void startAutoListenLoop() {
        Thread autoListen = new Thread(() -> {
            while (true) {
                try {
                    if (currentState != JarvisState.IDLE || !VoiceControlState.isAutoListenEnabled()) {
                        Thread.sleep(300);
                        continue;
                    }
                    if (!micLock.compareAndSet(false, true)) {
                        Thread.sleep(300);
                        continue;
                    }

                    String heard;
                    try {
                        heard = AudioTool.listen(60);
                    } finally {
                        micLock.set(false);
                    }

                    if (heard != null && !heard.isBlank() && !heard.startsWith("Erro")) {
                        String finalHeard = heard;
                        Platform.runLater(() -> addUserBubble(finalHeard));
                        Future<?> turno = runTurnInBackground(heard);
                        try {
                            turno.get(); // espera esse turno terminar (inclusive de falar) antes de ouvir de novo
                        } catch (Exception ignored) {
                            // Se der erro no turno, o próprio runTurnInBackground já loga; aqui só seguimos o loop.
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // Nunca deixa o loop "morrer" por causa de um erro pontual de uma gravação.
                }
            }
        }, "jarvis-auto-listen");
        autoListen.setDaemon(true);
        autoListen.start();
    }

    /**
     * Monta o painel principal: à esquerda o cartão de leitura biométrica
     * (que alterna sozinho entre telas decorativas), no centro o portal com a
     * webcam, e à direita o ícone grande do cartão atual + um coração pulsando.
     */
    private HBox buildHudPanel() {
        // --- Coluna esquerda: leitura biométrica (decorativa) ---
        Label category = new Label("MÓDULO BIOMÉTRICO · DEMO");
        category.getStyleClass().add("hud-category");

        hudTitleLabel = new Label();
        hudTitleLabel.getStyleClass().add("hud-card-title");
        hudTitleLabel.setWrapText(true);

        hudSubtitleLabel = new Label();
        hudSubtitleLabel.getStyleClass().add("hud-card-subtitle");
        hudSubtitleLabel.setWrapText(true);

        Region track = new Region();
        track.getStyleClass().add("hud-progress-track");
        track.setPrefSize(230, 3);
        track.setMaxSize(230, 3);
        Region fill = new Region();
        fill.getStyleClass().add("hud-progress-fill");
        fill.setPrefSize(230, 3);
        fill.setMaxSize(230, 3);
        StackPane progress = new StackPane(track, fill);
        progress.setMaxWidth(230);
        progress.setAlignment(Pos.CENTER_LEFT);
        // Barra "viva": vai e volta sem representar nenhum número real.
        ScaleTransition progressAnim = new ScaleTransition(Duration.seconds(2.4), fill);
        progressAnim.setFromX(0.25);
        progressAnim.setToX(1.0);
        progressAnim.setCycleCount(ScaleTransition.INDEFINITE);
        progressAnim.setAutoReverse(true);
        progressAnim.setInterpolator(Interpolator.EASE_BOTH);
        fill.setTranslateX(0);
        progressAnim.play();

        hudTextBlock = new VBox(8, category, hudTitleLabel, hudSubtitleLabel, progress);
        hudTextBlock.setAlignment(Pos.CENTER_LEFT);
        hudTextBlock.setPrefWidth(260);
        hudTextBlock.setMaxWidth(260);

        // --- Coluna direita: ícone do cartão + coração pulsando ---
        hudIconLabel = new Label();
        hudIconLabel.getStyleClass().add("hud-card-icon");
        hudIconLabel.setEffect(new DropShadow(22, Color.web("#1fd6ff")));

        Label heart = new Label("♥");
        heart.setStyle("-fx-text-fill: #ff3355; -fx-font-size: 30px;");
        heart.setEffect(new DropShadow(16, Color.web("#ff3355")));
        ScaleTransition heartBeat = new ScaleTransition(Duration.millis(520), heart);
        heartBeat.setFromX(1.0);
        heartBeat.setFromY(1.0);
        heartBeat.setToX(1.25);
        heartBeat.setToY(1.25);
        heartBeat.setCycleCount(ScaleTransition.INDEFINITE);
        heartBeat.setAutoReverse(true);
        heartBeat.setInterpolator(Interpolator.EASE_BOTH);
        heartBeat.play();

        VBox iconColumn = new VBox(14, hudIconLabel, heart);
        iconColumn.setAlignment(Pos.CENTER);
        iconColumn.setPrefWidth(260);
        iconColumn.setMaxWidth(260);

        applyHudState(HUD_STATES[0]);
        Timeline hudCycle = new Timeline(new KeyFrame(Duration.seconds(5), e -> advanceHudState()));
        hudCycle.setCycleCount(Timeline.INDEFINITE);
        hudCycle.play();

        HBox panel = new HBox(20, hudTextBlock, buildPortal(), iconColumn);
        panel.setAlignment(Pos.CENTER);
        panel.getStyleClass().add("hud-scanpanel");
        panel.setMaxWidth(Region.USE_PREF_SIZE);
        return panel;
    }

    private void applyHudState(HudState state) {
        hudTitleLabel.setText(state.title());
        hudSubtitleLabel.setText(state.subtitle());
        hudIconLabel.setText(state.icon());
        hudIconLabel.setStyle("-fx-text-fill: " + toHex(state.color()) + ";");
        ((DropShadow) hudIconLabel.getEffect()).setColor(state.color());
    }

    /** Troca o cartão exibido com um fade suave (some, troca o texto, reaparece). */
    private void advanceHudState() {
        hudStateIndex = (hudStateIndex + 1) % HUD_STATES.length;
        HudState next = HUD_STATES[hudStateIndex];

        FadeTransition out = new FadeTransition(Duration.millis(300), hudTextBlock);
        out.setToValue(0);
        out.setOnFinished(e -> {
            applyHudState(next);
            FadeTransition in = new FadeTransition(Duration.millis(300), hudTextBlock);
            in.setToValue(1);
            in.play();
        });
        out.play();
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    /**
     * Portal central: sua webcam num círculo, cercada por anéis tracejados
     * girando em sentidos opostos e três "fios" elípticos inclinados que
     * giram em velocidades diferentes (o efeito de energia enrolada do vídeo
     * de referência), com marcas de mira nos quatro cantos. Se não houver
     * webcam, o núcleo brilhante antigo aparece no lugar da imagem.
     */
    private StackPane buildPortal() {
        ringOuter = new Circle(CAM_RADIUS + 42);
        ringOuter.setFill(Color.TRANSPARENT);
        ringOuter.setStroke(Color.web("#1fd6ff", 0.35));
        ringOuter.setStrokeWidth(1.5);
        ringOuter.setStrokeType(StrokeType.OUTSIDE);

        ringInner = new Circle(CAM_RADIUS + 22);
        ringInner.setFill(Color.TRANSPARENT);
        ringInner.setStroke(Color.web("#1fd6ff", 0.55));
        ringInner.setStrokeWidth(1.5);

        spins.add(spin(ringOuter, 9, 360));
        spins.add(spin(ringInner, 14, -360));

        // Fios de energia: elipses finas inclinadas, cada uma girando no próprio ritmo
        double[][] swirlSpecs = {
                // rx, ry, ângulo inicial, segundos por volta (negativo = sentido contrário)
                {CAM_RADIUS + 30, CAM_RADIUS + 8, 20, 7},
                {CAM_RADIUS + 26, CAM_RADIUS + 14, 75, -9},
                {CAM_RADIUS + 34, CAM_RADIUS + 4, 130, 12}
        };
        Group swirlGroup = new Group();
        for (double[] spec : swirlSpecs) {
            Ellipse swirl = new Ellipse(spec[0], spec[1]);
            swirl.setFill(Color.TRANSPARENT);
            swirl.setStroke(Color.web("#1fd6ff", 0.55));
            swirl.setStrokeWidth(1.2);
            swirl.setRotate(spec[2]);
            swirls.add(swirl);
            swirlGroup.getChildren().add(swirl);
            double seconds = Math.abs(spec[3]);
            spins.add(spin(swirl, seconds, spec[3] < 0 ? -360 : 360, spec[2]));
        }

        // Núcleo de reserva (aparece enquanto a câmera não entrega imagem, ou se não houver webcam)
        fallbackCore = new Circle(CAM_RADIUS);
        DropShadow glow = new DropShadow();
        glow.setRadius(CAM_RADIUS * 0.55);
        glow.setSpread(0.35);
        fallbackCore.setEffect(glow);

        // Imagem da webcam recortada em círculo
        webcamImage = new WritableImage(WebcamTool.SIZE, WebcamTool.SIZE);
        webcamView = new ImageView(webcamImage);
        webcamView.setFitWidth(CAM_RADIUS * 2);
        webcamView.setFitHeight(CAM_RADIUS * 2);
        webcamView.setPreserveRatio(false);
        webcamView.setSmooth(true);
        webcamView.setClip(new Circle(CAM_RADIUS, CAM_RADIUS, CAM_RADIUS));
        webcamView.setVisible(false);

        // Aro brilhante em volta da imagem (muda de cor conforme o estado)
        rim = new Circle(CAM_RADIUS + 2);
        rim.setFill(Color.TRANSPARENT);
        rim.setStrokeWidth(2.5);
        DropShadow rimGlow = new DropShadow();
        rimGlow.setRadius(18);
        rimGlow.setSpread(0.3);
        rim.setEffect(rimGlow);

        StackPane core = new StackPane(fallbackCore, webcamView, rim);
        core.setMaxSize(CAM_RADIUS * 2 + 6, CAM_RADIUS * 2 + 6);

        corePulse = new ScaleTransition(Duration.seconds(1.3), core);
        corePulse.setFromX(1.0);
        corePulse.setFromY(1.0);
        corePulse.setToX(1.035);
        corePulse.setToY(1.035);
        corePulse.setCycleCount(ScaleTransition.INDEFINITE);
        corePulse.setAutoReverse(true);
        corePulse.setInterpolator(Interpolator.EASE_BOTH);
        corePulse.play();

        // Marcas de mira nos 4 cantos do portal
        Pane brackets = new Pane();
        brackets.setMouseTransparent(true);
        brackets.setPrefSize(PORTAL_SIZE, PORTAL_SIZE);
        brackets.setMaxSize(PORTAL_SIZE, PORTAL_SIZE);
        double bs = 24;
        Color bc = Color.web("#1fd6ff", 0.7);
        Polyline tl = bracket(bs, bc, true, true);
        Polyline tr = bracket(bs, bc, false, true);
        Polyline bl = bracket(bs, bc, true, false);
        Polyline br = bracket(bs, bc, false, false);
        tr.setTranslateX(PORTAL_SIZE - bs);
        bl.setTranslateY(PORTAL_SIZE - bs);
        br.setTranslateX(PORTAL_SIZE - bs);
        br.setTranslateY(PORTAL_SIZE - bs);
        brackets.getChildren().addAll(tl, tr, bl, br);

        Label scanLabel = new Label("BIO SCAN · AO VIVO");
        scanLabel.getStyleClass().add("hud-scan-label");
        StackPane.setAlignment(scanLabel, Pos.TOP_CENTER);

        StackPane portal = new StackPane(ringOuter, ringInner, swirlGroup, core, brackets, scanLabel);
        portal.setPrefSize(PORTAL_SIZE, PORTAL_SIZE);
        portal.setMinSize(PORTAL_SIZE, PORTAL_SIZE);
        portal.setMaxSize(PORTAL_SIZE, PORTAL_SIZE);
        return portal;
    }

    private RotateTransition spin(javafx.scene.Node node, double seconds, double byAngle) {
        return spin(node, seconds, byAngle, 0);
    }

    private RotateTransition spin(javafx.scene.Node node, double seconds, double byAngle, double startAngle) {
        RotateTransition t = new RotateTransition(Duration.seconds(seconds), node);
        t.setFromAngle(startAngle);
        t.setToAngle(startAngle + byAngle);
        t.setCycleCount(RotateTransition.INDEFINITE);
        t.setInterpolator(Interpolator.LINEAR);
        t.play();
        return t;
    }

    /** A cada quadro da tela, copia o quadro novo da webcam (se houver) para a imagem do portal. */
    private void startWebcamRenderLoop() {
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (WebcamTool.isAvailable() && WebcamTool.copyLatestInto(webcamImage)) {
                    if (!webcamView.isVisible()) {
                        webcamView.setVisible(true);
                        fallbackCore.setVisible(false);
                    }
                } else if (!WebcamTool.isAvailable() && webcamView.isVisible()) {
                    // Webcam desligada: volta para o núcleo brilhante
                    webcamView.setVisible(false);
                    fallbackCore.setVisible(true);
                }
            }
        };
        timer.start();
    }

    private void handleSend() {
        String text = input.getText().trim();
        if (text.isEmpty()) return;
        input.clear();
        addUserBubble(text);
        runTurnInBackground(text);
    }

    private void handleTalk() {
        // Se o Jarvis estiver falando algo, uma nova interação (falar de novo)
        // corta essa fala na hora, em vez de deixar as duas se misturarem.
        AudioTool.stopSpeaking();

        if (!micLock.compareAndSet(false, true)) {
            addSystemLine("Microfone ocupado no momento, tenta de novo em instantes.");
            return;
        }

        setState(JarvisState.LISTENING);
        setInputsDisabled(true);

        Task<String> listenTask = new Task<>() {
            @Override
            protected String call() {
                return AudioTool.listen(90);
            }
        };
        listenTask.setOnSucceeded(e -> {
            micLock.set(false);
            setInputsDisabled(false);
            String heard = listenTask.getValue();
            if (heard == null || heard.isBlank()) {
                setState(JarvisState.IDLE);
                addSystemLine("Não entendi nada, tente de novo.");
                return;
            }
            addUserBubble(heard);
            runTurnInBackground(heard);
        });
        listenTask.setOnFailed(e -> {
            micLock.set(false);
            setState(JarvisState.IDLE);
            setInputsDisabled(false);
            addSystemLine("Erro ao ouvir: " + listenTask.getException().getMessage());
        });
        new Thread(listenTask).start();
    }

    private Future<?> runTurnInBackground(String userText) {
        // Corta imediatamente qualquer fala anterior que ainda esteja tocando,
        // e marca essa como a pergunta "mais nova" — qualquer resposta de uma
        // pergunta anterior que chegar depois disso é descartada (esquecida).
        AudioTool.stopSpeaking();
        long myTurn = turnCounter.incrementAndGet();

        setState(JarvisState.THINKING);
        JarvisAgent.addUserMessage(messages, userText);

        return turnExecutor.submit(() -> {
            try {
                JarvisAgent.processTurn(apiKey, messages, new JarvisAgent.AgentListener() {
                    @Override
                    public void onAssistantText(String text) {
                        if (turnCounter.get() != myTurn) {
                            return; // uma pergunta mais nova já foi feita: esquece essa resposta
                        }
                        Platform.runLater(() -> {
                            addJarvisBubble(text);
                            setState(JarvisState.SPEAKING);
                        });
                        AudioTool.speak(text);
                        if (turnCounter.get() == myTurn) {
                            Platform.runLater(() -> setState(JarvisState.IDLE));
                        }
                    }

                    @Override
                    public void onToolUse(String toolName) {
                        if (turnCounter.get() != myTurn) {
                            return;
                        }
                        Platform.runLater(() -> addSystemLine("Executando: " + toolName));
                    }
                });
            } catch (Exception e) {
                if (turnCounter.get() == myTurn) {
                    Platform.runLater(() -> addSystemLine("Erro: " + e.getMessage()));
                }
            } finally {
                if (turnCounter.get() == myTurn) {
                    // Também atualiza o indicador de voz aqui: se o comando que acabou de
                    // rodar foi "desative/ative a escuta automática", isso já reflete na hora.
                    Platform.runLater(() -> {
                        setState(JarvisState.IDLE);
                        refreshVoiceReadout();
                    });
                }
            }
        });
    }

    /** Adiciona um balão de mensagem alinhado à direita, representando algo que você disse/digitou. */
    private void addUserBubble(String text) {
        addBubble(text, "VOCÊ", "bubble-user", "tag-user", Pos.CENTER_RIGHT);
    }

    /** Adiciona um balão de mensagem alinhado à esquerda, representando a resposta do Jarvis. */
    private void addJarvisBubble(String text) {
        addBubble(text, "JARVIS", "bubble-jarvis", "tag-jarvis", Pos.CENTER_LEFT);
    }

    private void addBubble(String text, String tag, String bubbleStyleClass, String tagStyleClass, Pos alignment) {
        Label tagLabel = new Label(tag);
        tagLabel.getStyleClass().addAll("bubble-tag", tagStyleClass);

        Label textLabel = new Label(text);
        textLabel.getStyleClass().add("bubble-label");
        textLabel.setWrapText(true);
        textLabel.setMaxWidth(560);

        VBox bubble = new VBox(4, tagLabel, textLabel);
        bubble.getStyleClass().add(bubbleStyleClass);
        bubble.setMaxWidth(600);

        HBox row = new HBox(bubble);
        row.setAlignment(alignment);
        row.setMaxWidth(Double.MAX_VALUE);

        messagesBox.getChildren().add(row);
        scrollChatToBottom();
    }

    /** Adiciona uma linha discreta de status/sistema (não é uma fala de ninguém), centralizada e pequena. */
    private void addSystemLine(String text) {
        Label line = new Label(text);
        line.getStyleClass().add("system-line");
        line.setWrapText(true);

        HBox row = new HBox(line);
        row.setAlignment(Pos.CENTER);
        row.setMaxWidth(Double.MAX_VALUE);

        messagesBox.getChildren().add(row);
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        // Espera o layout recalcular antes de rolar, senão a rolagem acontece
        // antes do balão novo entrar na altura total do conteúdo.
        Platform.runLater(() -> chatScroll.setVvalue(1.0));
    }

    private void setInputsDisabled(boolean disabled) {
        input.setDisable(disabled);
        sendButton.setDisable(disabled);
        talkButton.setDisable(disabled);
    }

    private void setState(JarvisState state) {
        this.currentState = state;
        switch (state) {
            case IDLE -> {
                statusLabel.setText("SISTEMA OCIOSO");
                setCoreColorAnimated(Color.web("#29b6f6"));
                setRates(0.6, 0.5);
            }
            case LISTENING -> {
                statusLabel.setText("OUVINDO...");
                setCoreColorAnimated(Color.web("#00e5ff"));
                setRates(1.6, 1.5);
            }
            case THINKING -> {
                statusLabel.setText("PROCESSANDO...");
                setCoreColorAnimated(Color.web("#b388ff"));
                setRates(1.1, 2.2);
            }
            case SPEAKING -> {
                statusLabel.setText("RESPONDENDO...");
                setCoreColorAnimated(Color.web("#00ff9d"));
                setRates(1.8, 1.0);
            }
        }
    }

    private void setRates(double pulseRate, double spinRate) {
        if (corePulse == null) return;
        corePulse.setRate(pulseRate);
        for (RotateTransition spin : spins) {
            spin.setRate(spinRate);
        }
    }

    /** Anima suavemente a transição da cor atual dos núcleos até a cor-alvo, em vez de trocar de uma vez. */
    private void setCoreColorAnimated(Color target) {
        Color from = currentCoreColor;
        currentCoreColor = target;
        Transition transition = new Transition() {
            {
                setCycleDuration(Duration.millis(450));
            }
            @Override
            protected void interpolate(double frac) {
                applyCoreColor(from.interpolate(target, frac));
            }
        };
        transition.play();
    }

    private void applyCoreColor(Color color) {
        if (rim == null) return;
        RadialGradient gradient = new RadialGradient(
                0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE),
                new Stop(0.35, color.brighter()),
                new Stop(1, color.darker().darker())
        );
        fallbackCore.setFill(gradient);
        ((DropShadow) fallbackCore.getEffect()).setColor(color);
        rim.setStroke(color);
        ((DropShadow) rim.getEffect()).setColor(color);
        ringInner.setStroke(color.deriveColor(0, 1, 1, 0.6));
        ringOuter.setStroke(color.deriveColor(0, 1, 1, 0.35));
        for (Ellipse swirl : swirls) {
            swirl.setStroke(color.deriveColor(0, 1, 1, 0.55));
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
