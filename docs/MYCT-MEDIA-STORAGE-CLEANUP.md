# MYCT Media — Gerenciar armazenamento

Microetapa candidata: 0.7.8-media-storage-rc1 / versionCode 37.

Estado: código em branch isolada, ainda não validado no Fedora nem no Moto G06. PR draft. Não foi executada nenhuma exclusão nem alterado qualquer torrent real.

## Objetivo e ações separadas

Nova aba Downloads → Mídia → Armazenamento. Consultar arquivos existentes em Media/Series (Animes), Media/Series-TV (Séries), Media/Filmes (Filmes). Obras e pastas recolhidas por padrão. Seleção por arquivo, pasta de temporada ou título completo, até 256 itens.

**Ação 1: Retirar links**. Excluir somente hardlinks verificáveis da biblioteca. Os arquivos originais e o seeding no Transmission permanecem intactos. Normalmente não recupera espaço físico. Arquivos não registrados anteriormente no My-CT só são elegíveis se o inode coincidir com um arquivo de torrent concluído administrado pelo My-CT.

**Ação 2: Liberar espaço**. A remoção permanente funciona no primeiro MVP em **granularidade de torrent inteiro**. O usuário pode selecionar vídeos individuais, mas se eles pertencem a um torrent com outros links de biblioteca não selecionados, a prévia é **bloqueada**; precisa selecionar todos os episódios ligados ao mesmo torrent, mesmo que estejam em pastas diferentes. A remoção do conteúdo original é solicitada ao serviço Transmission pelo RPC torrent_remove(delete_local_data=true), o que encerra o seeding do torrent. Somente depois de aceito esse RPC, a CLI elimina os hardlinks correspondentes registrados na seleção. Não há unlink de arquivo original individual enquanto o torrent está ativo, nem deleção via shell/rm -rf.

**Atenção:** um torrent pode conter propagandas, legendas ou outros arquivos não mostrados na biblioteca. O modo liberar espaço apaga o **conteúdo inteiro** do torrent, incluindo os arquivos adicionais. A prévia informa nome do torrent e quantidade/amostras de vídeos não vinculados à biblioteca. O volume selecionado não é estimativa garantida de espaço físico recuperado. Hardlinks extras fora das bibliotecas reconhecidas bloqueiam a operação se detectáveis por nlink. Não é possível estimar todos os arquivos adicionais por esse MVP.

Ambos os modos exigem consulta, seleção, prévia real, confirmação adicional explícita e verificação de fingerprint do estado no momento da aplicação. Não há desfazer em exclusão definitiva. Falhas após remoção RPC podem deixar links remanescentes, que devem ser reavaliados manualmente; nunca repetir a chamada em caso de timeout sem checar o estado do Transmission. O estado registra cada link removido, preserva listas ignored para não reimportar e tenta retirar diretórios vazios sem apagar outros arquivos.

Nenhuma operação é feita ao consultar, marcar arquivos ou gerar prévia.

## Componentes

- ops/myct-media-cleanup — CLI Python stdlib com comandos list, preview TOKEN, apply TOKEN FINGERPRINT. Reutiliza as políticas e locks do myct-media. Chama Transmission RPC exclusivamente na exclusão definitiva.
- ops/tests/test_media_cleanup.py — unidade local com diretórios temporários e RPC falso, incluindo remoção só de link, bloqueio parcial, exclusão total, fingerprint inválido, hardlink externo e caminhos inválidos.
- Media.kt — contrato e parsing tipados + token Base64URL.
- MediaModel.kt — SSH existente e reconsulta posterior, com stale em respostas ambíguas.
- MediaStorageScreen.kt — seleção em pastas recolhíveis, ações distintas e confirmação.
- MediaScreen.kt — terceira aba Armazenamento.
- MediaCleanupTest.kt — testes JVM de payload/validação.

## Validação — fases

Antes de qualquer tentativa de instalação:

    cd ~/Projetos/My-CT
    git fetch origin
    git worktree add --detach ../My-CT-media-storage origin/myct-media-storage-cleanup
    cd ../My-CT-media-storage

    python3 -m py_compile ops/myct-media-cleanup
    python3 -m unittest discover -s ops/tests -p 'test_media*.py' -v

    cd android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain

Se PASS, conferir versão 37, certificado e SHA-256 antes de atualizar APK sem desinstalar ou limpar dados. Instalar somente novo helper com install -Dm0700 ops/myct-media-cleanup ~/.local/bin/myct-media-cleanup, sem ativar timer.

Validação segura inicial no telefone, SEM executar nenhuma exclusão:

1. Aba Armazenamento → Consultar arquivos, localizar Frieren (28), FMA (64), Tulsa King (10).
2. Abrir/recolher uma pasta, marcar 1 arquivo, gerar prévia apenas de hardlinks; confirmar que mostra preservação do original.
3. Gerar prévia Liberar espaço desse arquivo isolado de um torrent multi-episódio; deve bloquear por outros hardlinks.
4. Selecionar toda a pasta/obra, gerar prévia da exclusão definitiva, confirmar que exibe todos os torrents afetados e vídeos adicionais; **não tocar em confirmar**.
5. Conferir proteção de arquivos não comprovados e estado de falha/consulta.
6. Verificar que nenhum dado, arquivo ou status Transmission mudou.

Operação real de retirada de hardlinks e operação destrutiva sobre originais ficam **expressamente condicionadas à aprovação do usuário após os testes não destrutivos**. Não usar o primeiro teste físico como oportunidade de testar exclusão real sem consentimento específico.

Sem merge ou release automático. PR permanecerá em draft até validação e autorização.
