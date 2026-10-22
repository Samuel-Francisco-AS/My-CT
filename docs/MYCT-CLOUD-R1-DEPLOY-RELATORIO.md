# MYCT-CLOUD-R1 — publicação e implantação supervisionada

21/10/2026. Candidata publicada e helpers atualizados sob autorização expressa
do proprietário. **Ainda sem instalação/aceite físico R1 no Moto G06; sem merge.**
A R2 não foi iniciada.

## Origem imutável e autorização

- Código, APK e arquivos implantados: **`f239bee9a77042b0603d5a06f738bb723bb5e362`**.
- [PR #60](https://github.com/Samuel-Francisco-AS/My-CT/pull/60), branch
  `fix/myct-cloud-r1`, preservada em draft/open/sem merge.
- [Reauditoria independente da Luna](https://github.com/Samuel-Francisco-AS/My-CT/pull/60#issuecomment-6318687404):
  A01 resolvido na revisão estática, apto a candidata de teste físico, sem aprovação
  para merge. [Relatório técnico auditado](https://github.com/Samuel-Francisco-AS/My-CT/blob/f239bee9a77042b0603d5a06f738bb723bb5e362/docs/MYCT-CLOUD-R1-RELATORIO.md).
- Checkout inicialmente limpo, um único worktree, HEAD local/remoto/PR iguais ao
  aprovado após fetch. Nenhum avanço adicional antes do build/publicação. A main
  permaneceu em `54618d91182efbd81c5c1c3124ab8a418d89ebe7`.
- Este arquivo é registro **posterior à implantação**, em commit separado de
  documentação na mesma branch. Não muda o código/pacote aprovado nem o alvo da tag.
  Não foi usado reset destrutivo, force push, limpeza de artefatos ou reinstalação
  completa do SETUP.

A autorização inicial abrangia três helpers. O pré-voo identificou que o
`myct-archive` auditado também importa `myct_cloud_config.py`, ausente no Fedora.
Uma importação isolada falhou com `ModuleNotFoundError: myct_cloud_config`, sem
alterar a instalação. A implantação ficou bloqueada até a resposta expressa do
proprietário **“Autorizar o módulo adicional auditado”**, nesta sessão. Por isso
há **três substituições e uma inclusão adicional**, todas do mesmo commit
aprovado. Nenhum código foi adaptado para contornar essa dependência.

## APK e pre-release

[**Página oficial da candidata**](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-cloud-r1-v0.13.2-rc1)
· [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-cloud-r1-v0.13.2-rc1/myct-cloud-r1-0.13.2-rc1.apk)
· [Checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-cloud-r1-v0.13.2-rc1/myct-cloud-r1-0.13.2-rc1.apk.sha256).

| Campo | Verificado |
| --- | --- |
| Tag | `myct-cloud-r1-v0.13.2-rc1` |
| Commit da tag anotada, conferido também no remoto | `f239bee9a77042b0603d5a06f738bb723bb5e362` |
| Estado da release | Publicada, **pre-release**, não definitiva |
| Package / versão / code | `dev.myct` / `0.13.2-cloud-r1-rc1` / **49** |
| ABIs presentes no APK | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| APK | `myct-cloud-r1-0.13.2-rc1.apk`, **18.291.953 bytes** |
| SHA-256 real desta reconstrução | `ee3564ce5f8f72141ada12538cb6b4c104b7ab68160032e13488dd5abdf1330f` |
| Certificado SHA-256 público | `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3` |
| SETUP payload SHA-256 | `1d92c5291e504d2ca1c0f7e266170bb07ca85373be63d0074c4b3fe7fb21233f` |
| SETUP diagnóstico SHA-256 | `745819c977f21498bd5a057151fbe179836d66753b4d01bf8cda822197256a63` |

`apksigner verify --print-certs` aprovou o APK novo e o code 48 anterior
`0.13.1-setup-audit-fix1-rc1`; certificados iguais. `aapt dump badging` confirmou
`dev.myct` e os codes 48/49. A assinatura e o incremento de versão permitem
atualização sobre o code 48, sem desinstalar ou limpar dados; a instalação real
será confirmada pelo proprietário. Nenhuma keystore/material privado publicado.

Esta APK foi **reconstruída** do commit aprovado com tarefas reexecutadas. Seu
hash foi calculado de novo, sem reutilizar o hash da APK incremental anterior.
As três ABIs e todas as 16 fontes/hashes do payload SETUP embutido foram
conferidos. Payload e diagnóstico permanecem idênticos à candidata auditada.

A tag/release sugeridas não existiam no remoto; não existia tag local. Criada
tag anotada no SHA aprovado e enviada sem force. A release foi preparada como
draft/pre-release, com **somente APK e checksum**, conferida antes de publicar.
Os dois assets foram baixados novamente e comparados byte a byte às fontes locais;
o digest SHA-256 fornecido pelo GitHub também coincide. Publicação final mantém
`isDraft:false`, `isPrerelease:true` e `targetCommitish` exatamente no SHA aprovado.
Nenhum catálogo, backup, log privado, configuração ou dado pessoal é asset.

## Build e validação

Ferramentas existentes: Git 2.55.0, GitHub CLI 2.97.0, Python 3.14.7, Java
21.0.10, Gradle wrapper 8.14.3, Android SDK/build-tools 36; Fedora 44, usuário
`sam`/UID 1000. Nenhuma dependência instalada. Operação local no Fedora pessoal,
sem alterar SSH ou estabelecer outra arquitetura de acesso.

```bash
env -u MYCT_TEST_LOCAL_SSH -u MYCT_TEST_LOCAL_SFTP \
  ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  --offline --rerun-tasks --console=plain
```

| Verificação desta missão | PASS / FAIL / SKIP ou estado |
| --- | --- |
| JVM completo, 328 casos | **326 PASS / 0 FAIL / 2 SKIP** |
| `assembleDebug` | PASS, reconstrução sem reaproveitar tarefas |
| `lintDebug` | PASS, **0 erros**, 1 warning UseKtx e 2 hints anteriores |
| Identidade, três ABIs e certificado | PASS |
| Integridade do SETUP no APK | PASS, 16 fontes e hashes + allowlist exata |
| Integridade dos assets enviados/baixados | PASS, dois arquivos idênticos |
| Sintaxe dos quatro arquivos implantados | PASS, `compile()` em memória, sem pycache ativo |
| Importação/compatibilidade antes e depois | PASS, engines + bridge, sem dispatch |
| Validação de pedido `space` simulado com 13 caminhos | PASS, sem iniciar job |
| Parser dos catálogos e índice Media existentes | PASS, somente leitura; sem publicar conteúdo |
| Leitura/telemetria de fixture descartável | PASS, 26 bytes reais, métrica `read`, sem falso 100% global |
| Jobs/catálogos/biblioteca/serviços na janela de implantação | PASS, fotografias/estados iguais |
| Moto G06 / consulta real de ~18 GiB | **NÃO EXECUTADOS nesta missão** |

Os dois SKIP são os opcionais `FedoraHandshakeTest` e `IdeFedoraSftpTest` de
SSH/SFTP reais, explicitamente desabilitados; não contados como PASS. A compilação
e as provas instrumentadas anteriores são evidência de preparação, não execução
no Moto. Não houve comando ADB nesta missão, pois não havia identificação
inequívoca de um Moto G06 autorizado para instalação automática.

Build/test/lint: **3m12,29s**, pico RSS **2.714.608 KiB**, custo de desenvolvimento;
não é medição da consulta real nem do runtime dos helpers. Configurações de
memória/serviços não foram alteradas. Logs e resultados privados locais em
`artifacts/myct-cloud-r1-deploy-validation/`, ignorados pelo Git. A importação
negativa que identificou o módulo ausente foi um impedimento real, resolvido
pela autorização adicional antes de substituir qualquer helper.

## Pré-voo e proteção do Fedora

Os três arquivos anteriores eram regulares, sem symlinks/redirecionamentos,
UID 1000, nlink 1, modo 0700, e correspondiam exatamente à versão histórica
`3b3ca355ca131dd0947d1f25718d40a8af44fc23`. Não eram alterações locais
anônimas a sobrescrever automaticamente. Os outros quatro auxiliares
`myct-cloud`, `myct-torrent`, `myct-media` e `myct-media-cleanup` já coincidiam
com o commit auditado e permaneceram intactos.

Pré-voo: **14 recibos históricos `succeeded`, zero starting/running/cancelling/
uncertain/pending**, nenhuma unidade/job Cloud ativo ou processo de
archive/cloud/Rclone em execução. Catálogos sem estados pendentes/incertos.
Cerca de **47,2 GB livres**, frente a menos de 1 MiB de código/backup/staging.
Não houve cancelamento, interrupção ou início de tarefa para viabilizar a troca.

Durante a troca foram mantidos os locks **existentes** do bridge (`delivery`),
dos dois catálogos e do Media, sem criar/reinicializar catálogos. Checagens de
recibos, unidades, processos e estados foram repetidas imediatamente antes da
substituição e entre arquivos; qualquer estado não confirmável abortaria a
operação. As substituições ocorreram em menos de um segundo, sem necessidade
de reiniciar processos. O bridge/catálogos não receberam novos trabalhos.

## Backup e arquivos instalados

Backup privado novo e exclusivo, fora do Git e dos helpers ativos:

```text
~/.local/state/myct-helper-backups/cloud-r1-20261021T145519Z-83a7fe5b/
```

Diretórios 0700; cópias dos três originais com seus bytes, hashes, modos 0700 e
mtime preservados; manifestos `before.json`/`after.json` e checkpoints 0600.
O módulo adicional era ausente, fato registrado no manifesto, sem arquivo
preexistente a salvar. Backups anteriores não foram sobrescritos nem removidos.
O backup não autoriza rollback automático, sobretudo com tarefa ativa/incerta.

Destino real: **`/home/sam/.local/bin/`**, UID 1000 nos quatro arquivos, nlink 1,
sem symlinks, SHA-256 origem/destino iguais. Hashes abaixo são de código público,
não de catálogos pessoais:

| Arquivo | Modo antes → depois | SHA-256 antes | SHA-256 implantado |
| --- | --- | --- | --- |
| `myct-archive` | 0o700 → 0o700 | `8e08b1d1692f59cd4754ce6c0f9caacb215bc312db4bd54d80efa7c2e4fe7eba` | `e5bd0bbfc73ee0f585abf74fc14586d0c21cefb24aaac5e91332b1de6974baa0` |
| `myct-archive-torrent` | 0o700 → 0o700 | `68c2d1a8153de9bb97ca250ea5cedd15b2ad67818142f8a89aa5440eca93d627` | `f801b8f2be4308188bb35e8b613e65d32ec658fdcf5cbb19af8d2c02ef46c8cd` |
| `myct_cloud_progress.py` | 0o700 → 0o600 | `18ac27004c1abc9376b3e616affcebf13ab977da7ed07a9444c4c632fb52dbc1` | `06a409686857a5ad1f93c145dea496de92d33b780279ad9c66c4418ce663ff03` |
| `myct_cloud_config.py` | ausente → 0o600 | `ausente` | `55d91d458c859cfcc20b5153a443d1ce3ba973af6e5dd612dfe3cde101150df8` |

Staging privado 0700 no mesmo filesystem de destino; arquivos completos,
sincronizados e verificados antes da ativação. O módulo ausente foi criado com
publicação atômica sem substituir destino existente; os três helpers foram
substituídos com rename atômico, sem truncamento. O staging desta implantação foi
removido após sucesso; backup e manifestos preservados. **Sem implantação parcial.**

O `myct_cloud_config.py` incluído é o módulo não secreto já presente no commit
aprovado/SETUP, não uma nova implementação. Não foi criado/alterado o arquivo de
seleção remota nem a configuração Rclone. Não houve OAuth, reinstalação de
componentes ou execução do instalador completo. Os helpers são coerentes com o
bundle, sem transformar a instalação pessoal em uma instalação SETUP gerenciada.

## Verificações posteriores e seus limites

Imports com Python isolado e sem escrita de bytecode passaram na fixture de
compatibilidade e na instalação ativa. Foram compilados os quatro arquivos;
carregados os motores de arquivo/torrent e o bridge, sem chamar seus dispatchers.
Uma requisição fictícia de `space` com 13 caminhos passou pela validação do
bridge. A telemetria foi exercitada apenas em recibo/arquivo temporários privados
criados pelo próprio smoke test; o leitor de hashes processou **26 bytes reais**.
O teste finalizou a telemetria sem inventar porcentagem global de 100%.

Os parsers dos catálogos existentes e o índice Media foram consultados somente
em memória, sem imprimir entradas. Hashes/identidade/mtime dos catálogos,
seleção remota e índice permaneceram iguais; metadados agregados de `~/Media`
(dev/inode/nlink/tamanho/mtime/ctime) iguais nas fotografias. Não se releu nem
recalculou hash de 18 GiB para produzir uma alegação de integridade total da mídia.
A implantação só escreveu arquivos de código, staging e backup autorizados.

Os 14 recibos continuaram byte a byte iguais (digest agregado privado), sem
novos jobs. Core, SSH, Transmission, PostgreSQL e Prowlarr continuaram ativos
com os mesmos PIDs; timer AV continuou em espera, sem alteração. Nenhuma chamada
start/stop/restart/enable/disable foi feita em serviços. Não houve `space` real,
`release`, `restore`, `recover`, remoção de torrent, upload ou modificação no Drive.
Os resultados de leitura local não provam disponibilidade remota atual: isso
será revalidado pela consulta iniciada pelo proprietário no Moto.

## Roteiro de aceite físico — sem excluir

1. Abrir a página oficial da pre-release, conferir SHA-256 e instalar a APK como
   **atualização** do My-CT code 48. Não desinstalar, limpar dados nem apagar Cofre.
2. Conectar normalmente ao Fedora e abrir **Downloads → Mídia → Armazenamento → Nuvem**.
3. Escolher o pacote de aproximadamente 18 GiB previamente arquivado/verificado.
4. Selecionar os **13 hardlinks Jellyfin** e iniciar **Consultar espaço / prévia**.
5. Observar fase, leitura numerada, bytes/total, porcentagem, taxa e ETA quando
   calculáveis. Fases sem denominador/ETA confiáveis devem ser indeterminadas.
   Os números se referem à leitura indicada, não necessariamente à consulta toda.
6. **Permanecendo na tela e com a consulta ativa**, tocar em **Atualizar**.
7. Aguardar conclusão, conferir **13/256 selecionados** e **Liberar espaço…**
   habilitado quando a prévia for válida, sem blockers e dentro do prazo.
8. Abrir o diálogo, conferir consequências e confirmação específica Jellyfin,
   e **cancelar sem confirmar a exclusão**. Não há exclusão real como condição
   de aceite desta candidata.

Teste separado de sair/voltar: na mesma conexão/processo, a seleção visual deve
persistir; sair da tela/segundo plano revoga autorização por segurança e pode
exigir nova consulta. Reconexão/morte do processo não restaura consentimento.
Atualizar uma prévia já concluída também a revoga. Essa invalidação legítima é
diferente do toque em Atualizar enquanto `space` continua ativo na mesma tela.

Registrar no retorno do proprietário: instalação/abertura, 13 selecionados antes
e depois, efeito de Atualizar, fases/bytes/taxa/ETA observados, habilitação do
diálogo e seu cancelamento. Registrar a duração total da consulta e, quando
presentes no diário privado `JOB.progress`, **somente os números sanitizados de
`phase_seconds`**, sem anexar logs Rclone, paths pessoais, tokens ou catálogos.
Essas medições subsidiarão a R2 em missão separada; não antecipar conclusões de
gargalo pelo tempo de build ou por fixtures pequenas.

## Pendências e retomada

- Instalação, verificação visual e aceite funcional no Moto G06: **pendentes**.
- Consulta real do pacote de ~18 GiB e tempos por fase: **pendentes do proprietário**.
- PR #60 permanece draft e sem merge/Ready for Review. A release é candidata,
  sem encerramento da R1 e sem aprovação definitiva de exclusão.
- Aguardar aceite funcional e avaliação final da Luna. Sem nova implantação,
  rollback, exclusão ou início de R2 automaticamente.
