Criar Lembretes e Anotações

Use esta skill quando o usuário pedir para anotar algo, criar um lembrete,
guardar uma informação para depois, ou perguntar o que ele tinha anotado
anteriormente.

Passos para SALVAR um lembrete/anotação:
1. Identifique claramente o texto que deve ser guardado (reescreva de forma
   objetiva se o usuário falou de forma solta ou por voz).
2. Use a ferramenta salvar_nota passando esse texto.
3. Confirme ao usuário que a nota foi salva, repetindo o conteúdo salvo.

Passos para CONSULTAR lembretes/anotações:
1. Use a ferramenta listar_notas.
2. Se houver notas, mostre-as organizadas (data/hora + texto de cada uma).
3. Se o usuário pedir algo específico (ex: "o que anotei sobre o mercado"),
   procure entre as notas retornadas e responda apenas o que for relevante.
4. Se não houver nenhuma nota salva, avise o usuário em vez de inventar uma.

Observação: os lembretes ficam salvos em um arquivo de texto simples e
persistem entre execuções do Jarvis (não se perdem quando o programa fecha).
Não é possível editar ou apagar uma nota específica ainda — apenas adicionar
novas e listar todas.
