# MYCT-5D-B2 — antivírus no Android, aceite operacional e fechamento

16/10/2026 · Issue [#18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18).
Branch `myct-5d-b2-android-antivirus`, criada da `main` limpa/sincronizada em
`b6cac9d`, após confirmar o merge B1/PR #20 (`a5770af`).
**ACEITA operacionalmente pelo proprietário em 16/10/2026, com limitação de arquivos grandes documentada; PR #21 integrado à `main` (merge `ed593cffe798e01177c249b9acffc700184a455a`).**
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

Na entrega inicial B2 (`5de77e3`), nenhum arquivo em `server/` ou `ops/` foi alterado; não houve deploy, instalação,
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

## Roteiro mínimo Moto G06 — referência da candidata inicial

As provas físicas já confirmadas estão registradas abaixo e não devem ser repetidas
para este diagnóstico. A lista original fica como referência do escopo.

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

## Diagnóstico pontual do vídeo de 1.788.118.547 bytes — 16/10/2026

### Provas físicas relatadas pelo proprietário (não repetidas pelo agente)

APK 0.5.6/code 13 instalado no Moto G06: inspeção de PDF pequeno com MIME,
tamanho/SHA-256; antivírus desse PDF CLEAN; cancelamento manual INCOMPLETE;
consulta de assinaturas e atualização manual real SUCCESS, último sucesso
avançando de 16:07 para 17:19, daily 28155 atualizada.

Na primeira análise do vídeo, o proprietário observou ERROR após ~70,57 s, RSS
próximo de 1 GiB, ClamAV 1.4.6 e mensagem “Falha de execução do clamscan”. A
segunda tentativa foi cancelada e informou INCOMPLETE. **Não há evidência de
malware nesse erro, nem resultado de análise completa do vídeo.** Nome, caminho
privado e conteúdo do arquivo não foram consultados/copiados/publicados.

### Fatos verificados sem nova análise do vídeo

Branch correta, árvore inicialmente limpa, referência `5de77e3`; código do
adaptador instalado e binário instalado iguais às cópias do checkout no momento
da inspeção. Pacote Fedora `clamav-1.4.6-1.fc44.x86_64`.

Limites efetivos do worker, sem alteração:

| Limite | Valor |
| --- | --- |
| Entrada / max-filesize / tamanho PCRE | 2.000.000.000 bytes |
| RLIMIT_AS, espaço de endereçamento total | 2.147.483.648 bytes / 2 GiB |
| RLIMIT_FSIZE por arquivo temporário | 2.000.000.000 bytes |
| RLIMIT_CPU soft/hard | 120 s / 120 s |
| Prazo total do worker | 180 s |
| Adaptador / leitor SSH Android | 190 s / 200 s |
| Max-scansize para esse tamanho | 1.922.336.275 bytes (entrada + 128 MiB) |
| Recursão / entradas | 8 / 1.000 |

O [scanstdin da versão 1.4.6](https://github.com/Cisco-Talos/clamav/blob/clamav-1.4.6/clamscan/manager.c#L715)
primeiro copia stdin integralmente para um temporário, depois chama a análise
desse arquivo e remove o temporário. Não é uma análise streaming sem cópia em
disco. Erros de escrita ou análise podem terminar com saída não zero; falha não
é detecção. O adaptador também descarta o diretório temporário ao terminar.

O [fmap da mesma versão](https://github.com/Cisco-Talos/clamav/blob/clamav-1.4.6/libclamav/fmap.c#L351)
reserva um mapa anônimo arredondado em páginas, proporcional ao arquivo, além
do bitmap e outras estruturas. Paginação/aging economiza páginas residentes,
mas não elimina a reserva virtual. Falha dessa reserva produz aviso de alocação;
o [scanner trata falha de fmap como CL_EMEM](https://github.com/Cisco-Talos/clamav/blob/clamav-1.4.6/libclamav/scanners.c#L5734).

**RLIMIT_AS não é RSS.** `man getrlimit` instalado confirma que esse teto limita
brk/mmap/mremap; ao excedê-lo as alocações podem falhar com ENOMEM sem consumir a
RAM física restante. Só o mapa do vídeo deixaria **359.365.101 bytes (~342,72 MiB)**
dos 2 GiB para assinaturas, bibliotecas, heap, pilhas e estruturas adicionais.
O pico RSS informado pelo usuário não mede a VM corrente no ponto da falha.

O diretório operacional continha apenas update.json, update.log e work.lock,
sem histórico de scans. Não havia registros de ClamScan no journal de usuário
consultado; consulta filtrada do kernel não retornou correspondências e não havia
coredump acessível do ClamScan. Isso não prova ausência de eventos fora dos logs
consultados. Saída do scan via SSH não é automaticamente persistida no journal.

O filesystem temporário atual é Btrfs, com ~151,88 GB disponíveis; não comprova o
espaço/cota existente na execução anterior. `statvfs` informou zero para **todos**
os contadores de inodes; não foi interpretado como prova de esgotamento de inodes.
A memória disponível atual era ~6,4 GiB; também não é medição da execução anterior
nem contraprova de RLIMIT_AS. Não foi pesquisado o vídeo no home ou em torrents.

### Prova mínima isolada: reserva virtual, sem scan e sem arquivo gigante

Foi chamada somente a API `cl_fmap_open_handle` da **libclamav.so.12 instalada**
em um processo Python descartável, com RLIMIT_AS=2 GiB. Callback de leitura
retornaria EOF e contou invocações: **zero**. Não foram carregadas assinaturas,
criados arquivos/temporários de alvo nem chamados ClamScan ou scan de conteúdo.
Uma reserva anônima PROT_NONE de 1 GiB simulou ocupação de VM, sem tocar páginas.

| Condição | Resultado da reserva via fmap |
| --- | --- |
| Mapa de 1.788.118.547 bytes, sem reserva adicional | Passou |
| Mapa de 64 MiB, com reserva adicional de 1 GiB | Passou |
| Mapa de 1.788.118.547 bytes, com reserva adicional de 1 GiB | Falhou |

Diagnóstico real emitido: `LibClamAV Warning: fmap: map allocation failed`.
VmSize inicial **264.320 KiB**, RSS **15.816 KiB**. Com a reserva adicional,
VmSize **1.312.896 KiB**, RSS **15.956 KiB**. Pico RSS de todo o teste **16.608 KiB**
(~16,2 MiB), tempo medido **0,002 s**. A operação sem reserva mostra que esse
tamanho não é intrinsecamente recusado pelo fmap instalado; a falha controlada
mostra a interferência do orçamento virtual. A reserva de 1 GiB é **sintética**:
não é uma medição das assinaturas nem reprodução da análise/erro do MKV.

### Hipóteses e conclusão possível

| Hipótese | Avaliação |
| --- | --- |
| Reserva virtual + assinaturas ultrapassam RLIMIT_AS | **Principal, fortemente sustentada** pelo código, orçamento e prova isolada; não confirmada na execução histórica sem JSON/VM daquele instante |
| ENOSPC/cota/permissão/erro de IO no temporário | Possível; espaço atual não exclui falha histórica. Necessita mensagem original do motor |
| Teto de entrada ou max-filesize de 2 GB | O tamanho relatado está abaixo do teto; não explica sozinho a falha |
| Temporário ultrapassa RLIMIT_FSIZE | Cópia simples do vídeo cabe no teto. Sinal SIGXFSZ seria normalmente INCOMPLETE; falta a saída original para avaliar outros temporários/erros de escrita |
| Timeout total do worker / SSH | ~70,57 s é inferior a 180/190/200 s; o ramo observado não corresponde ao timeout normal |
| RLIMIT_CPU / encerramento por sinal | Tempo de CPU não pode ser inferido apenas do elapsed; porém sinal reportado pelo worker seria classificado INCOMPLETE, não a mensagem ERROR observada |
| Leitura incompleta de stdin / alteração do original | O worker confere offset e metadados; normalmente geraria incomplete_reason/INCOMPLETE. Sem JSON original não há prova retrospectiva |
| Limitação de parser/formato ou outro erro interno | Continua possível; requer engine_output original para distinguir |

**Conclusão:** o mecanismo de falha por espaço virtual foi demonstrado com custo
mínimo; a causa individual do evento histórico permanece **não confirmada**.
Não foi justificado aumentar memória/tempo, alterar ingestão ou repetir o MKV.
Não há correção comprovada que torne essa análise grande completa. Aceitar até
2 GB continua sendo autorização para solicitar análise, não garantia de conclusão.

### Por que o Android mostrou somente a mensagem genérica

O worker já retorna exit_code, signal, incomplete_reason e engine_output. A CLI,
no ramo de código de saída diferente de 0/1, sem motivo parcial e sem sinal,
substituía a explicação por “Falha de execução do clamscan”. O parser Android
prioriza `message`; `engine_output` não é despejado na interface.
Isso explica a perda de detalhe sem implicar falha no transporte JSON exit 1.
O texto observado é compatível com esse ramo, mas **não substitui os campos brutos
faltantes**. Exit 2 é compatível com falha de alocação/IO; não foi capturado do
vídeo e não é declarado como fato da execução anterior.

### Correção pequena realizada, sem alterar limites ou instalar componentes

Somente `ops/myct-av` e seus testes foram ajustados para reconhecer diagnósticos
explícitos de alocação/reserva, espaço/cota, tamanho e criação/escrita temporária.
A mensagem usa frases fixas e inclui o exit code, sem ecoar caminhos/linhas privadas.
`fmap() failed` isolado não é promovido a prova de ENOMEM; informa falha de mapeamento
com causa não identificada. Falhas desconhecidas continuam sem causa inventada.

**Classificação preservada:** ERROR continua ERROR nesses casos. Cancelamento,
sinais, motivos de inspeção parcial, detecções parciais e UNAVAILABLE mantêm a
precedência anterior. Não converter tudo em INCOMPLETE para aparentar aprovação;
em hipótese alguma transformar falha em CLEAN. Essa melhoria explica futuras
falhas, **não conserta nem confirma a causa do MKV**.

Teste direcionado: `python3 -m unittest discover -s ops/tests -p test_av.py -v`:
**12/12 PASS**, dez anteriores e dois novos testes, com várias entradas simuladas.
Incluem mensagens de memória/IO desconhecidas, exclusão de caminhos privados,
classificação original, detecções parciais, sinais/cancelamento e prioridade de
base indisponível. Nenhum scan, PDF, EICAR, update ou benchmark aprovado foi repetido.

**Worker C++ não mudou:** não precisa recompilar/reinstalar para esta melhoria.
**Android não mudou:** nenhum novo APK/release foi gerado. Para a nova mensagem
chegar à 0.5.6 já instalada, seria necessária atualização manual/autorizada de
**somente o adaptador `myct-av`**, com backup. Não foi instalada nesta tarefa.
Timer/base/FreshClam/serviços/configurações permanecem intocados.

### Proposta anterior à autorização da reprodução (registro histórico)

Preferir o **JSON original**, caso o proprietário o tenha preservado: exit_code,
signal, incomplete_reason e trechos de engine_output sobre mmap/alocação/IO,
sem nome/caminho completo. Não há API/histórico para recuperá-lo do app atual.

Sem esse registro, confirmação específica requer uma nova execução autorizada,
não simples aumento de limites. **Até esse ponto do diagnóstico não havia reprodução grande autorizada ou
executada; a autorização e o resultado posteriores estão registrados abaixo.** Se o proprietário optar por ela, a proposta é uma
única chamada da CLI existente sobre o arquivo original, sem mudar parâmetros:

```sh
# PROPOSTA APENAS — NÃO EXECUTADA; requer autorização prévia.
# O proprietário escolheria o caminho localmente, sem publicá-lo.
MYCT_AV_PATH_HEX=$(python3 -c 'import sys; print(sys.argv[1].encode().hex())' "$MYCT_AV_TARGET")
~/.local/bin/myct-av scan "$MYCT_AV_PATH_HEX"
```

Finalidade: capturar JSON bruto localmente e amostrar `/proc` do filho (VmSize,
VmPeak, VmRSS e limites), mais espaço/cota/erro temporário. Publicar somente os
campos de resultado, métricas e mensagens reconhecidas/sanitizadas, sem hash ou
caminho privado. Custo esperado: cópia temporária de ~1,79 GB, VM até 2 GiB,
RSS possivelmente próximo de 1 GiB como antes, até 120 s CPU / 180 s de worker
(190 s adaptador); pode falhar novamente. Não é garantia de custo físico exato.
Comando exato de captura/monitoramento deve ser revisado com a autorização,
antes de qualquer execução. Alternativamente, manter a limitação documentada e
usar análise sob demanda nos arquivos que caibam nos recursos atuais.

R-09/issue #9 não foi alterada. PR #21 continua sem merge e issue #18 aberta.

## Reprodução única autorizada — falha de reserva virtual confirmada

O proprietário autorizou expressamente **uma única** reprodução monitorada do
mesmo MKV de 1.788.118.547 bytes, sem alteração de parâmetros/componentes. Foi
feita uma chamada à **CLI instalada existente**, com argumento hexadecimal e sem
repetição. O adaptador instalado ainda é o anterior à melhoria de mensagem do
commit `9050cee`; o worker instalado coincide com o binário já aprovado.

### Pré-checagem e preservação

- Um único arquivo regular MKV com esse tamanho foi localizado por metadados,
  sem seguir symlinks de diretórios nem expor nome/caminho.
- ~**151,88 GB** disponíveis no filesystem temporário, mais de 6 GiB de RAM
  disponível; nenhum ClamScan/FreshClam/worker de análise ativo.
- Serviço de update `inactive/dead`, lock de trabalho livre; nenhuma chamada de
  atualização foi feita. A CLI adquiriu seu lock normalmente.
- Nenhum componente, limite, permissão ou unidade foi instalado/modificado.
  A verificação usou stat/xattrs; **nenhum hash do vídeo foi calculado**.

Um monitor externo somente de leitura amostrou os descendentes da CLI em `/proc`
a cada ~200 ms. Capturou stdout/stderr localmente e publicou só campos e trechos
sanitizados. Não havia arquivo adicional com conteúdo do vídeo criado pelo
monitor; somente o temporário nativo inevitável do ClamScan. Um marcador local
impediu lançamento acidental de segunda reprodução. O ponteiro temporário com o
caminho privado foi descartado após o teste.

### Resultado efetivo da única execução

```json
{
  "state": "ERROR",
  "exit_code": 2,
  "signal": 0,
  "incomplete_reason": "",
  "duration_ms": 17959,
  "peak_rss_kib": 984616,
  "cpu_user_s": 10.2795,
  "cpu_system_s": 3.4225,
  "cli_status": 1
}
```

Trechos pertinentes do motor, sem caminho privado:

```text
LibClamAV Warning: fmap: map allocation failed
LibClamAV Error: CRITICAL: fmap() failed
stdin: Can't allocate memory ERROR
Total errors: 1
Data scanned: 0.00 MB
Data read: 1705.28 MB (ratio 0.00:1)
```

O número `Scanned files: 1` da saída do ClamAV **não significa análise completa**:
o contador foi incrementado, mas o mapeamento falhou antes da varredura de
conteúdo. Não há resultado de ausência de ameaça para esse arquivo.

| Medição | Resultado |
| --- | --- |
| Parede do motor / chamada monitorada | 17,959 s / 18,022 s |
| CPU total | 13,702 s (user + system) |
| RSS high water do filho por wait4 | 984.616 KiB / **961,54 MiB** |
| Pico VmSize amostrado | 1.017.784 KiB |
| VmPeak informado pelo kernel | 1.029.452 KiB |
| VmSize estável imediatamente antes da falha | **1.016.460 KiB / 1.040.855.040 bytes** |
| Pico PSS amostrado | 967.417 KiB / 944,74 MiB |
| VmSwap do ClamScan nas amostras | 0 KiB |
| Amostras do processo | 86 |
| Maior espaço temporário alocado observado | ~1,76 GB |
| Espaço temporário disponível após terminar | ~151,88 GB |

A amostragem pode perder picos; o pico temporário amostrado não representa o
volume exato de IO nem prova que o temporário máximo ficou abaixo do tamanho da
entrada. O ClamAV reportou leitura de toda a entrada, e não houve erro de escrita.
As diferenças entre ~70,57 s na execução original e ~18 s nesta não são tratadas
como benchmark ou correção: cache/IO e condições anteriores não foram controlados.
Ambas falharam; não foi realizada outra tentativa.

Limites lidos **do processo ClamScan durante a execução**, soft e hard:

```text
Max cpu time       120         120         seconds
Max file size      2000000000  2000000000  bytes
Max address space  2147483648  2147483648  bytes
```

O timeout do worker permaneceu 180 s; nada foi ampliado. Todos os metadados
comparados do original permaneceram iguais: dispositivo/inode, tamanho, modo,
uid/gid, atime/mtime/ctime, blocos alocados e valores de atributos estendidos.
Preservação decorre também da abertura read-only/O_NOATIME e ausência de ação de
escrita sobre o alvo; não se reivindica uma comparação de conteúdo por hash.
O diretório temporário de scan foi removido, nenhum scanner permaneceu ativo,
Core e Transmission continuaram ativos. Timer permaneceu `active/enabled` e os
hashes das duas unidades são os mesmos já registrados neste relatório.

### Diagnóstico confirmado para a reprodução

O motor **falhou ao reservar o mapa virtual do arquivo**. Imediatamente antes,
usava ~1,04 GB de VM. Somar o mapa de no mínimo 1.788.118.547 bytes exigiria
**2.828.973.587 bytes (~2,635 GiB)**, antes de arredondamento/bitmap/outros custos:
**pelo menos ~650 MiB acima do RLIMIT_AS de 2 GiB**. Esse teto impede a reserva.

Uma alocação recusada não aumenta VmSize/VmPeak até o tamanho solicitado; por isso
os indicadores observados podem continuar próximos de 1 GiB. RSS também pode
permanecer ~962 MiB enquanto a reserva virtual falha. Não é necessário esgotar a
RAM física do Fedora para que ENOMEM ocorra nesse limite do processo.

Nesta reprodução não foi timeout, término por sinal, teto simples de entrada,
falta de espaço temporário ou indicação de malware: houve erro explícito de
alocação/fmap, `exit_code=2`, `signal=0`, motivo parcial vazio e orçamento virtual
insuficiente para a reserva necessária. Não se descartam outros erros que possam
aparecer em tentativas futuras com parâmetros diferentes. A reprodução explica
o mesmo sintoma relatado no Android; não recupera retroativamente o JSON perdido
da primeira tentativa de ~70 s.

### Proposta proporcional — não aplicada

1. **Ação mínima mantendo os recursos atuais:** conservar os limites e tratar
   esse tamanho como inviável para análise completa neste orçamento/base. A
   melhoria de mensagem já revisável no commit `9050cee` permite que a mesma
   versão Android mostre falha de memória, sem inventar resultado. Para usá-la,
   atualizar somente o adaptador Python dependerá de autorização própria;
   nenhum worker/APK precisa mudar para essa mensagem. Isso **não** torna o MKV
   analisável nem transforma ERROR em CLEAN/INCOMPLETE para aparentar sucesso.
2. **Se analisar esse porte for uma necessidade real:** candidato a avaliar em
   nova autorização é separar orçamento virtual de proteção de RAM: AS de
   **3 GiB** é uma hipótese de ajuste baseada no mínimo medido de ~2,635 GiB,
   com uma proteção independente de memória do grupo da análise (por exemplo,
   teto de **2 GiB via cgroup**, que inclui mais que RSS). CPU 120 s, parede
   180 s, entrada/temporários e expansão permaneceriam limitados. Esse controle
   teria de ser específico à análise, sem alterar serviços existentes/timer nem
   criar daemon. Não foi implementado nem validado; page cache, PCRE e parsers
   podem ainda provocar reclaim, outra falha ou limite de tempo. Aumentar AS
   sozinho não garante sucesso nem preserva sozinho o teto físico desejado.

**Recomendação agora:** manter a configuração aprovada e revisar o diagnóstico;
só iniciar mudança de orçamento/executor se o proprietário optar por suportar
esse porte e autorizar um ciclo separado. Não escolher um novo teto universal de
entrada pela medição de um único arquivo: o custo das assinaturas/parsers varia.

Nenhuma correção de limites/recursos, instalação, nova compilação ou novo APK foi
feita. Nenhuma segunda reprodução foi executada ou autorizada. Não repetir scans
ou provas físicas já aceitas. PR #21 permanece aberto sem merge; issue #18 aberta.


## Fechamento e aceite expresso da MYCT-5D-B2 — 16/10/2026

Após revisão do diagnóstico da execução monitorada, o proprietário **autorizou expressamente** o fechamento da MYCT-5D-B2, a atualização **manual** do adaptador `myct-av` com backup e o merge do PR #21. O aceite refere-se à entrega funcional observada no Moto G06, mantendo a limitação de arquivos grandes, e não à capacidade de examinar integralmente qualquer arquivo dentro do teto de entrada.

**Evidência de uso real no Moto G06 (APK 0.5.6/code 13):**
- Inspeção de PDF com tamanho, MIME e SHA-256; varredura ClamAV pontual concluída em CLEAN, sem qualquer afirmação de segurança absoluta.
- Cancelamento de análise apresentando INCOMPLETE corretamente.
- Opções exibindo ClamAV disponível, daily 28155 e status da base; atualização manual concluída em SUCCESS e registro de último sucesso avançando de 16:07 para 17:19.
- Não houve prova física adicional de EICAR no Android nesta B2; os testes EICAR reais foram realizados no Fedora durante a B1.

**Limite identificado:** o MKV de 1.788.118.547 bytes terminou em ERROR. Reprodução **única** e autorizada confirmou `fmap: map allocation failed`, `exit_code=2`, `signal=0`, ~961,54 MiB RSS, com pelo menos ~2,635 GiB de reserva virtual exigida contra `RLIMIT_AS=2 GiB`. Não houve detecção ou resultado CLEAN nesse arquivo. O teto de entrada de 2 GB é de **elegibilidade**, não garantia de inspeção completa. Não ampliar memória/CPU/tempo nem repetir o MKV por consequência do aceite.

**Adaptação de mensagem:** o commit `9050cee` no PR #21 inclui diagnósticos sanitizados em `ops/myct-av`, com 12/12 testes direcionados PASS. **O script atualizado ainda NÃO foi instalado no Fedora pela assistente:** depende de execução manual do proprietário, autorizada agora, com backup e sem sudo. O worker C++20, o APK 0.5.6, o FreshClam, a base e o timer systemd de 36 horas não precisam ser reinstalados/reconfigurados. A troca do script melhora a mensagem futura de ERROR; não faz o vídeo tornar-se analisável.

**Pendências intencionalmente separadas:**
- Apresentação extensa do diálogo e caminhos/nomes grandes: [issue #9, R-09](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), MYCT-R, não bloqueia este aceite.
- Avaliação futura, **somente mediante novo pedido e autorização**, de VM maior (hipótese 3 GiB) com proteção independente do uso de RAM (ex.: cgroup específico), sem garantia de que a análise completa funcionará.
- Não ampliar escopo para automação, varredura em background ou quarentena.

**Decisão e resultado:** MYCT-5D-B2 aceita e PR #21 integrado à `main` no merge `ed593cffe798e01177c249b9acffc700184a455a`. Issue #18 encerrada como concluída. O registro da instalação manual do adaptador no Fedora permanece pendente até o proprietário executar e informar o resultado. Registro de instalação manual do adaptador no Fedora permanecerá pendente até o proprietário executar e informar resultado. As instruções/histórico de “PR sem merge” acima se referem às etapas anteriores à autorização final.
