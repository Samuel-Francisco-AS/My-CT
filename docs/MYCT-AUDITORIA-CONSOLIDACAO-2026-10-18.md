# My-CT — Auditoria de consolidação de PRs (#34, #36–#42)

Data: 2026-10-18. Objetivo: tornar a `main` equivalente à candidata funcional instalada no Fedora/Moto G06, sem alterar serviços ou dados locais.

## Estratégia de integração

**Branch canônica:** `myct-download-cards-cleanup` (PR #42). Comparação antes da consolidação: `main` SHA `349c82a34c70d097ab4fdc3d1b7b6a452f068493`; a branch canônica estava 101 commits à frente e zero atrás, alterando 46 arquivos. A cadeia #36–#42 é em grande parte linear, exceto os seis commits tardios do #36, incluídos no resultado final em conteúdo. Comprovado: `ops/myct-media` e `ops/tests/test_media.py` têm o mesmo blob SHA no #36 e no #37 para os reconhecimentos de Frieren e Fullmetal; o resultado final mantém os mesmos testes e regras.

**Não realizar merge independente do PR #34 após incorporar a branch final.** Isso reintroduziria uma variante anterior de `ops/myct-torrent`. As implementações `magnet()` e `Rpc.call()` do #34, incluindo tolerância a trackers DNS falhos, `&&`, preservação de `xt=urn:btih:` e diagnósticos RPC, foram carregadas para a branch final nos commits `8c193a2` e `642b69c` juntamente com testes regressivos. A branch canônica também conserva a funcionalidade nova `delete-data`.

**PRs auditados:**
- #36 — backend Mídia / hardlinks + timer opcional; alterações somente em código/configuração versionada, não em serviço ativo.
- #37 — interface Android de Mídia, model, comandos SSH e testes.
- #38 — seleção e importação manual em lote (filmes, séries, animes), numeração e prévia.
- #39 — UX de pastas recolhíveis e seleção com scroll.
- #40 — inventário das bibliotecas Jellyfin, pastas/títulos e episódios.
- #41 — aba Armazenamento com retirada separada de hardlinks e prévia de exclusão definitiva; correção pontual de corrida em LocalTerminalModel.
- #42 — cards Torrents compactos, mais novos primeiro, opção explícita de excluir dados, histórico HTTP com exclusão por card, testes, diagnóstico de magnets.
- #34 — correções de magnets portadas para #42; PR histórico não deve ser reaplicado.

## Auditoria de escopo

Mudanças de `main` a `#42`: 46 arquivos: `android/`, `ops/`, `server/download.cpp`, `server/tests/`, `docs/`. Não houve alteração no core PostgreSQL, autenticação, banco ou no APK/package `dev.myct` além de versionamento; final `versionCode=38`, `versionName=0.7.9-download-cards-rc1`.

## Evidências verificadas

- Android `0.7.9` code 38: usuário executou compilação de testes JVM, assembleDebug e lint e confirmou `BUILD SUCCESSFUL` (após correção `DownloadsModelTest.kt`).
- SHA-256 do APK testado fisicamente `35b38142ccb4ed6b692a9221f90633dea3124ead27926cb9fd501293baea202b`, assinatura original `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`. O APK em execução **não muda** durante o merge.
- Inventário Media: 150 vídeos elegíveis; visualização e prévias (somente hardlinks e bloqueio de exclusão parcial) testadas no Moto. Remoção efetiva de arquivos originais por esta aba NÃO testada.
- Torrent: leitura de 29 torrents; UI compacta e ordem decrescente aceitas no Moto. Exclusão definitiva de um torrent incompleto (cobaia `Pai em Tempo Integral`, hash `e189919f44dbffff0661eb6076c9bbff42a39b7c`) confirmada por ausência no Transmission e diretório removido.
- HTTP/HTTPS: listagem de 4 históricos e testes C++ em HOME temporário aprovados; exclusão real pelo card HTTP ainda NÃO testada.
- Magnet: usuário confirmou sucesso ao reaplicar correção pontual no Fedora. Depois, o PR #42 incorporou a versão completa do #34; **a consolidação completa do Python ainda não foi submetida pelo usuário ao gate final**.
- GitHub: sem workflow CI/combined status registrados no SHA de auditoria. A auditoria de código não equivale a teste de execução em ambiente limpo.

## Gates pós-consolidação / pendências

1. No Fedora, atualizar a `main` com `git fetch origin && git switch main && git pull --ff-only origin main` após garantir worktree limpo; não apagar worktrees com alterações.
2. Revalidar `python3 -m py_compile ops/myct-torrent` e `python3 -m unittest discover -s ops/tests -p test_torrent.py -v`; conferir toda a suíte `test_media*.py` se alterar código posteriormente.
3. Reinstalar o helper `ops/myct-torrent` somente **após testes**; nunca sobrescrever helper local por branch mais antiga ou cherry-pick não integrado. Não é preciso reinstalar Android na consolidação.
4. Opcional: testar exclusão HTTP/HTTPS com arquivo descartável, previamente autorizado. Não executar exclusão real apenas para auditar merge.
5. Prévias da aba Armazenamento não provam exclusão definitiva nem funcionamento em todos os tipos de arquivos; não alegar aceitação ponta a ponta.

## Riscos e limites

A consolidação é exclusivamente remota no GitHub; não reinicia Transmission/Jellyfin, não deleta conteúdo dos torrents, não mexe no SSD, não modifica os dispositivos. O merge na main não instala executáveis no Fedora. PRs intermediários devem ser encerrados como incorporados/superados, sem reaplicar seu código em ordem que sobrescreva implementações mais recentes. Preservar branches até eventual limpeza consciente e garantir que o último commit de `main` inclua todos os arquivos canônicos.
