Resumir Vídeo do YouTube

Use esta skill quando o usuário mandar um link do YouTube (ou pedir para
resumir um vídeo) e quiser saber do que se trata sem assistir.

Importante: o Jarvis não assiste ao vídeo nem ouve o áudio. O resumo é feito
com base no título, descrição e informações da página do vídeo, então é um
resumo do que o vídeo PROMETE tratar, não do conteúdo falado em si. Deixe
isso claro para o usuário.

Passos:
1. Use abrir_pagina com a URL do vídeo informada pelo usuário.
2. Use extrair_texto_pagina para ler o título, nome do canal, descrição e
   informações visíveis na página.
3. Com base nesse texto, escreva um resumo de até 5 frases sobre o assunto
   do vídeo, o que ele parece abordar, e quem é o canal.
4. Avise o usuário que esse resumo é baseado no título/descrição da página
   (o Jarvis não assiste ao vídeo), então pode não cobrir todos os detalhes
   do conteúdo falado.
5. Se a página não carregar informações suficientes (ex: link inválido),
   avise em vez de inventar um resumo.
