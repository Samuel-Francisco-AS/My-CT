# MYCT Media — Microetapa UX de navegação (0.7.6)

Estado: código publicado para testes. Base: PR #38 (myct-media-manual-batch). Não instalar ou fazer merge antes de validar.

## Problema no Moto G06

Com 134 vídeos, a seção Organizar manualmente formava uma tela muito longa: título no alto, pastas com dezenas de arquivos, um formulário para cada episódio e prévia/importação no fim.

## Melhorias

- As pastas por torrent/subpasta começam recolhidas. Clique no cabeçalho ou Ver arquivos para expandir. O cartão mostra contagem selecionada e um exemplo de arquivo.
- Marcar todos / Desmarcar funciona sem abrir a pasta. Para filmes, continua sendo um vídeo por importação.
- Ao expandir, cada arquivo é uma linha compacta em LazyColumn, carregada sob demanda.
- A seleção usa ordenação numérica natural: episódio 1, 2, 10 em vez de 1, 10, 2. Ainda é possível corrigir números individuais.
- A seção Numeração inicia recolhida, mantendo alterações quando expandida novamente.
- A prévia detalhada dos destinos também inicia recolhida, deixando visível o resumo e os conflitos.
- Barra permanente Dados / Pastas / Episódios / Prévia para navegar diretamente.
- Rodapé fixo mostra a contagem de selecionados e o botão Pré-visualizar ou Criar N hardlinks; não há botão flutuante cobrindo nomes.
- Aviso para possíveis trailers, amostras e publicidade na seleção.
- A ação de criação continua exigindo prévia válida, ausência de conflitos e confirmação explícita.

## Sem mudanças de infraestrutura

Backend, Transmission, seeding, bibliotecas Jellyfin e hardlinks existentes não sofrem alteração com a atualização visual. CLIs instaladas da microetapa anterior continuam válidas. Sem timer novo.

## Testes no Fedora

    cd ~/Projetos/My-CT
    git fetch origin
    git worktree add --detach ../My-CT-media-ux origin/myct-media-ux-folders
    cd ../My-CT-media-ux/android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain

Versão candidata: dev.myct, code 35, 0.7.6-media-ux-rc1. Inspecionar manifesto e assinatura antes de atualizar no Moto, sem desinstalar ou limpar dados.

## Aceite físico

1. Ao entrar em Downloads → Mídia → Organizar manualmente, pastas devem estar recolhidas.
2. Abrir e fechar uma pasta de 64 arquivos sem perder seleção.
3. Marcar todos / Desmarcar uma pasta recolhida, selecionar dois arquivos e conferir contagem.
4. Usar Dados / Pastas / Episódios / Prévia, verificar saltos.
5. Editar número de episódio, recolher e expandir: o número permanece.
6. Gerar prévia sem importar. O resumo aparece próximo ao rodapé e os caminhos estão recolhidos.
7. Confira que arquivos já importados não sejam sobrescritos; a função Criar hardlinks só permite confirmação com prévia válida.
8. Confirmar que Torrents, Terminal e Reconhecimento automático continuam acessíveis.

Manter PR draft, sem merge ou deploy até testes.