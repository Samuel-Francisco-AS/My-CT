# MYCT-5D-B2 — antivírus no Android, candidata para aceite

16/10/2026 · Issue [#18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18).
Branch `myct-5d-b2-android-antivirus`, criada da `main` limpa/sincronizada em
`b6cac9d`, após confirmar o merge B1/PR #20 (`a5770af`).
**Implementação e testes automatizados concluídos; aceite físico no Moto G06 pendente.**
Não confundir as provas do motor B1 ou os testes JVM abaixo com execução Android
real autenticada. PR/branch devem permanecer abertos, sem merge até revisão.

## Critérios adotados

Arquivo → Verificar arquivo → relatório SHA-256 preservado → botão explícito
Verificar antivírus → resultado real da CLI v1, incluindo JSON em exit 1.
Cancelamento deve fechar só o exec da análise; sair do app cancela conforme 5D-A.
Opções deve consultar estado e permitir pedido manual de atualização, confirmando
término por update-status. Polling somente na seção visível e durante execução.
Nenhum upload de conteúdo/hash, ação corretiva ou mudança no motor/timer Fedora.

## Implementação e arquivos

- `Antivirus.kt`: contrato tipado, versões/estados/metadados, comandos fixos e
  encoding hexadecimal UTF-8. Não aceita opções livres do cliente.
- `Downloads.kt`: leitor bruto `executeSshResult` preserva stdout, stderr e exit
  status; streams limitados a **256 KiB cada**, timeout e interrupção. A função
  existente `executeSshCommand` mantém a política de erro dos downloads/5D-A.
- `SshConnection.kt`: exec AV reutiliza sessão autenticada e pinning; scan abre
  stdin e o mantém aberto como 5D-A. Não cria conexão alternativa, PTY, API ou
  acesso a credenciais. Fechar o exec envia EOF; não desconecta sessão/terminal.
- `AntivirusModels.kt`: operações em `runInterruptible(Dispatchers.IO)`, geração
  para descartar respostas antigas, espera pelo fechamento de operação cancelada,
  sem fila ou persistência de resultados. Modelos separados para scan e assinaturas.
- `FilesModel.kt` / `FilesScreen.kt`: integra a seção Antivírus ao relatório.
  Inicialmente Não analisado; escolher/abrir arquivo não dispara ClamScan.
  Não sobrepõe análise AV ao cálculo SHA-256. Usa bytes da inspeção quando chegam,
  com fallback ao tamanho SFTP previamente conhecido. O limite é **2.000.000.000
  bytes inclusivos**; acima dele o botão fica desabilitado com aviso antes de
  solicitar AV. A CLI continua decidindo pelo tamanho real e demais limites.
- `AntivirusScreen.kt`: mensagens legíveis, nomes de detecções, versão do motor,
  daily/data/freshness, duração e RSS opcional. Não mostra engine_output nem log
  bruto do FreshClam na interface principal. Não oferece excluir/quarentena.
- `MainActivity.kt` / `TerminalModel.kt`: Opções → Antivírus e assinaturas, com
  retorno às opções gerais no mesmo diálogo, evitando acumular controles longos.
  Continuam cinco abas. Lifecycle/reconexão encerram só consultas e descartam
  estado anterior; os controles existentes de terminal foram preservados.
- `build.gradle.kts`: **0.5.6-antivirus / code 13**, mesmo `applicationId=dev.myct`.
  Nenhuma dependência nova.

CLEAN significa nenhuma detecção nas assinaturas informadas; não atesta segurança.
DETECTED destaca os nomes, sem ação sobre arquivo. INCOMPLETE preserva detecções
parciais e motivo, inclusive BUSY/limites. ERROR/UNAVAILABLE mantêm o motivo da CLI.
Resposta inválida, desconexão, ausência de exit status ou timeout do transporte
não produzem veredito: mostram resultado não confirmado. CLI ausente (126/127)
é indisponibilidade. O parser recusa versão desconhecida, CLEAN contraditório com
sinal/incomplete_reason, sucesso sem metadados essenciais ou DETECTED sem nomes.

O progresso informa ~10 s para carregar assinaturas e possível demora maior em
arquivos grandes. Timeout SSH de scan **200 s**, acomodando o worker B1 de 180 s
e o adaptador de 190 s; nenhum limite do motor foi ampliado. Consultas/pedido de
update usam 25 s. Cancelar, fechar relatório, desconectar ou sair do app cancela
scan; recriação da Activity conserva o estado como 5D-A. EOF/close afeta somente
esse canal. Uma queda real da sessão compartilhada pode afetar todas as abas;
isolamento lógico do exec não elimina esse fato. A rapidez de EOF em falha de
rede depende da detecção pelo SSH/servidor e de eventual IO bloqueado no kernel.

## Atualização em Opções

Abrir a seção consulta `myct-av status` e `myct-av update-status`. Não solicita
atualização automaticamente. Somente o botão chama `myct-av update`; seu
`requested:true` é exibido como pedido, sem usar SUCCESS histórico dessa resposta.
A conclusão é lida separadamente por `update-status` e exige `result: SUCCESS`.

Enquanto `running:true`, consulta a cada **2 s**, com uma requisição por vez.
WAITING mostra espera por análise; RUNNING/UPDATING mostram atualização em curso.
SUCCESS, ERROR e INTERRUPTED encerram polling. Falha preserva último sucesso/data;
falha de transporte marca dados anteriores como não confirmados. O botão fica
bloqueado durante envio/acompanhamento para não duplicar pedido.

Fechar seção/menu, colocar app em background ou desconectar interrompe consultas,
**não** o serviço remoto. Reabrir consulta o estado real, sem repetir update.
Não existe polling permanente, retomada automática, histórico ou agenda Android.

## Validação automatizada

Ambiente: Fedora 44, SDK/build-tools 36, JBR 21.0.10, Gradle 8.14.3 e configuração
do projeto existente (heap 1536 MiB, duas workers, sem daemon persistente).
Nenhum pacote instalado e nenhuma alteração de limites globais de build.

```sh
ANDROID_HOME="$HOME/Android/Sdk" JAVA_HOME=/opt/android-studio/jbr \
  ./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
git diff --check
```

**86 testes PASS / 1 opcional ignorado**, zero falhas/erros; **14 testes novos**
em `AntivirusTest` e `AntivirusModelsTest`. Lint: **0 erros, 2 avisos de versões
já existentes e 1 sugestão de estado Compose existente**. Build/assemble aprovados.
O Gradle avisou que o processo único de build atingiu o orçamento de metaspace ao
final; concluiu com sucesso e encerrou. Não ampliamos memória para suprimir aviso.

Respostas CLI e transportes dos testes são **simulados**, sem solicitar análise,
update real ou criação de EICAR no Fedora nesta B2. Cobertura:

- CLEAN, DETECTED, INCOMPLETE, ERROR e UNAVAILABLE; detecções parciais; JSON válido
  em stdout com exit 1; CLI ausente; JSON incompleto/contraditório/versão inválida.
- Assinaturas antigas, dados daily/data, duração/RSS e último sucesso preservado.
- Pedido aceito com SUCCESS histórico, consulta separada WAITING/RUNNING e
  conclusão efetiva; SUCCESS, ERROR, INTERRUPTED e NEVER.
- Atualização não iniciada ao abrir, interrupção do polling ao fechar, reabertura
  consultando resultado sem repetir pedido, falha SSH e cancelamento de scan.
- Cancelamento fecha operação antes de iniciar outra; respostas antigas não
  substituem estado atual. Leitor bruto limita **ambos** os streams, timeout,
  interrupção e status ausente. Novo exec continua possível após cancelamento.
- Caminho hostil com espaços, acentos, aspas e metacaracteres passado a `/bin/sh`
  real de teste: só hex chega como argumento, nenhum comando do nome executado.
- Fronteira numérica inclusiva 2.000.000.000 aceita pelo modelo; +1 recusado sem
  abrir SSH. Nenhum arquivo grande criado para esses testes.
- Regressão SHA-256/comparação e demais testes existentes aprovados. Teste JSch
  já existente confirma EOF/close preservando shell/sessão em transporte simulado;
  não é prova autenticada no Moto. `openDownloadExec(true)` é o mesmo mecanismo
  da 5D-A e da nova análise AV.

Não repetimos benchmark/aceite B1, testes extensivos de motor ou testes físicos
5D-A. Nenhum teste automatizado nesta etapa comprova layout/IME, funcionamento
SSH autenticado no aparelho ou preservação real de um alvo pelo Android.

## Fedora preservado

Nenhum arquivo em `server/` ou `ops/` alterado; não houve deploy, instalação,
update de assinaturas, restart de serviço, sudo ou mudança de configuração.
Somente leitura do timer confirmou **active/enabled**, OnUnitInactiveSec 36h.
Hashes das unidades instaladas permaneceram:

```text
c5b2f7ad735e15340a3530510fba384b520681791d8640978abb5a6591700de7  myct-av-update.timer
9d536360bfa7cfb584db1a8801b2ed720301d6a00dabf21eff04659ac20f5b63  myct-av-update.service
```

SSH, Core, PostgreSQL, Cofre, downloads, Transmission, base privada e FreshClam
não receberam alterações. A análise B2 apenas solicita a CLI B1, cuja preservação
e consumo já foram aceitos. Não há varredura automática, recursão, execução de
alvos, envio a APIs, isolamento ou controle de torrents por detecção.

## APK candidato e instalação pelo proprietário

[Pré-release myct-5d-b2-v0.5.6-rc1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5d-b2-v0.5.6-rc1).
Assets `myct-5d-b2-0.5.6-debug.apk` e `.apk.sha256`.

```text
801517302b150dfb92a9e57e3efbcc6decaf95bb2a392d40e8e7bc1217a7a14a  myct-5d-b2-0.5.6-debug.apk
```

Certificado SHA-256 conferido igual ao APK 0.5.5 aceito:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Pacote `dev.myct`, code 13. Instalar como atualização, **sem desinstalar/limpar
os dados**. Não foi instalado APK pelo agente, nem conectado aparelho/emulador.
Pré-requisito Fedora já atendido pela B1: CLI e worker atuais instalados e usuário
SSH com acesso ao arquivo sob seu home. O APK não instala componentes no servidor.

## Roteiro mínimo Moto G06 — pendente de execução/aceite

1. Baixar APK e `.sha256` da pré-release; conferir em computador com
   `sha256sum -c myct-5d-b2-0.5.6-debug.apk.sha256`. Atualizar sobre a 0.5.5,
   sem apagar dados (opcional, se ADB já autorizado: `adb install -r APK`).
2. Conectar SSH como de costume, em rede privada/host conhecido.
3. Arquivos → selecionar arquivo benigno conhecido → Verificar arquivo.
   Conferir que tamanho/MIME/SHA-256/checksum continuam disponíveis.
4. Na seção Antivírus, conferir Não analisado → Verificar antivírus. Aguardar
   carga das assinaturas; conferir CLEAN, versão do motor, daily/data/freshness
   e duração. Confirmar que o arquivo continua presente/intacto via inspeção/SFTP.
5. Se já houver fixture EICAR inofensiva sob o home, selecioná-la e verificar
   DETECTED, nomes e arquivo preservado. Não usar malware real nem torrent ativo.
6. Abrir Opções → Antivírus e assinaturas, conferir disponibilidade e último
   sucesso. Tocar Atualizar assinaturas; observar pedido, estado e resultado real.
   Fechar/reabrir a seção se necessário: deve consultar sem reiniciar update.
7. Repetir análise em fixture própria, cancelar durante carga, conferir
   INCOMPLETE local e terminal SSH funcionando. Verificar também sair/voltar ao
   app; não deve inventar sucesso nem repetir scan.
8. Confirmar terminal/SFTP/Saúde/Cofre/Downloads utilizáveis, sem precisar refazer
   os aceites extensivos das fases anteriores. Registrar versão e problemas.

**Retomada:** branch/PR B2 abertos; APK candidato 0.5.6/code 13; issue #18 aberta.
Aceite depende de teste físico e confirmação expressa do proprietário. Não fazer
merge, ampliar escopo ou iniciar automações/varredura residente por consequência.
