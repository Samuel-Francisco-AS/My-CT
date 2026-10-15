# MYCT-5B — Gerenciador de Downloads Android

15/10/2026. Branch `myct-5b-android-downloads`, criada após `git fetch origin main`
com HEAD/base `a80813c6cdff0e6c91088699ec7b600d63725bf9` (PR #15 integrado).
**Implementado e validado automaticamente; aceite físico no Moto G06 pendente.**
Nenhum merge, instalação da CLI, deploy do Core ou alteração de serviços ativos.

## Aceite definido antes da implementação

Iniciar HTTP/HTTPS pelo Android, consultar histórico real do Fedora, mostrar
bytes/total/progresso, cancelar com confirmação e abrir a pasta pelo SFTP.
Consultas fora da thread da UI, limitadas, a cada 4 s apenas com aba e Activity
visíveis e SSH conectado. Recuperar estado do servidor na reconexão, sem repetir
ações. Independência do worker já aceita na MYCT-5A; nesta fase falta provar a
integração Android no aparelho.

## Implementação

- Quinta aba **Downloads**. Barra existente passa a rolar horizontalmente em
  telas estreitas; deslize para alcançar a quinta aba. Sem redesign do app.
- URL HTTP/HTTPS pública, nome opcional (`download` por padrão), Baixar,
  Atualizar, mensagens de pedido aceito/erro e aviso de uma transferência sem fila.
- Histórico por `myct-download list`: nome, estados em português, bytes exatos,
  total quando conhecido, porcentagem e barra determinada; sem total, barra
  indeterminada e bytes. Erros do worker (HTTP/rede/disco) aparecem em `message`.
- Cancelar exige confirmação, chama `cancel ID`, consulta novamente a lista e
  preserva parciais. Sem apagar arquivos ou retomar downloads.
- **Ver em Arquivos** abre `FilesModel.load(directory)` e seleciona Arquivos.
  Botão aguarda eventual operação SFTP existente; nenhum explorador novo.
- Histórico exclusivamente consultado do Fedora; memória de tela é descartada
  na troca de sessão. Resposta inválida/falha deixa dados anteriores marcados
  como não confirmados. Reconectar consulta novamente, sem replay de start/cancel.

`TerminalModel` fornece a mesma `SshConnection` autenticada. Cada operação abre
um **ChannelExec próprio**, sem PTY, com saída e stderr separados. Não usa o
shell interativo do Terminal nem o canal privado do Cofre. Mantém pin/host key,
autenticação, SFTP/SAF e túnel Saúde existentes. Fechar consulta fecha apenas seu
canal; fechar a sessão também fecha os canais exec rastreados.

Comando fixo em `"$HOME/.local/bin/myct-download"`, verificado com `test -x` antes
de cada operação. Argumentos têm quoting POSIX de aspas simples, incluindo
escape de aspas internas; controles são recusados, ID é UUID estrito e verbos
são fechados no adaptador. `--allow-private` é recusado como nome porque a CLI
interpretaria esse valor como opção. Validação completa de URL, DNS, destinos
e nomes permanece na CLI. Não há opção LAN/privada nesta tela.

Execução via `runInterruptible(Dispatchers.IO)`, connect limitado a 10 s e prazo
por comando de 25 s (inclui connect); máximo de 256 KiB em cada uma das duas
saídas. Saída excedente é descartada até o canal ser fechado; nenhuma resposta
ilimitada é acumulada. Uma ação seguida de list pode usar dois desses prazos.
Falha/timeout não reenvia ação: orientação é atualizar antes de repetir. Sair da
aba, ocultar Activity ou desconectar interrompe a consulta; uma ação já aceita
pelo Fedora pode continuar. Não há polling/serviço Android permanente.

URLs não são salvas em preferências, saved state ou logs Android. Stderr
arbitrário não vai para a UI: erros usam mensagens controladas; respostas de
histórico usam os campos da CLI. Metadados privados do servidor continuam com
a política MYCT-5A. Nenhuma dependência adicionada.

### Correção pontual na consulta da CLI

A primeira execução de `make -C server test` apresentou 3 falhas: `interrupted`
com mensagem de conclusão/erro já gravada. Hipótese: `show()` lia `running`, o
worker terminava durante `systemctl is-active` e a consulta marcava interrupção
usando o estado antigo. Teste mínimo determinístico altera `state` durante o
probe simulado de systemctl. Correção: reler `state` após unidade inativa,
preservando estado final. O teste novo e os 10 existentes passam. Nenhuma
mudança de motor, Core, serviços instalados ou protocolo; rationale em docs/05.

## Build, binário e APK

Ferramentas existentes: Fedora 44, GCC 16.2.1, Python 3.14.7, Java 21,
Gradle wrapper 8.14.3, AGP 8.13.0, Kotlin 2.2.20, SDK 36 e Build Tools 36.
Sem instalação de dependências, emulador ou sudo.

```sh
cd ~/Projetos/My-CT/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python3 terminal/upstream/verify.py
```

APK: `artifacts/myct-5b-debug.apk`, pacote `dev.myct`, versão
`0.5.0-downloads`, versionCode **7**. SHA-256 no arquivo
`artifacts/myct-5b-debug.apk.sha256` e abaixo. Artefatos binários fora do Git.
Assinatura debug SHA-256 `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
igual ao APK MYCT-4B conferido com `apksigner verify --print-certs`.
Atualizar **sem desinstalar**, preservando dados e pins.

**Pré-requisito no Fedora:** a verificação local encontrou CLI estável ausente
ou não executável. O proprietário deve executar, sem transferência ativa:

```sh
cd ~/Projetos/My-CT
make -C server myct-download
install -Dm0700 server/myct-download "$HOME/.local/bin/myct-download"
```

Isso também leva a correção de consulta ao binário utilizado pelo Android. Git
não atualiza cópias instaladas. O app somente orienta quando falta executável;
não instala. Não exige sudo nem mudanças em linger, SSHD ou serviços existentes.

Transfira APK por USB/MTP ou SFTP já disponível, confira SHA-256 e abra pelo
gerenciador Android. Com ADB já autorizado, opcionalmente:

```sh
cd ~/Projetos/My-CT
sha256sum -c artifacts/myct-5b-debug.apk.sha256
adb install -r artifacts/myct-5b-debug.apk
```

## Validação e evidências

- Android: **57 testes contabilizados, 56 aprovados, 1 opcional SSH local
  ignorado**, zero falhas/erros. Oito testes novos cobrem todos os estados,
  total conhecido/desconhecido/zero, resposta incompleta/inválida, quoting real
  em `/bin/sh` com CLI fictícia isolada, recusa de opção pelo nome, canal exec,
  limite de saída/timeout/fechamento, stderr sensível, cancelamento, desconexão,
  saída da aba e reconexão sem reenvio.
- `lintDebug`: zero erros, 6 avisos existentes de versões de dependências.
- `make -C server test`: **11/11 PASS**, incluindo curl/systemd reais em HOME
  temporário, fixture HTTP loopback e teste determinístico do probe. Core usa
  porta efêmera; sem interferir no Core instalado. Timeout/cancelamento da suíte
  não equivalem a novo aceite físico SSH/Moto.
- `python3 -m unittest discover -s ops/tests -v`: **6/6 PASS**, offline; sem
  desbloquear cofre real ou pedir senha.
- `python3 android/terminal/upstream/verify.py`: **21 arquivos verificados**, 2
  patches documentados. `git diff --check`: aprovado.
- ADB: nenhum aparelho conectado. Exec e modelos Android usam doubles nos
  testes; não foi feita autenticação SSH Android real nesta execução.
- Primeiro build revelou acesso de lifecycle em `Activity` genérica; corrigido
  com tipo `ComponentActivity` já utilizado no app. Build final aprovado.

Logs, JUnit e lint ficam em `artifacts/myct-5b-validation/`, fora do Git.
Limites de build existentes preservados: heap 1536 MiB, metaspace 512 MiB,
2 workers, sem novos caches/SDK. Medidas abaixo são de processos de build/teste,
não do app físico nem de todos os filhos systemd.

## Roteiro de aceite no Moto G06 — pendente

1. Instale manualmente a CLI no Fedora pelos comandos acima e atualize o APK
   sobre o anterior. Conecte pela aba Terminal com a identidade já fixada.
2. Deslize a barra de abas até **Downloads**. Confirme recuperação do histórico
   existente. Se faltar CLI, confira mensagem com instrução de instalação.
3. Cole URL **pública HTTP/HTTPS confiável**, de arquivo de teste com tamanho e
   duração suficientes para observar progresso (poucos MB bastam). Informe nome
   simples, por exemplo `prova-5b.bin`, e toque Baixar. URLs LAN/loopback não são
   liberadas pela aba; não usar a fixture privada MYCT-5A neste formulário.
4. Observe estado e bytes, total/% quando a origem informar Content-Length,
   barra e atualização em cerca de 4 s. Confira que a UI responde. Uma origem
   sem Content-Length deve mostrar progresso indeterminado e bytes.
5. Enquanto estiver ativo, tente outro pedido: deve informar ausência de fila.
   Encerre SSH pelo app e feche o aplicativo. Espere a transferência terminar.
6. Abra novamente, conecte pelo Terminal e vá a Downloads: confira estado real
   **Concluído**, tamanho esperado e nome. Não basta o aviso de pedido aceito.
7. Toque **Ver em Arquivos**: deve abrir a pasta do ID no SFTP. Baixe pelo SAF
   existente e abra o arquivo se desejar. Isso prova integração, sem refazer
   investigação de autostart/Core/cofre ou a prova de hash MYCT-5A já aceita.
8. Inicie outra transferência de teste suficientemente lenta. Toque Cancelar,
   primeiro feche a confirmação sem cancelar; depois confirme. Confira estado
   Cancelado e preservação do `.part` no Fedora, sem apagar ou retomar.
9. Confira mensagem com URL inválida e consulta após desconectar/reconectar.
   Alterne abas e saia do app: não deve existir atualização permanente oculta.
   Faça uma checagem breve de Terminal/Arquivos/Saúde/Cofre, sem novo desbloqueio
   obrigatório nem repetição dos testes físicos anteriores.

Registrar versão instalada, URL não sensível ou descrição da fixture pública,
IDs, estados, bytes, acesso à pasta e cancelamento. Não enviar segredos/URLs com
tokens. Se falhar, registrar mensagem e preservar parciais; não declarar aceite
por build. Gate MYCT-5B só encerra após confirmação do proprietário no Moto.

## Arquivos alterados e retomada

- `android/app/src/main/java/dev/myct/Downloads.kt`: contrato, parser, comandos
  seguros e execução limitada; `DownloadsModel.kt`: ações/estado;
  `DownloadsScreen.kt`: formulário/lista/progresso/lifecycle.
- `SshConnection.kt`: canais exec independentes; `TerminalModel.kt`: vínculo
  com sessão; `MainActivity.kt`: quinta aba e navegação SFTP.
- `android/app/build.gradle.kts`: versão do APK.
- `DownloadsTest.kt`, `DownloadsModelTest.kt`: testes JVM novos.
- `server/download.cpp`, `server/tests/test_download.py`: correção/teste do probe.
- README raiz e Android, docs/05, docs/10 e este relatório: uso, decisão e retomada.

Sem torrent/magnet, fila, agendamento, notificações, retomada, API nova ou banco
adicional. Histórico superior ao limite de resposta falha explicitamente;
paginação fica para demanda real. UI não oferece `--allow-private`. Timeout ou
saída da aba após envio pode ter resultado ambíguo: atualizar, nunca repetir
automaticamente. Memória/CPU do APK e conforto da barra de abas ainda exigem
aparelho físico. Dívidas de terminal/SFTP da issue #9 permanecem adiadas.

SHA de implementação: `01b79f44114da13ae82964102ff8314b441f24f9`.
Referência final do PR e medidas de entrega registradas abaixo.


## Medidas finais de entrega

- APK: **13.679.795 bytes** (aproximadamente 13,05 MiB).
- SHA-256 APK: `d8e16a53ce82fe22115defbe8e3b305636b769f4ef6aa513a36ac7e9e92755f2`.
- Logs/JUnit/lint copiados: aproximadamente 200 KiB.
- Build/testes/lint Android finais: 1 min 26 s; RSS máximo medido 1.834.984 KiB
  (aproximadamente 1,75 GiB), CPU acumulada 402 s, sem swap. Gradle avisou sobre
  metaspace ao encerrar; build terminou com sucesso, sem elevar limites.
- Build/testes servidor: 50,35 s incluindo compilação; RSS máximo 326.708 KiB
  (aproximadamente 319 MiB), CPU acumulada 16,34 s, sem swap. Filhos systemd não
  estão incluídos nessa medição. Nenhum novo daemon permanente.
- Não há medida do processo Android no Moto: aparelho não conectado.

PR: [#16 — MYCT-5B: gerenciador de downloads Android via SSH](https://github.com/Samuel-Francisco-AS/My-CT/pull/16),
**rascunho, sem merge**, aguardando validação física. O SHA de implementação
acima identifica o código/APK testado; o SHA final incluindo esta documentação
é o HEAD da branch publicado no [histórico do PR](https://github.com/Samuel-Francisco-AS/My-CT/pull/16/commits).
Para registrar a referência exata sem autorreferência no próprio commit:

```sh
git rev-parse origin/myct-5b-android-downloads
gh pr view 16 --repo Samuel-Francisco-AS/My-CT --json headRefOid
```

Na entrega local, `artifacts/myct-5b-final-sha.txt` guarda esse SHA final,
também registrado na descrição do PR. Retomar pelo PR/branch, instalar CLI e APK
somente pelo proprietário e executar o roteiro Moto; não integrar à main antes
de avaliar o aceite físico.
