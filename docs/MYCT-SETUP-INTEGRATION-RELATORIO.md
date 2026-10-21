# MYCT-SETUP-INTEGRATION — consolidação na main

21/10/2026. Consolidação técnica executada sob autorização do proprietário,
condicionada ao ensaio integrado, após auditorias e aceite funcional do APK
code48 no Moto G06 informado nesta missão. Nenhuma instalação ou modificação
de serviços/dados pessoais. A [issue #53](https://github.com/Samuel-Francisco-AS/My-CT/issues/53)
continua aberta; encerramento definitivo depende de confirmação do proprietário.

## Referências, estado inicial e resultado

Checkout único `/home/sam/Projetos/My-CT`, inicialmente limpo na
`feat/myct-setup-4`, HEAD `e40e7241d39686efbea6de2f213d661cf5673d9b`.
Sem merge/rebase/cherry-pick incompleto. GitHub autenticado, refs atualizadas por
fetch e conferidas contra os SHAs autorizados. A `main` remota estava em
`fc69747ce8767592f02e8e291a5605ccb1b3434d`; a local, atrasada em
`cfb0cd33ea5946e77ee8b64f752ec411e1a54ea2`, foi preservada até a promoção remota
e então avançada por fast-forward. Não houve worktree, clone ou diretório paralelo.

| PR | Head original conferido | Base preservada | Estado real final no GitHub |
| --- | --- | --- | --- |
| [#54](https://github.com/Samuel-Francisco-AS/My-CT/pull/54) | `41770450a8a2043fd7c8a37cbe3ab4e7bfdd48c5` | main | MERGED, reconhecida por ancestralidade após #59 |
| [#55](https://github.com/Samuel-Francisco-AS/My-CT/pull/55) | `8a2212f8d59ed8db448c158648ac23faffc5d8d3` | main | MERGED, reconhecida por ancestralidade após #59 |
| [#56](https://github.com/Samuel-Francisco-AS/My-CT/pull/56) | `98de5fd86742b7f011f7ee666a4e693fb1ca2516` | feat/myct-setup-1 | CLOSED, substituída pela #59; mergedAt nulo |
| [#57](https://github.com/Samuel-Francisco-AS/My-CT/pull/57) | `a86a912e547e512cfd3ab98510fa87aca4b5497e` | feat/myct-setup-2 | CLOSED, substituída pela #59; mergedAt nulo |
| [#58](https://github.com/Samuel-Francisco-AS/My-CT/pull/58) | `e40e7241d39686efbea6de2f213d661cf5673d9b` | feat/myct-setup-3 | CLOSED, substituída pela #59; mergedAt nulo |
| [#59](https://github.com/Samuel-Francisco-AS/My-CT/pull/59) | `567d51409b10f2ca01c0f822942efd8c0baaf21e` | main | MERGED; PR única da consolidação |

Todos os heads originais são ancestrais da `main` resultante. Nenhum commit
ancestral perdido, squash, rebase, force push ou exclusão de branch remota.
Encerrar #56–#58 como substituídas não equivale a declarar merge individual;
isso foi explicitado nos comentários dessas PRs. O GitHub registra #54/#55/#59
como MERGED em `2026-10-21T04:08:29Z`.

**Marco efetivo da integração:** `567d51409b10f2ca01c0f822942efd8c0baaf21e`.
**Árvore promovida e conferida:** `fcb97410dfb38ebcbf385cbac866233f5cf8db73`.
O registro pós-integração acrescenta apenas este relatório e atualizações de
README/plano/retomada, sem mudar o código validado. Seu SHA de publicação é
registrado no comentário final da issue #53 e da PR #59 após a escrita do commit.

## Ensaio e promoção atômica

Branch `integration/myct-setup-1-4` criada de `origin/main` no checkout original.
Merges `--no-ff`, em ordem, preservando pais e commits de cada etapa:

| Merge | Commit do ensaio |
| --- | --- |
| #54 | `840085e9f18ff3a638ddeb09d5a6b4e5e7be1dac` |
| #55 | `11f13ba020bcd962b0cc353184c9f51adae9dd6b` |
| #56 | `6d9f037d58b5495bd9adb086a58f85caeeec9554` |
| #57, com resolução documental | `259df1fe0ed7110aef11f276835c28079b3d7184` |
| #58, incluindo AUDIT-FIX-1 | `19fcb65141a042888727ccb0e54557e252056fff` |

O ensaio em `19fcb651` difere da candidata corrigida `e40e7241` exclusivamente
pela ADR-019 e pelo arquivo do plano; Android/ops/server são byte a byte iguais
no Git. Depois foram acrescentados somente a evidência de diff e ajustes de
formatação documental. Nenhuma versão, configuração, migration ou contrato de
execução foi alterado pela integração.

A PR #59 reúne todos esses históricos. A consulta do GitHub mostrou `main`
sem proteção/rulesets ativos e nenhuma checagem CI configurada na PR; isso é
**ausência de CI, não PASS de CI**. Os testes locais abaixo foram obrigatórios.
PR pronta, MERGEABLE/CLEAN, base e todos os heads reconferidos antes da promoção.

Para publicar exatamente a árvore testada, foi usada a mutação GitHub `updateRefs`
com `beforeOid=fc69747ce8767592f02e8e291a5605ccb1b3434d`,
`afterOid=567d51409b10f2ca01c0f822942efd8c0baaf21e` e `force=false`.
É avanço fast-forward condicionado ao valor anterior: mudança concorrente de SHA
faz a operação falhar, sem sobrescrever trabalho novo. Não foi usado bypass de
proteção. Todos os commits de merge já faziam parte do head. A promoção foi única,
sem publicar etapas antigas separadamente ou gerar código diferente do ensaio.

## Resolução ADR-019 e diff

Único conflito no merge #57: final de `docs/05-DECISOES-E-QUESTOES.md`.
A ADR-019 vinda de #54 e a decisão SETUP-3 foram mantidas integralmente, nessa
ordem cronológica; removidas somente `<<<<<<< HEAD`, `=======` e
`>>>>>>> origin/feat/myct-setup-3`. O merge #58 acrescentou SETUP-4 sem novo conflito.
Conferência programática confirmou que os blocos originais completos da ADR e
das decisões SETUP-3/4 constam na versão final, com títulos/links inalterados.
Não houve resolução ours/theirs nem conflito de código/serviço/Gradle/migration.

[Patch exato contra a candidata e40e7241](evidence/myct-setup-integration-adr.patch),
com contexto zero: acrescenta a linha ADR-019 na tabela e nove linhas de texto
antes da decisão SETUP-3. Arquivo registrado no Git; log bruto e versão com
marcadores estão apenas em artifacts/. O plano conserva o conteúdo histórico,
com metadados separados por linhas vazias em vez de espaços finais.

Diff consolidado do marco contra a main original: **51 arquivos, +6192/-14**.
O registro posterior altera somente documentação. Inspeção reproduzível:

```bash
git diff --stat fc69747ce8767592f02e8e291a5605ccb1b3434d 567d51409b10f2ca01c0f822942efd8c0baaf21e
git diff --exit-code e40e7241d39686efbea6de2f213d661cf5673d9b main -- android ops server
git log --graph --oneline fc69747ce8767592f02e8e291a5605ccb1b3434d..main
git diff --check fc69747ce8767592f02e8e291a5605ccb1b3434d main
```

## Testes desta integração

Logs completos e resultados JSON em `artifacts/setup-integration/`, ignorados.
Relatórios privados anteriores não foram publicados/sobrescritos. Fedora 44
x86_64 rootless, 1536 MiB/1 CPU; ambiente operacional com rede desativada e sem
volumes pessoais. Fixtures PostgreSQL em dois HOME/clusters do contêiner,
sem conexão ao banco pessoal. Contêineres criados para os testes foram removidos.

| Verificação executada | Resultado |
| --- | --- |
| Python SETUP completo, host, árvore integrada | PASS: 57, 0 falhas |
| Python SETUP completo, Fedora descartável offline | PASS: 57, 0 falhas |
| Operacional ops/tests, Fedora descartável offline | PASS: 204, 0 falhas; mutações restritas à fixture |
| DNF efetivo e RPM negativos | PASS: repo configurado gpgcheck=0 passa a pacote efetivo 1, metadados 0/1 preservados; RPM sem assinatura/chave confiável rejeitados, nada instalado |
| Interrupção, retomada, ordens opcionais, conflitos, legado, aprovação | PASS nas regressões SETUP; ordens usam contratos simulados com publicação real de arquivos, não são instalação física |
| PostgreSQL/age reais | PASS: PG18.6/age1.3.1, dump, pacote privado, restauração limitada, reexecução, preservação e conflitos/versões/corrupção bloqueados |
| Android JVM | PASS: 316 aprovados, 2 ignorados (318 casos, 0 falhas/erros) |
| assembleDebug/lintDebug offline --rerun-tasks | PASS: 102/102 tarefas executadas, 3m16s; lint 0 erros, 1 warning UseKtx e 2 hints anteriores |
| Manifesto/bundle/assets e certificado | PASS: release myct-setup-4-fix1-rc1 e hashes coerentes; todas as entradas descompactadas do APK iguais ao code48 aceito |
| git diff --check consolidado | PASS final |
| Pós-promoção na main efetiva | PASS: árvore exatamente igual à candidata promovida; Python 57 reexecutados; ancestralidade, código e integridade reconferidos |
| CI GitHub | NÃO CONFIGURADA, sem checks reportados; nenhum PASS inferido |
| Moto G06 | Aceite funcional code48 informado pelo proprietário antes desta missão; nenhum teste físico novo pelo agente |
| Provisionamento completo Fedora físico limpo | **NÃO VALIDADO / NÃO EXECUTADO**, por decisão do proprietário |

Comandos: `python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v`;
`python3 ops/setup/tests/validate_audit_fix_container.py artifacts/setup-integration`;
`python3 ops/setup/tests/validate_recovery_container.py artifacts/setup-integration/recovery-fedora.json`;
em android/, `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --rerun-tasks`.
RPMs sintéticos previamente verificados foram copiados de artifacts/ da rodada
corretiva para a fixture; nenhum download, instalação de pacote ou importação de
chave no host. Não executar o script interno de recuperação diretamente no host.

O primeiro `diff --check` do arquivo .patch apontou espaços de contexto próprios
de um diff unificado; preservou-se a evidência exata usando contexto zero. A
verificação consolidada também revelou três hard breaks Markdown com espaços
finais já trazidos de #54: substituídos por separação de parágrafos, sem perda de
texto. Falhas intermediárias registradas; resultado final sem warnings de espaço.
O Gradle avisou que o daemon expiraria por Metaspace ao final; o build concluiu
com sucesso, sem alterar limites/configurações para mascarar o aviso.

Bundle SHA-256: `2ea25903bfa303c12cc6339a2fd0d4d3ffbd30b292ee83a28a566fcb316c2960`.
Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
APK reconstruído SHA-256: `2ae483240135a4f48cf19213ea9bd2f72f475f3986b3315918683733520a841d`;
o ZIP/empacotamento difere, mas todas as entradas descompactadas conferem.
APK publicado/aceito permanece `d93e429333f0bbf70842a4b623a7b7940d9f66ded42d0169d9b67362358fa319`.
Nenhuma release/versão/assinatura alterada e nenhuma instalação Android realizada.
Snapshot final da fixture PG: 102,2 MB, não é medição de pico.

## Operação, riscos e retomada

Mudanças permaneceram no Git/builds: não houve deploy de helpers, pacotes, serviços,
SQL/backup/restauração pessoal, OAuth, transferência/consulta autenticada Drive,
torrent real, modificação de catálogos, mídia, firewall, SSHD, SELinux ou reboot.
Os helpers antigos instalados continuam protegidos por intervenção manual.
Arquivos ignorados e artifacts/ preservados, sem git clean ou limpeza em massa.

Limites continuam explícitos: restauração PostgreSQL apenas dos eventos tipados
da migração 001 em Base gerenciada vazia/igual; nenhuma execução de SQL arbitrário
recebido. Catálogos/receitas são históricos, sem ativação automática. Mídia e
objetos Drive não respaldados/verificados não se tornam recuperáveis por constar
metadado. OAuth é humano, separado de diagnóstico passivo. Checksums não autenticam
a origem; age protege conteúdo privado, sem provar seu autor.

Arquivos definitivos antigos divergentes, initdb interrompido, migrações futuras e
destinos preexistentes conflitantes exigem intervenção. Não apagar recursos para
forçar sucesso. Os testes de contêiner não comprovam SELinux/PAM/boot físico nem
suporte a todos os filesystems/arquiteturas. Não confundir aceite funcional Moto
com prova de provisionamento físico limpo ou restauração de acervo pessoal.

Para retomar: usar main, conferir estado/ref remota e relatório; desenvolvimento
não atualiza automaticamente executáveis instalados. Em caso de regressão Git,
abrir correção/revert revisado com novos commits, preservando o histórico; não
reset/force push, reinstalação ou rollback de dados pessoal automático. Para
recuperação operacional, pacote confiável, diagnóstico, prévia e aprovação no
host apropriado, com conflitos resolvidos manualmente. Sem backup real autorizado
nesta missão, não há novo pacote pessoal de recuperação.

Pendências posteriores não iniciadas: provisionamento físico limpo, aplicação de
receitas/catálogos, recuperação de mídia efetivamente respaldada, futuras migrações,
dívidas UX #9, automações #4, licenciamento principal a decidir pelo proprietário.
Preservados avisos de terceiros; nenhuma licença arbitrária adicionada.
Próximo passo administrativo: confirmação expressa do proprietário antes de
considerar a trilha definitivamente encerrada. Issue #53 permanece aberta.
