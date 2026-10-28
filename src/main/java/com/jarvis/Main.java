package com.jarvis;

import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Scanner;

/**
 * Versão em texto (console) do Jarvis. Usa o mesmo JarvisAgent que a
 * interface visual (JarvisFxApp.java) — essa classe só cuida de mostrar
 * tudo no terminal e ler o teclado/microfone.
 *
 * Como rodar:
 *   1) export ANTHROPIC_API_KEY=sk-ant-...   (ou set no Windows)
 *   2) mvn package
 *   3) java -jar target/jarvis-core-1.0-SNAPSHOT.jar
 */
public class Main {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Defina a variável de ambiente ANTHROPIC_API_KEY antes de rodar.");
            System.exit(1);
        }

        ArrayNode messages = JarvisAgent.newConversation();

        System.out.println("Jarvis (núcleo) pronto.");
        System.out.println("Aperte ENTER para falar (5s de gravação), ou digite sua mensagem e aperte ENTER.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\nVocê: ");
            String typed = scanner.nextLine();

            String userInput;
            if (typed.isBlank()) {
                userInput = AudioTool.listen(8);
                if (userInput.isBlank()) {
                    System.out.println("[Não entendi nada, tente de novo]");
                    continue;
                }
                System.out.println("Você disse: " + userInput);
            } else {
                userInput = typed;
            }

            if (userInput.equalsIgnoreCase("sair")) break;

            JarvisAgent.addUserMessage(messages, userInput);
            JarvisAgent.processTurn(apiKey, messages, new JarvisAgent.AgentListener() {
                @Override
                public void onAssistantText(String text) {
                    System.out.println("Jarvis: " + text);
                    AudioTool.speak(text);
                }

                @Override
                public void onToolUse(String toolName) {
                    System.out.println("[Jarvis está usando a ferramenta: " + toolName + "]");
                }
            });
        }

        scanner.close();
        BrowserTool.close();
        System.out.println("Encerrado.");
    }
}
