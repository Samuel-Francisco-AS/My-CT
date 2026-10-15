# MYCT-3C — Dashboard Android com telemetria real

**Data:** 15/10/2026. **Estado: implementação preparada, aceite físico pendente.**
Branch `myct-3c-android-dashboard`, criada de `origin/main` atualizada
(`5e6eab7`). Não declarar MYCT-3C concluída até a prova no Moto G06.

## Aceite definido antes da implementação

No Moto, autenticar pelo SSH existente, abrir Saúde e ver CPU, RAM, disco e
uptime do Core obtidos por HTTP em túnel SSH. Atualizar ao entrar e por botão,
sem polling. Preservar Terminal/Arquivos, exibir ausência de SSH/Core e marcar
amostras antigas após falha/desconexão. Encerrar listener ao desconectar e criar
outro na nova sessão; API Fedora e listener Android somente loopback.

## Implementação

- `SshConnection.kt`: encaminhamento JSch na sessão já autenticada/pinada,
  `setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 8765)`. Porta atribuída pelo
  SO, criada sob demanda e reutilizada até encerrar SSH. Criação/remoção
  serializadas contra desconexão; `close()` remove listener antes de desconectar
  a sessão. Não cria outro login nem executa shell para métricas.
- `Health.kt`: contrato JSON real do Core, validação de tipos/intervalos,
  tamanhos de 64 bits, formatação IEC e uptime do processo Core. Utilizado =
  total − disponível; disco usa a disponibilidade ao usuário (`f_bavail`).
  `HttpURLConnection`, URL fixa, sem proxy/cache/redirect, timeouts de conexão e
  leitura de 4 s, corpo limitado a 8 KiB. API continua privada no Fedora.
- `HealthModel.kt`: IO fora da main, uma consulta por vez, resultado com data,
  cancelamento e geração para descartar respostas de sessões antigas. Última
  amostra fica apenas em memória, explicitamente antiga após erro/desconexão;
  nova sessão limpa a amostra para não misturar hosts.
- `HealthScreen.kt`/`MainActivity.kt`: Terminal | Arquivos | Saúde; carregamento,
  Atualizar, mensagens e última consulta. Abas acessíveis desconectadas para
  consultar estado antigo e retornar ao formulário em Terminal. Nenhum timer,
  gráfico ou biblioteca de visualização. Rotação/reentrada pode consultar de novo.
- `TerminalModel.kt`: anexa/desanexa Saúde ao mesmo ciclo SSH; perda conhecida de
  conexão invalida imediatamente o estado atual do dashboard. Sem mudança no
  renderizador, teclas/IME/clipboard, SAF, serviços foreground ou PostgreSQL.
- Configuração de segurança Android permite HTTP somente em `127.0.0.1`,
  mantendo proibição geral de cleartext. Loopback Android pertence ao aparelho,
  não é isolamento contra outros processos locais; não expõe listener à LAN.
- Versão `0.3.0-health`, code **5**. JSON da plataforma em produção; apenas
  testes JVM acrescentam `org.json:json:20250517` (sem dependência runtime nova).

Referências consultadas: [API JSch](https://javadoc.io/static/com.github.mwiede/jsch/2.28.4/com.jcraft.jsch/com/jcraft/jsch/Session.html)
(conferida também com `javap` no jar 2.28.8 instalado) e
[segurança de rede Android](https://developer.android.com/privacy-and-security/security-config).

## Ambiente e validação

Fedora 44 real, g++ 16.2.1, GNU Make 4.4.1, Java 21.0.10, Gradle 8.14.3,
SDK 36/Build Tools 36.0.0, JSch 2.28.8 já existentes. `pg_config --version`
retornou `PostgreSQL 18beta1`; nenhum pacote/serviço global foi instalado.
Download pequeno apenas da dependência de teste JSON via Maven; builds seguintes
podem usar cache offline. Sem emulador, novo serviço ou instalação Android automática.

Comandos:

```sh
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain
python android/terminal/upstream/verify.py
cd server
make
./myct-core
# Outra sessão:
curl --fail --silent http://127.0.0.1:8765/v1/health
ss -lntp 'sport = :8765'
# Encerrar somente o Core iniciado para o teste com Ctrl-C.
```

Resultado final: **BUILD SUCCESSFUL** (35 s na última execução offline),
**35 testes: 34 aprovados, 0 falhas, 1 ignorado** (handshake Fedora opcional,
sem `MYCT_TEST_LOCAL_SSH=1`). Os 9 testes novos de Saúde passaram.
`lintDebug`: **No issues found**. Verificação Termux: **21 arquivos upstream
verificados, 2 patches documentados**. `git diff --check` sem erros.
Relatórios locais em `android/app/build/reports/tests/testDebugUnitTest/` e
`android/app/build/reports/lint-results-debug.html`.

Testes novos cobrem parsing e valores inválidos, unidades, estados/última amostra,
consulta bem-sucedida/erro, descarte de resultado durante reconexão, HTTP 200/503/
redirect/corpo excessivo, reutilização/fechamento/recriação do encaminhamento.
Um teste abre listener **real JSch** com porta dinâmica, confere loopback em
`/proc/net/tcp{,6}`, remove e consegue reocupar a porta. Esse teste não autentica
SSH, não abre `direct-tcpip` remoto e **não substitui a prova Moto ↔ Fedora**.
HTTP e falhas nos testes JVM usam servidor local sintético.

Ocorrências corrigidas: primeiro build apontou recurso XML ausente (diretório
criado e recurso incluído); primeiro teste de listener pressupunha bind na string
`getPortForwardingL`, mas a API retorna apenas porta/destino. Corrigido o assert
conforme resultado real e acrescentada conferência do bind em `/proc/net/tcp{,6}`.
Segunda hipótese/teste mínimo: a JVM representa o IPv4 no socket IPv6; prova
isolada com o mesmo jar JSch e `ss -lnt` confirmou `[::ffff:127.0.0.1]`, e
`/proc/net/tcp6` confirmou `0000000000000000FFFF00000100007F`. Teste passou a
aceitar exclusivamente as duas representações exatas de loopback (sem wildcard).
O diagnóstico inicial com `jshell` não rodou porque essa ferramenta não existe;
a prova foi feita com `java` 21 em modo source-file, sem instalar nada.

### Evidência Fedora sem banco

Core iniciado temporariamente e consultado por `curl`, depois encerrado. Cluster
preservado e parado (`pg_ctl ... status`: `no server running`). HTTP 200:

```json
{"status":"ok","cpu_percent":55.2,"ram_total_bytes":8199708672,"ram_available_bytes":6746890240,"disk_total_bytes":237907214336,"disk_available_bytes":141528940544,"uptime_seconds":0}
```

`ss` comprovou somente `127.0.0.1:8765`; após teste, nenhum listener nessa porta.
Core: **7.684 KiB RSS, 0,0% CPU** em snapshot; amostragem CPU do endpoint mede
outro intervalo. Build Gradle chegou a aproximadamente 1,7 GiB RSS em amostra
transitória, sem daemon permanente. APK aproximadamente 13 MiB; RAM/CPU do APK
no Moto ainda não medidas. Não repetir testes extensos do banco/3A/3B.

## APK e assinatura

`artifacts/myct-3c-debug.apk`, com `artifacts/myct-3c-debug.apk.sha256`, fora do Git.
Origem: `android/app/build/outputs/apk/debug/app-debug.apk`.
Tamanho: **13,552,604 bytes**. SHA-256: `28fd14954a8ec9e485486093d14a7975ab3ed0d998e564f2d0e97aa82bcc076f`.
`apksigner verify --print-certs` conferiu o mesmo certificado do APK MYCT-2R:
SHA-256 `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Atualizar sem desinstalar; não apagar dados/pins nem commitar keystore.

## Roteiro curto de comprovação no Moto G06

1. Fedora: `cd ~/Projetos/My-CT/server && ./myct-core`. Banco pode ficar parado.
   Em outra sessão: `curl -fsS http://127.0.0.1:8765/v1/health`.
2. Transferir APK por USB/MTP ou SFTP existente, conferir SHA-256, instalar como
   atualização. Se ADB estiver autorizado: `adb install -r artifacts/myct-3c-debug.apk`.
3. Em Saúde sem SSH, conferir mensagem explícita. Voltar ao Terminal e conectar
   normalmente, preservando pin e autenticação existentes.
4. Abrir Saúde: conferir CPU, RAM utilizada/total/disponível, disco e uptime.
   Comparar com `curl` próximo no tempo: RAM/disco variam; CPU é outra amostra de
   120 ms e não deve coincidir exatamente. Tocar Atualizar e conferir data/uptime.
5. Alternar Terminal (`pwd`), Arquivos (listar/abrir pasta) e Saúde. Confirmar
   coexistência. Anotar visualização real, legibilidade e ausência de regressão.
6. Parar apenas o Core com Ctrl-C: Atualizar deve informar indisponível e marcar
   dados anteriores. Reiniciar manualmente e Atualizar deve recuperar.
7. Encerrar SSH: Saúde deve mostrar sem conexão e amostra antiga. Confirmar no
   Terminal Android via ADB autorizado (`adb shell cat /proc/net/tcp`, se permitido)
   que o listener loopback dinâmico desapareceu; se o Android restringir essa
   inspeção, registrar esse limite. O teste JVM cobre fechamento local, mas não
   comprova sozinho a inspeção no aparelho. Reconectar e entrar em Saúde para
   conferir túnel recriado e amostra nova. Encerrar Core ao terminar a prova.

## Limites e retomada

`adb devices -l` retornou lista vazia: APK não instalado e **nenhum aceite físico**
registrado. Não há credencial SSH disponível ao agente para testar sessão
Android autenticada; tentativa CLI com BatchMode/StrictHostKeyChecking recusou
localhost sem chave conhecida, sem mudar known_hosts. Não diagnosticar bloqueio
no `sshd` sem prova: nenhum SSH/firewall/permissão global foi alterado.

Concluir somente após proprietário confirmar roteiro, métricas reais e
Terminal/Arquivos preservados. Publicar PR para revisão, sem merge automático.
Issue #9 permanece adiada; downloads, systemd, polling, PostgreSQL e refinamento
de UX continuam fora deste recorte. Core e PostgreSQL deixados parados.
