# MYCT-SETUP-AUDIT-FIX-1 — candidata corretiva

21/10/2026. Referência auditada: `96312faf06d38bf50fc45e6991de92e0d217c4af`.
Correções aditivas na mesma `feat/myct-setup-4`, sem rebase, worktree, clone,
merge ou alteração das bases #54–#58. A [PR #58](https://github.com/Samuel-Francisco-AS/My-CT/pull/58)
permanece draft sobre `feat/myct-setup-3`, dependente das #54/#55/#56/#57.
References #53, sem fechamento. Este relatório não aprova globalmente a trilha.

Commit corretivo de código/testes: **`5bdb3886f1b388329c12bba4b904d2d5df91cc4b`**,
descendente direto da referência congelada; 18 arquivos, +635/-129 linhas.
O commit seguinte acrescenta apenas README e este relatório. A release aponta
para a revisão final de documentação, com o mesmo código/APK validado. Para
confronto Luna, comparar o congelado com o SHA final publicado na PR/release,
preservando também o commit corretivo acima como referência executável.

O relatório independente original foi lido integralmente e preservado em
`artifacts/auditoria-codex-independente.md`, fora do Git. SHA-256 original:
`cf5879fa7179d560b68021ba8424520a3b64714d1a6d032a95d2e0e3c0d11e14`.
Logs e reproduções locais permanecem em `artifacts/setup-audit-fix1/`, ignorados.
Nenhum dado pessoal, identidade age, configuração OAuth, backup ou chave privada
de assinatura foi incluído nesta entrega. Os exemplos usam somente fixtures.

## Correções e resultados

| ID / severidade original | Reprodução e causa | Arquivos principais | Correção / teste antes e depois | Resultado e risco residual |
| --- | --- | --- | --- | --- |
| FIX-01 / A-01 — alta | Confirmada em DNF5 Fedora 44: repo com `gpgcheck=0` mantém `pkg_gpgcheck=0` mesmo com a antiga opção global. | `ops/setup/setup.py`, `addons.py`, `cloud.py`; `test_audit_fixes.py`, `validate_audit_fix_container.py` | Política única por invocação: `gpgcheck=1`, `*.gpgcheck=1`, `localpkg_gpgcheck=1`. Antes: configuração efetiva 0. Depois: 1; RPM sem assinatura e RPM assinado por chave não confiável rejeitados, nada instalado. | PASS no contêiner offline. `repo_gpgcheck` 0/1 permanece como configurado; nenhuma importação arbitrária de chave ou edição permanente. Confiança ainda depende das chaves/repositórios administrados pelo proprietário. |
| FIX-02 / A-02 — média | Confirmada: publicação por hardlink seguida de morte antes da limpeza deixava dois links e o leitor recusava o próprio arquivo. | `setup.py:atomic_user_write/rename_noreplace`, `recovery.py:rec_publish/rec_bytes`; testes de interrupção | Temporário privado, escrita completa, hash/releitura e fsync antes de `renameat2(RENAME_NOREPLACE)`; fsync do diretório. Morte antes/depois da publicação, depois do fsync e na limpeza. Antes: nlink 2 e leitura recusada. Depois: destino ausente retomável ou íntegro com nlink 1 e pacote legível. | PASS. Sem sobrescrita ou relaxamento da guarda de hardlinks. Temporários órfãos podem permanecer privados; não são tratados como instalação concluída. Filesystems sem essa operação falham de forma fechada. |
| FIX-03 / A-03 — média | Confirmada: seleção Rclone ativa era publicada sem valores na prévia. | `recovery.py:rec_restore_plan`, `ServerSetup.kt`, `BackupSetupScreen.kt`; testes Python/JVM | Prévia estruturada contém remote/config de origem, configuração ativa anterior, remote/config resultantes e estado apply/existing/conflict/historical. Esses fatos entram no hash aprovado e são recalculados sob lock. Antes: remote oculto. Depois: valores visíveis; seleção alterada depois da inspeção invalida aprovação. | PASS em contratos Python/JVM; renderização no Moto pendente. Só seleção não secreta, nunca conteúdo de rclone.conf/OAuth. Receita histórica explicitamente não será ativada. |
| FIX-04 / A-04 — média | Confirmada: helper legítimo de nuvem confundido com daemon Transmission legado. | `addons.py:known_helper/addon_diagnose`; `test_audit_fixes.py` | Helper auxiliar com bytes/SHA do manifesto, arquivo seguro e permissão executável pode existir sozinho. Não concede propriedade sobre serviço/config/processo/API. Antes: Rclone→Transmission bloqueado. Depois: duas ordens Transmission/Rclone aplicadas e repetidas em fixture; Rclone→Prowlarr permite plano. Helper divergente e configuração legada continuam bloqueados. | PASS em simulação contratual com publicação real de arquivos; instalação completa desses componentes no Fedora descartável nesta rodada não executada. Não há adoção de legado. |
| FIX-05 / A-05 — média | Confirmada: ancestral 0777 e lock simbólico aceitos na SETUP-2; proteções divergentes. | `setup.py` guardas/locks/escritas, `addons.py`, `cloud.py`, `recovery.py`; testes negativos | Guardas compartilhadas de HOME, uid, tipo, ausência de escrita por grupo/outros e nlink; caminhada por descritores com O_NOFOLLOW/O_DIRECTORY, fstat e comparação do diretório na publicação. Lock comum também não segue links. Antes: planos aplicáveis. Depois: bloqueio antes de sudo; symlink/hardlink/FIFO/conflitos e corrida de ancestral recusados. | PASS. Não chmod/corrige diretórios existentes. Proteção contra outros usuários; não pretende isolar um atacante já executando com o mesmo uid. Diretórios ancestrais normais 0755 são aceitos, nunca 0777/0775. |
| FIX-06 / A-06 — baixa | Confirmada por revisão dos campos Compose: ausência de limpeza ON_STOP. | `SetupCredential.kt`, modelos/telas ServerSetup e BackupSetup; `SetupCredentialTest.kt` | Estado efêmero compartilhado pela tela/modelo limpa campo e confirmação em ON_STOP/ON_DESTROY, descarte e qualquer troca de conexão; envio/cancelamento/dismiss também limpam. LifecycleRegistry real STOP→START e trocas/limpeza repetida não recuperam texto. | Mitigação PASS JVM; aparelho/captura/IME pendentes. FLAG_SECURE preservado. String JVM não permite garantir apagamento físico de memória; remoção de referências não é limpeza criptográfica. Sem saved state/preferências/logs ou repetição automática. |
| FIX-07 — média confirmada | Reprodução própria do risco indicado pela Luna, distinta de A-02: SIGXFSZ abrupto em escrita de 130000 bytes deixava nome definitivo com 4096 bytes, tanto SETUP-2 quanto SETUP-3; retry bloqueado. | `addons.py:publish/component`, `cloud.py` por herança, publicação compartilhada em `setup.py`; testes | Helper/config/unidade agora chega ao nome definitivo somente após escrita completa e verificação; registro de integridade publicado atomicamente após o artefato. Interrupções nos cinco pontos, retry e igualdade/mtime confirmados. Prowlarr usa staging e rename sem substituição para árvore oficial. | PASS; não era apenas potencial. Arquivo definitivo divergente produzido por versão antiga continua exigindo intervenção: sua origem não é demonstrável com segurança. Não é apagado/sobrescrito automaticamente. |

Não foi criado framework de execução, daemon, API administrativa, mecanismo OAuth,
catálogo ou motor de transferência. Política e publicação comuns são pequenas
funções no módulo já concatenado pelo bundle. Helpers operacionais e seus
contratos não mudaram. O manifesto identifica `myct-setup-4-fix1-rc1`; o leitor
aceita também os releases schema1 anteriores, preservando a migração 001.

## Publicação, retomada e aprovação

O nome definitivo nunca contém escrita parcial nova. Staging aleatório é criado
exclusivamente no diretório de destino, com O_NOFOLLOW, modo privado, bytes
conferidos e fsync. A publicação Linux é uma única renomeação sem substituição.
Morrer depois da renomeação pode deixar a operação sem resposta de sucesso;
o diagnóstico seguinte deve conferir bytes e estado real. Não se presume sucesso
porque o processo morreu. Arquivos iguais são preservados; divergentes bloqueiam.
Recibos próprios mutáveis usam substituição atômica; configurações e backups não.

Arquivos antigos com dois hardlinks, definitivos truncados ou staging privilegiado
divergente não são automaticamente reparados: sem comprovação de origem seria
inseguro abrir exceção. Permanecem para revisão humana. Temporários novos órfãos
não impedem retry e não são removidos em massa. Não há `rm -rf`/`git clean`.

A prévia Rclone distingue a seleção ativa de uma receita apenas histórica.
Remote e caminho anterior/remapeado aparecem antes da aprovação. Configuração
existente divergente bloqueia; mudanças entre inspeção, aprovação e lock invalidam
o plano. Aplicar a seleção não cria/configura/autentica conta nem transfere dados.

## Validação executada

Ambientes: host apenas compilação/testes com HOMEs sintéticos; Fedora 44 x86_64
rootless em contêiner descartável, sem volumes pessoais, limitado a 1536 MiB e
1 CPU. O teste GPG/regressão usa rede desativada. Nenhum pacote foi instalado ou
atualizado no host. Serviços e bancos alterados pertencem exclusivamente às
fixtures dentro dos contêineres, removidos ao terminar.

| Verificação | Resultado real |
| --- | --- |
| Python SETUP-1/2/3/4 completo, host | PASS: 57 testes, incluindo nove regressões corretivas |
| Python SETUP completo, Fedora rootless offline | PASS: 57 testes |
| Operacional `ops/tests`, Fedora rootless offline | PASS: 204 testes; helpers reais, fixtures e systemd de usuário isolado |
| DNF5 efetivo, repo `gpgcheck=0` e metadados 0/1 | PASS: pacote efetivo 1, metadados inalterados e hash do .repo preservado |
| RPM sem assinatura / chave não confiável | PASS: ambos exit 1 por assinatura, pacote ausente após tentativa; nenhuma chave importada |
| Reproduções originais A-02/03/04/05 | PASS após correção; mantidas em `audit-reproduction-after.json`; A-01 em `gpg-fedora.json` |
| Interrupções abruptas e retomada | PASS: fork/os._exit e SIGXFSZ real; antes/depois de publicação, fsync e limpeza |
| Ordens e reexecução | PASS contratual: Transmission→Rclone, Rclone→Transmission; Rclone→Prowlarr permite plano; base representada por fixture/sentinela, RPM/systemd/API simulados neste teste |
| PostgreSQL/age reais em Fedora descartável | PASS: PG 18.6, age 1.3.1, dois HOME/clusters, dump lógico, exportação criptografada, restauração de eventos conhecidos, repetição sem alterações e conflitos bloqueados |
| Preservação e diagnóstico passivo | PASS nas fixtures: credenciais/configuração e unidade existente preservadas; inspeção não insere dados/não inicia serviço |
| JVM Android | PASS: 316 aprovados, 2 SKIP (318 casos); LifecycleRegistry e prévia Rclone incluídos |
| Android assembleDebug / lintDebug offline | PASS; execução final 1m45s, 102 tarefas (13 executadas/89 atualizadas), 0 erros lint, 1 warning UseKtx e 2 hints anteriores |
| `git diff --check` | PASS |
| Moto G06 desta candidata / captura / IME | NÃO EXECUTADO; aceites anteriores não substituem este teste |
| Fedora físico limpo, SELinux/PAM/boot físicos | NÃO EXECUTADO, por decisão expressa; contêiner não substitui validação física |
| Instalação completa nova Base/Transmission/Prowlarr/Rclone nesta rodada | NÃO EXECUTADA; assinatura, publicação e contratos foram testados separadamente |
| Google Drive / banco / torrents pessoais | NÃO ACESSADOS; nenhuma transferência, backup/restauração ou mudança de serviços pessoais |

Evidência PostgreSQL final: payload SHA-256
`2ea25903bfa303c12cc6339a2fd0d4d3ffbd30b292ee83a28a566fcb316c2960`;
memória do contêiner ao final 98,44 MB (snapshot, não pico), limite 1536 MiB.
A fixture conserva unidade preexistente inativa/static, credenciais de origem
intactas, catálogos históricos e ausência de OAuth no destino. Rejeita falta de
identidade, corrupção, major incompatível e marcador de migração futura.

Os RPMs sintéticos são fixtures oficiais de `rpm-software-management/rpm`, commit
`184507c8f690085c5917347a2cfd9722bcd8ee94`, `tests/data/RPMS/hello-2.0-1.x86_64*.rpm`.
Os bytes foram conferidos pelo Git blob e SHA-256 antes do teste. O validator fixa
esses hashes; binários/fixtures não entram no Git/release. Não houve instalação
nem importação da chave das fixtures, somente tentativas que devem ser rejeitadas.

Falhas intermediárias registradas, sem esconder resultados: a primeira regressão
Python falhou em quatro fixtures que ainda não fixavam HOME ou injetavam falha em
os.link, operação removida pela correção. As fixtures foram adaptadas à guarda de
HOME e à nova fronteira de publicação, mantendo a exigência de falha/sem destino.
Uma execução temporária contou testes duplicados por importação de TestCase;
corrigida a descoberta, o total final é 57. A primeira execução da suíte no novo
contêiner teve 57 erros por ausência de `/tmp/server/main.cpp`: o transporte da
fixture copiava somente ops/. Passou após copiar também server/, sem mudança para
ocultar falha de produto. Logs anteriores preservados com resultados finais.

Comandos reproduzíveis (não rodar validadores internos diretamente no host):

```bash
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
# Requer imagens de testes já preparadas e fixtures RPM verificadas em artifacts/:
python3 ops/setup/tests/validate_audit_fix_container.py artifacts/setup-audit-fix1
python3 ops/setup/tests/validate_recovery_container.py artifacts/setup-audit-fix1/recovery-fedora-final.json
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
cd ..
git diff --check
```

## Conflito documental e futura integração

Reprodução somente de leitura, sem mover main ou criar branches:

```bash
git merge-tree fc69747ce8767592f02e8e291a5605ccb1b3434d origin/docs/myct-setup-roadmap 96312faf06d38bf50fc45e6991de92e0d217c4af
```

O conflito em `docs/05-DECISOES-E-QUESTOES.md` continua reproduzido: #54 acrescenta
ADR-019 no mesmo final onde a cadeia acrescenta as decisões SETUP-3 e SETUP-4.
Resolução proposta para revisão posterior: conservar o texto completo ADR-019,
seguido integralmente das decisões SETUP-3 e SETUP-4, removendo só os marcadores
de conflito. Não escolher arquivo inteiro com ours/theirs. Esta missão não
altera esse documento nem a PR #54 para disfarçar o conflito.

Ordem recomendada permanece #54 → #55 → #56 → #57 → #58, somente após revisão
independente/aceite e planejamento autorizado. Usar merges que preservem os
commits ancestrais da cadeia; não squash/rebase de cada nível, que reapresentaria
commits nas PRs descendentes. Após cada merge, conferir diff e base do próximo
sob autorização própria. As correções estão no topo #58: **não implantar versões
intermediárias antigas** como se contivessem estes reparos. Não foram executados
merges, retargets, fechamentos ou publicação dos relatórios privados de auditoria.

## Candidata e roteiro Moto G06

APK `dev.myct`, versionCode **48**, versionName **0.13.1-setup-audit-fix1-rc1**.
Atualização sobre o APK anterior, sem desinstalação/limpeza de dados. Publicação
somente após build, JVM/lint e conferência do certificado contra code47.

Certificado SHA-256 verificado por `apksigner verify --print-certs`, idêntico ao
APK code47: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
APK com 18.390.669 bytes; SHA-256:
`d93e429333f0bbf70842a4b623a7b7940d9f66ded42d0169d9b67362358fa319`.
[Release corretiva candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-setup-audit-fix1-v0.13.1-rc1),
contendo somente APK e arquivo SHA-256; não contém logs ou backups.

1. Instalar a candidata como atualização; conferir pacote/versão e dados existentes.
2. Conectar por SSH e abrir Opções → Configurar servidor. Diagnosticar os alvos
   passivamente; legados devem continuar protegidos. Não aprovar instalações,
   verificação online de Drive, backup ou restauração no Fedora pessoal.
3. Digitar texto descartável no campo sudo/age, sem enviar; colocar app em segundo
   plano, retornar e conferir campo e confirmação vazios. Repetir após desconectar
   e reconectar SSH, cancelar/descartar diálogo e sair da tela. Confirmar proteção
   de captura da seção sem expor credenciais reais.
4. Para conferir a prévia Rclone, usar somente pacote sintético em Fedora
   descartável; verificar remote/caminhos origem/destino e indicação de aplicação
   ou receita histórica. Não usar backup/catalogação pessoal como fixture.
5. Conferir navegação, Terminal, SFTP e Saúde; observar regressões sem iniciar
   torrents, transferências, liberação de espaço ou consultas autenticadas Drive.

## Limites e pendências para Luna

Revisar especialmente as funções compartilhadas de caminhos/publicação, mudanças
de propriedade dos recibos e vínculo da seleção Rclone à aprovação. Conferir
incompatibilidades entre SETUPs usando os novos testes, sem tomar PASS de mock
como prova física. O mesmo uid controla seus próprios arquivos/processos; as
guardas não são uma sandbox contra comprometimento dessa conta.

SHA-256 detecta corrupção mas não autentica o autor do pacote. A reprodução de
pacote editado com hashes recalculados continua aceita como formato válido; o
usuário deve confiar na origem, ver agora os valores efetivos e aprovar. `age`
protege confidencialidade/integridade criptográfica do conteúdo, sem provar quem
o produziu. Não foi inventado protocolo de assinatura de origem nesta correção.

Recuperação permanece limitada: eventos tipados migração 001, seleção Rclone não
secreta e receitas/catálogos históricos. Ativação de catálogos, preferências
Transmission/Prowlarr, mídia/seeding/objetos Drive não são recuperação comprovada.
Initdb interrompido e arquivos definitivos divergentes exigem intervenção em vez
de reinicializar ou apagar. Serviços devem ser reconstruídos por planos SETUP,
nunca por importar unidades do backup. A candidata precisa de nova revisão Luna;
nenhuma aprovação global ou autorização de implantação/merge é inferida aqui.
