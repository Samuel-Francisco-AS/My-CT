# MYCT-SETUP-3 — Rclone e Google Drive

Candidata de 20/10/2026, empilhada sobre SETUP-2 (`98de5fd`). Nenhuma implantação
no Fedora pessoal, merge ou fechamento da issue #53. PRs documentais #54, SETUP-1
#55 e SETUP-2 #56 continuam draft e inalteradas. A implementação depende delas;
não copia o planejamento/ADR-019 da branch documental.

O proprietário aceitou os diagnósticos SETUP-1/2 no Moto G06, com proteção dos
legados e sem regressões observadas. Isso não valida instalação física limpa.
Por decisão expressa, avançamos SETUP-3 sem esse cenário: **Fedora físico limpo
NÃO VALIDADO**. SETUP-3 no Moto, OAuth real e Drive real nesta missão também
**NÃO VALIDADOS**. A auditoria reforçada Codex e revisão independente da Luna
continuam obrigatórias ao final da SETUP-4, antes de qualquer merge final.
SETUP-4 não foi iniciada.

## Aceite implementado e arquitetura

Fluxo vertical: SSH existente → Opções → Configurar servidor → Rclone / Drive →
diagnóstico passivo → plano → aprovação → pacote/helpers → configuração humana
separada → diagnóstico → confirmação própria para consulta online.

- `ops/setup/cloud.py`: alvo opcional no executor SETUP-1/2, sem Core/API novos.
  Plano SHA-256 do estado e manifesto, novamente conferido sob lock compartilhado.
  Não aceita comandos arbitrários. Não executa o motor de arquivamento no diagnóstico.
- `manifest.json`: Rclone mínimo **1.70.0**, Fedora tradicional 43/44, arquiteturas
  x86_64/aarch64, lista dos oito helpers e SHA-256 injetados no bundle determinístico.
  DNF mantém a verificação normal de assinatura, forçada também por repositório.
  Sem clone prévio, download de instalador remoto, upgrade global ou daemon.
- Publicação exclusiva dos arquivos ausentes em `~/.local/bin`; arquivos iguais
  são preservados, divergentes bloqueiam. Os helpers de mídia/torrent complementares
  são necessários aos imports reais do motor; instalá-los não inicia nem modifica
  Transmission, Prowlarr ou Jellyfin.
- `ops/myct_cloud_config.py`: seleção **não secreta** em
  `~/.config/myct/cloud.json`, permissão 0600, somente `remote` e `config`.
  Descobre o caminho efetivo por `rclone config file`, ou o padrão XDG/legado se o
  pacote estiver ausente. O plano fixa esse caminho para o worker systemd, inclusive
  caminho customizado. Nome padrão `myct-gdrive:`, mas aceita outro `NOME:`.
  `MYCT_ARCHIVE_REMOTE`, `RCLONE_CONFIG` e `--remote` dos motores continuam válidos.
  Seleção persistente preexistente divergente exige revisão manual, sem sobrescrita.
- `myct-archive` e `myct-archive-torrent` reutilizam essa seleção. Não há migração,
  segundo catálogo ou modificação no formato de registros. Pasta continua
  `My-CT-Archive/`; restauração continua vinculada ao remote registrado no catálogo.
- `myct-cloud` e o sistema de progresso permanecem com o contrato C1C. Unidades
  transitórias `systemd-run --user` mantêm trabalhos sob demanda independentes do
  SSH. O instalador só habilita linger se necessário e aprovado, explicando o efeito
  sobre outras unidades já habilitadas da conta. Não cria unidades Rclone/timers.
- Recibos/eventos privados em `~/.local/state/myct-setup/rclone`; falha parcial
  permite novo diagnóstico e plano das etapas faltantes. Não há rollback destrutivo.

A versão exata disponível no DNF **não é consultada durante o diagnóstico passivo**:
a interface informa mínimo e resolução pelos repositórios configurados ao instalar.
Isso evita escrita de caches/metadados na inspeção. O pacote efetivamente instalado
na fixture Fedora 44 foi **1.74.3**; outra versão compatível pode ser fornecida pelo
Fedora. A versão é conferida depois do DNF, e versão anterior já instalada não é
reinstalada/atualizada automaticamente. Esta resolução por repositório é uma
limitação de reprodutibilidade, explícita no plano.

## Quatro estados diferentes

| Nível | O que comprova | O que não comprova |
| --- | --- | --- |
| Instalação | Executável/versionamento, helpers íntegros, systemd/linger | OAuth |
| Configuração passiva | Arquivo privado legível, seção selecionada com `type=drive` | Validade dos tokens |
| Acesso online autorizado | `about --json`, cota e `lsjson --stat` da pasta única | Conteúdo, integridade ou recuperação do acervo |
| Transferência | Cópia/restauração de fixture com bytes/hashes iguais | Recuperabilidade de outras mídias |

O diagnóstico lê só metadados de configuração e tipo do remote escolhido. Não chama
`listremotes`, `config dump`, `about`, helpers de status, nem lê catálogos/trabalhos.
Configuração criptografada, pública, redirecionada ou ilegível aparece como protegida;
remote ausente aguarda configuração; backend diferente requer intervenção.
Instalação anterior não se torna gerenciada só porque foi detectada. O recibo SETUP-3
indica provisionamento dos helpers/seleção, sem reivindicar propriedade da conta OAuth.

A verificação online tem diálogo próprio: avisa expressamente que o Rclone **pode
renovar tokens e escrever rclone.conf**. A resposta só contém estados, cota numérica
e mensagem sanitizada; nunca respostas brutas do provedor. Erro de OAuth, rede,
permissão/cota ou JSON inválido falha sem presumir acesso. Cota zero é comunicada.
A pasta pode não existir antes do primeiro arquivamento; não é criada pela verificação.
Nenhum arquivo da nuvem é apresentado como recuperável por esse resultado.

## OAuth humano, sem navegador no Fedora

A tela orienta projeto Google Cloud, Drive API, consentimento e Client ID próprio
para aplicativo de computador, incluindo a atenção a usuários de teste/publicação.
Credenciais são digitadas somente nos prompts interativos do Rclone, pelo proprietário.

O botão de túnel abre somente Android `127.0.0.1:53682` → SSH → Fedora
`127.0.0.1:53682`. Em seguida, **Ir ao Terminal SSH privado** usa o Terminal real,
sem injetar comandos. Capturas ficam bloqueadas na sessão de UI após esse atalho,
inclusive ao navegar e após recriação da Activity. Usar o shell no prompt, sem
captura/gravação. O comando humano é `rclone config`, ou
`rclone --config /caminho/escolhido/rclone.conf config` para caminho customizado;
nenhuma credencial faz parte do comando. Criar somente o remote ausente, selecionar
Drive, inserir Client ID/Secret nos prompts e responder **Y** à autenticação pelo
navegador. Abrir privadamente no navegador do Android a URL loopback mostrada pelo
Rclone. O Fedora não precisa de GUI. Encerrar o túnel após concluir; desconexão SSH
também o encerra. Porta local ocupada resulta em falha clara, sem mudar o servidor.

O método oficial com **N** exige outra máquina com Rclone/navegador. Não fornecer
comandos `authorize` contendo Client Secret ou blob em argumentos: o túnel foi
escolhido para manter segredos fora de argv/histórico do shell. Nenhum cliente OAuth
foi implementado, e o aplicativo não recebe nem persiste tokens. Não enviar credenciais
ou screenshots do wizard ao Codex/Luna. Remote anterior não recebe botão de recriação
ou reautorização automática. Não clicar no wizard para refazer o Drive pessoal.

Referências oficiais consultadas: [Drive/Client ID](https://rclone.org/drive/),
[servidores headless/túnel](https://rclone.org/remote_setup/),
[authorize e seus argumentos](https://rclone.org/commands/rclone_authorize/),
[configuração e uso](https://rclone.org/docs/).

## Evidências e comandos

Os testes de instalação usam contêiner rootless Fedora 44 descartável, sem volumes
ou portas do host, sem modo privileged, limite 1,5 GiB/1 CPU/1024 PIDs. Não é daemon
necessário ao produto nem equivale a instalação física limpa. Imagem de teste
SETUP-1 previamente disponível. A fixture termina removida pelo próprio teste.

```sh
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py'
python3 -m unittest discover -s ops/tests -p 'test_archive*.py'
python3 -m unittest discover -s ops/tests -p 'test_cloud*.py'
python3 ops/setup/tests/validate_cloud_container.py artifacts/setup3/container-evidence.json
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
git diff --check
```

O teste de contêiner executa DNF/linger **dentro da fixture**, não no Fedora pessoal.
Simula falha após instalar o pacote, confirma registro de erro, retoma, confere
conclusão offline com OAuth pendente e reaplica sem alterar arquivos/recibos. Usa
remote local `alias`, caminho customizado e arquivo descartável, com cópia/restauração
pelos motores existentes. Confere também execução real de `myct-cloud` em unidade
transitória após saída do processo solicitante, sem credenciais herdadas por argv.
Não chama Google Drive. Não executa reboot do host.

Resultados finais e identificação do APK constam abaixo e nos JSON sanitizados:
[evidência Fedora isolado](evidence/myct-setup3-fedora.json) e
[inspeção pessoal](evidence/myct-setup3-personal.json).

No Fedora pessoal: Rclone 1.74.3, remote reconhecido localmente, não gerenciado.
Helpers dos motores anteriores divergentes bloquearam a instalação. Bytes/mtime de
rclone.conf e dos quatro helpers de nuvem instalados permaneceram iguais após o
diagnóstico. Só `rclone version` e `rclone config file` foram permitidos; nenhuma
consulta autenticada, transferência, leitura de catálogo privado ou modificação de
serviço. Core, PostgreSQL, Transmission, Prowlarr, Jellyfin e arquivos reais preservados.

Uma primeira prova local falhou no código do teste ao ler `archive.id`; o contrato
real é `archive.entry.id`. Corrigido somente o teste e repetida a prova com sucesso.
O instalador já havia instalado, registrado falha simulada e retomado corretamente.

## Validação curta no Moto G06 sem reconfigurar Drive

1. Atualizar o APK sobre code45, sem desinstalar. Confirmar `dev.myct`, code46 e dados
   anteriores presentes. Não conceder nenhuma instalação no servidor pessoal.
2. Conectar SSH normalmente; Opções → Configurar servidor → Rclone / Drive.
   Conferir versão 1.74.3, instalação anterior não gerenciada, remote reconhecido
   localmente, helpers anteriores preservados e instalação bloqueada.
3. Diagnosticar de novo; sair da seção, fechar/reabrir app e reconectar. O estado
   deve ser relido, com acesso online/arquivo ainda não verificados.
4. Informar um remote inexistente apenas para diagnóstico e depois retornar ao
   padrão. Não aprovar plano, não abrir OAuth, não editar configuração existente.
5. Conferir Saúde, Terminal, SFTP, Torrents, Busca e navegação de Armazenamento,
   sem iniciar/cancelar trabalhos de nuvem nem revalidar mídias remotas.
6. Registrar somente mensagens de estado sem segredos. Não capturar Terminal OAuth.

**Exigem autorização posterior específica:** aplicar/provisionar no Fedora pessoal;
qualquer alteração de helpers/seleção existente; configurar/reautorizar OAuth;
verificação online (inclusive renovação possível); prova descartável no Drive.
O instalador não inclui disparo automático de prova de transferência: esta entrega
prova o motor com backend local e preserva o fluxo de arquivamento já existente.
Nenhuma exclusão é parte da prova. Testes físicos no Moto, OAuth pelo túnel no Android,
instalação Fedora físico limpo e aarch64 permanecem pendentes. Rede/OAuth/cota foram
simulados com respostas negativas; não representam autorização real de uma conta.

## Arquivos e continuidade para auditoria

Backend: `ops/setup/{setup.py,cloud.py,manifest.json,build_bundle.py}`,
`ops/myct_cloud_config.py`, `ops/myct-archive`, `ops/myct-archive-torrent`.
Android: `ServerSetup.kt`, `ServerSetupModel.kt`, `ServerSetupScreen.kt`,
`SshConnection.kt`, `MainActivity.kt`, `app/build.gradle.kts`.
Testes: `ServerSetupTest.kt`, `ops/setup/tests/test_cloud_setup.py`,
`ops/setup/tests/validate_cloud_container.py`. Documentação: este relatório,
evidências JSON, README e decisão em `05-DECISOES-E-QUESTOES.md`.

Pontos para Luna: separação passivo/online, nenhuma saída/argv com credenciais,
recusa de legados/symlinks e preservação de catálogos, escopo DNF/linger, seleção
lida no worker real e limites das evidências. Revisar o empilhamento #54–#56 antes
do fechamento; não declarar instalado fisicamente com base em mocks/contêiner/build.


## Resultados da candidata RC1

| Verificação | Resultado real |
| --- | --- |
| Python SETUP-1/2/3 | 31 PASS |
| Motores de arquivamento | 79 PASS |
| Ponte/progresso/cancelamento de nuvem | 33 PASS |
| JVM Android | 309 PASS, 2 SKIP, 0 falhas/erros |
| Build APK | PASS, code46 |
| Lint | PASS: 0 erros, 1 warning UseKtx anterior, 2 hints anteriores em SearchModel |
| Certificado vs APK SETUP-2/code45 | Idêntico, apksigner |
| Fedora rootless descartável | DNF, falha/retomada, reexecução, cópia/restore local e worker systemd PASS |
| Fedora pessoal | Inspeção passiva, configuração/helpers preservados |
| Fedora físico limpo / Moto SETUP-3 / OAuth real | NÃO VALIDADOS |

Os dois SKIP são integrações já condicionais com Fedora/SSH real:
`FedoraHandshakeTest.realHostKeyMismatchAndTrustedNegotiationBeforeAuthentication`
e `IdeFedoraSftpTest.disposableFedoraFileThroughActualJschSftpRoundTripAndConflict`.
Nenhum deles foi convertido em aceite físico.

Fixture: Rclone 1.74.3; SHA-256 do arquivo restaurado
`f0b222d30e58d7674ed1131d9980e647be3ba40f4e89acd59c5e5475d5dd692b`.
Instantâneo do contêiner após provas: **59,31 MB** de RAM; não é medição de pico.
Payload da prova e do APK idênticos:
`7ee079c137fddc9bccd110e26fdd9a3308e90e585f7dfd7ce638f947a156b8f1`.
Instalador em memória 82.505 bytes; payload 88.791 bytes.

APK **0.12.0-setup3-rc1**, `dev.myct`, **versionCode 46**, **19.304.077 bytes**.
SHA-256: `0ed3f5a6def923404e135830833ac18561983797ca58ee989194a8e329d7b850`.
Certificado SHA-256 público preservado:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Atualização sobre code45 sem desinstalação; confirmação física com o proprietário.

[Release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-setup3-v0.12.0-rc1) ·
[APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup3-v0.12.0-rc1/myct-setup3-0.12.0-rc1.apk) ·
[SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup3-v0.12.0-rc1/myct-setup3-0.12.0-rc1.apk.sha256).

Build final: 2 min 43,53 s (ver log local), pico RSS 2.317.376 KiB medido por `/usr/bin/time -v`; nenhum limite do Fedora foi alterado. Warning Gradle sobre Metaspace não causou falha.
