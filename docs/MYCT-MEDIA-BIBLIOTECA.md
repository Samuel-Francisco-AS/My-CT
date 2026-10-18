# MYCT-Media — Consultar biblioteca existente (MVP)

Estado: implementado na branch myct-media-library-browser sobre myct-media-ux-folders. Não foi compilado nem testado no Fedora ou no Moto G06 nesta entrega. Sem merge e sem instalação automática.

## Objetivo

Evitar títulos duplicados ao importar vídeos para uma obra existente. Os nomes exibidos pelo Jellyfin podem vir dos metadados e estar traduzidos; o My-CT precisa do título correspondente à pasta real.

Na tela Downloads → Mídia → Organizar manualmente, acessar Biblioteca, consultar as pastas e expandir um título para ver:
- Categoria e nome da pasta real: Anime = Media/Series, Série = Media/Series-TV, Filme = Media/Filmes.
- Título sem o sufixo de ano, apropriado ao campo Título do organizador.
- Número de vídeos encontrados e quantos estão registrados no estado de hardlinks do My-CT.
- Para séries/animes, temporadas Season NN, números de episódios extraídos do nome dos vídeos, lacunas entre 1 e o maior número encontrado e arquivos sem número reconhecível.
- Para filmes, título e ano retirados do nome do diretório, quando presentes.

Os dados vêm do sistema de arquivos Fedora, não da API do Jellyfin. Portanto mostram o que está nas pastas, e NÃO garantem como o Jellyfin nomeia/apresenta o item ou se todas as bibliotecas estão configuradas no Jellyfin. 'Lacunas' só significam números ausentes de 1 até o maior encontrado; podem refletir temporadas incompletas ou convenções diferentes.

### Usar no organizador

Um toque em Usar título e temporada copia categoria e título exato da pasta, configura a temporada selecionada e sugere o número seguinte ao maior episódio encontrado. O usuário pode editar esses campos antes de selecionar fontes e confirmar a prévia. Ação Usar título (nova temporada) sugere a temporada seguinte e episódio 1. Para filmes, também utiliza ano do diretório quando identificado. **Nenhum hardlink é criado na consulta ou ao usar o título**.

Exemplo:
- Jellyfin mostra Frieren e a Jornada para o Além.
- A pasta real é Media/Series/Frieren Beyond Journeys End (2023).
- O organizador recebe Frieren Beyond Journeys End e reconhece a pasta existente, evitando nova pasta em português.

## Arquivos da alteração

- ops/myct-media-library: CLI de leitura, myct-media-library list, sem alterar mídia ou estado do gerenciador.
- ops/tests/test_media_library.py: testes de título com ano, temporadas, lacunas, filmes, symlinks.
- android/app/src/main/java/dev/myct/Media.kt: parser da resposta e ação SSH tipada.
- MediaModel.kt: consulta e estado da biblioteca.
- MediaLibrarySection.kt: visualização expansível e botão para usar título/temporada.
- MediaBatchScreen.kt: aba Biblioteca inserida no organizador manual, com atalhos e preenchimento.
- MediaLibraryTest.kt: testes de contrato e formatação da numeração.
- android/app/build.gradle.kts: candidato 0.7.7-media-library-rc1, versionCode 36.

## Testes sugeridos

Usar novo worktree separado, sem alterar main, PR #38 ou PR #39:

    cd ~/Projetos/My-CT
    git fetch origin
    git worktree add --detach ../My-CT-media-library origin/myct-media-library-browser
    cd ../My-CT-media-library

    python3 -m py_compile ops/myct-media-library
    python3 -m unittest discover -s ops/tests -p 'test_media*.py' -v

    cd android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain

Se testes passarem, atualizar apenas o helper novo, sem tocar nos outros:

    cd ~/Projetos/My-CT-media-library
    install -Dm0700 ops/myct-media-library "$HOME/.local/bin/myct-media-library"
    myct-media-library list | python3 -c 'import json,sys; d=json.load(sys.stdin); print("Obras:",len(d["groups"])); [(print(x["kind"],x["path"],"episódios:",[s["episodes"] for s in x["seasons"]])) for x in d["groups"]]'

Confirmar versão do APK 36 e assinatura original antes de atualizar por cima da instalação sem limpar dados; não desinstalar.

Aceite físico:
1. Consultar biblioteca existente e encontrar Frieren, FMA e respectivos episódios.
2. Expandir Frieren e usar título e temporada 1; campo Título deve preencher Frieren Beyond Journeys End.
3. Confirmar que o próximo episódio é sugerido, sem criar nenhum link.
4. Alternar para nova temporada, validar temporada 2 e primeiro episódio 1.
5. Consultar título já importado manualmente e confirmar que aparece mesmo não sendo gerenciado pelo My-CT.
6. Sem execução de sync, nenhum serviço/timer e nenhum merge sem aceite do usuário.
