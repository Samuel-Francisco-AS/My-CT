# MYCT-Media — Organizador manual em lote (MVP)

**Estado:** implementação em branch/PR draft; NÃO validada no Fedora nem no Moto G06 nesta entrega. Nenhuma instalação, serviço ou biblioteca Jellyfin foi modificada remotamente.

## Objetivo e comportamento

O usuário pode abrir **Downloads → Mídia → Organizar manualmente**, consultar **vídeos concluídos dos torrents administrados pelo My-CT** e selecionar arquivos individuais ou todos os vídeos de uma pasta do torrent. A seleção funciona mesmo com nomes que não seguem convenções, porque o usuário define o destino.

Tipos:
- **Anime**: nome + temporada + episódio, destino **`~/Media/Series/<Título>/Season NN/<Título> SnnEnn.mkv`**. Mantemos o caminho histórico da biblioteca **Animes** do Jellyfin, que já aponta para `~/Media/Series`. Isso evita esconder Frieren já existente.
- **Série (live-action ou outra)**: nome + temporada + episódio, destino **`~/Media/Series-TV/<Título>/Season NN/<Título> SnnEnn.mkv`**. Para que apareça separadamente no Jellyfin, configurar manualmente a biblioteca tipo **Programas de TV**, chamada **Séries**, apontando para `~/Media/Series-TV`.
- **Filme**: título + ano, **um vídeo por importação**, destino **`~/Media/Filmes/<Título> (AAAA)/<Título> (AAAA).mkv`**. Configurar manualmente a biblioteca **Filmes** como tipo **Filmes**, apontando para `~/Media/Filmes` se ela ainda não existir.

**Atenção:** selecionar a categoria no My-CT organiza os diretórios; a classificação efetiva exibida pelo Jellyfin depende do tipo de biblioteca configurado no Jellyfin. Não alteramos automaticamente bibliotecas, credenciais ou metadados. Essa separação inicial mantém a biblioteca Animes atual sem migração. Não proponha mover a biblioteca atual para outro caminho sem planejamento e autorização.

A listagem é restrita aos vídeos concluídos que constam dos torrents conhecidos pelo `myct-torrent`; não é explorador geral de `/home` nem permite incluir caminhos arbitrários do Android. No MVP, 1 a 128 arquivos por lote (para filmes, 1), até 400 vídeos disponíveis por consulta.

## Fluxo Android

1. Conectar ao Fedora pelo SSH existente.
2. Downloads → Mídia → **Organizar manualmente** → **Consultar arquivos do Fedora**.
3. Escolher **Anime**, **Série** ou **Filme**; informar título; se episódio, temporada e número inicial.
4. Marcar uma pasta ou arquivos. Atribuições de episódios são sugeridas pela ordem alfabética dos nomes. **Conferir e corrigir individualmente** a numeração, especialmente quando os nomes não possuem zeros à esquerda ou misturam extras.
5. **Pré-visualizar destinos**. O Fedora responde, por arquivo, o destino e `ready`, `existing` ou `collision`.
6. Qualquer colisão bloqueia o lote inteiro. Desmarcar/renumerar e consultar prévia de novo. Nada será sobrescrito.
7. **Criar hardlinks… → Confirmar importação**. Só arquivos concluídos entram, sem mover/renomear o arquivo original do Transmission. Cada novo hardlink é registrado no estado da CLI imediatamente após o sucesso.
8. Os hardlinks são removíveis na seção de links gerenciados existente; ao remover, o original do Transmission fica intacto.
9. O reconhecimento automático original permanece na aba **Reconhecimento automático**, mas não é pré-requisito do fluxo manual.

### Arquitetura mínima

- `ops/myct-media`: motor atual; ajuste pontual para `Series-TV` em caminhos gerenciados.
- `ops/myct-media-bulk`: nova CLI Python stdlib, reutiliza o core instalado ao lado dela, oferece `sources`, `plan TOKEN`, `import TOKEN`.
- `ops/tests/test_media_batch.py`: testes da seleção explícita e colisões.
- `android/.../Media.kt`: contrato tipado, serialização JSON + Base64URL, parse de consultas.
- `MediaModel.kt`: usa **sessão SSH autenticada já existente**; lê prévia e envia ação somente após confirmação.
- `MediaBatchScreen.kt`: formulário, seleção por pasta/arquivo, numeração editável e prévia por arquivo.
- `MediaScreen.kt`: alterna entre modo manual e reconhecimento automático.

Token Base64URL não é autorização nem credencial: contém somente a lista de arquivos e metadados indicados pelo usuário; no servidor, todos os IDs são revalidados contra os torrents concluídos em cada comando. A importação preserva inode por `os.link()`, concede somente ACL Jellyfin necessária, recusa caminhos simbólicos e nunca sobrescreve destino existente. Não há operação para apagar torrents, nem processo agendado ativado.

## Instalação e validação no Fedora — antes de qualquer APK

Usar worktree separado da main e dos PRs #34/#36/#37:

```bash
cd ~/Projetos/My-CT
git fetch origin
git worktree add --detach ../My-CT-media-batch origin/myct-media-manual-batch
cd ../My-CT-media-batch

python3 -m py_compile ops/myct-media ops/myct-media-bulk
python3 -m unittest discover -s ops/tests -p 'test_media*.py' -v

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain
```

Não prosseguir na ocorrência de erros. Se tudo estiver aprovado, instalar ambos os helpers **sem executar importações**:

```bash
cd ~/Projetos/My-CT-media-batch
install -Dm0700 ops/myct-media "$HOME/.local/bin/myct-media"
install -Dm0700 ops/myct-media-bulk "$HOME/.local/bin/myct-media-bulk"
myct-media-bulk sources | python3 -c 'import json,sys; print("Vídeos disponíveis:",len(json.load(sys.stdin)["files"]))'
```

APK candidato em `android/app/build/outputs/apk/debug/app-debug.apk`, `applicationId=dev.myct`, `versionCode=34`, `versionName=0.7.5-media-batch-rc1`. Confirmar certificado atual (digest SHA-256 `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`) e `aapt dump badging` antes de **atualizar por cima**, sem apagar dados. Não publicar nem instalar se assinatura/versionCode divergirem.

## Aceite físico

1. Interface Android carrega pastas do torrent e permite marcar/desmarcar arquivo e pasta.
2. Selecionar 2 episódios de um anime de teste, escolher temporada correta, verificar prévia antes de importar.
3. Testar colisão num destino ocupado (Frieren E01): prévia mostra `collision`, confirmação bloqueada.
4. Selecionar 1 episódio ainda não importado, confirmar e conferir inode duplicado, permissões Jellyfin, reconhecimento/reprodução e seeding no Transmission.
5. Testar importação múltipla de poucos episódios novos. Não importar de uma vez o pacote completo sem conferir numeração/seleção.
6. Para séries e filmes, primeiro criar/verificar as bibliotecas Jellyfin correspondentes.
7. Conferir remoção de um link pelo app sem tocar no original.
8. Verificar retorno de tela, reconexão SSH, assinatura e versionCode.

Sem ativar timer/systemd ou fundir PR antes do aceite de ponta a ponta.
