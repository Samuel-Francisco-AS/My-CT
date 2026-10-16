# MYCT-5D-B1 — ClamAV sob demanda no Fedora

16/10/2026 · Issue [#18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18).
Branch `myct-5d-b1-clamav`, criada da `main` limpa e sincronizada em
`6d78dbdbb279b3493a6de0e46367cbd1cffef57e` (após PR #19).
**Candidata para aceite do proprietário; sem merge. B2 não iniciada.**
APK permanece **0.5.5/code 12**, aceite 5D-A preservado.

## Aceite definido antes da implementação

Um arquivo regular autorizado sob o home → leitura somente por descritor →
ClamScan pontual com limites → JSON distinguindo detecção, ausência de detecção,
falha, indisponibilidade e inspeção incompleta. Conteúdo e metadados preservados.
FreshClam sem root, base própria, mesmo serviço oneshot para CLI/timer, sem
concorrência; intervalo de 36h após término, timer entregue **desabilitado**.
Medir no i7-3770/8 GB e manter os serviços existentes funcionando.

## Entrega e decisões

- `ops/myct-av`: adaptador operacional em Python stdlib já instalado no Fedora,
  para JSON, locks, metadados e systemd. Segue a exceção pequena usada em
  `myct-torrent`; não introduz dependência ou altera o backend principal.
- `server/av_scan.cpp`: worker **C++20**; executa `/usr/bin/clamscan` por argv
  fixo, sem shell e sem opções fornecidas pelo cliente.
- `server/safe_file.hpp`: extração da validação de caminhos da 5D-A, compartilhada
  por `myct-inspect` e pelo worker AV. `inspect.cpp` apenas passa a usar essa
  função; o helper 5D-A instalado não foi substituído.
- `ops/av/`: configuração mínima FreshClam, serviço/timer, instalador sem
  sobrescrita e roteiros opt-in de validação real.
- [Contrato CLI/JSON para B2](MYCT-5D-B1-CLI.md): quatro comandos públicos,
  cancelamento, estados, freshness, métricas e exemplos.

Home autorizado vem de `getpwuid`, não de `$HOME`. `openat2` com
`RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS` recusa symlinks finais/intermediários,
traversal, caminhos externos e tipos especiais. O AV exige **O_NOATIME**, sem
fallback: arquivos cujo proprietário/permissões não permitam essa abertura
serão recusados, mesmo que uma leitura comum fosse possível. O descritor aberto
é passado como stdin ao ClamScan, evitando nova resolução do caminho e mudanças
de atime. Tamanho, mtime, ctime e atime são conferidos ao terminar. Não é snapshot;
modificação concorrente invalida o resultado. Aguardar conclusão de downloads.

A análise não apaga, move, renomeia, altera conteúdo/permissões/proprietário,
isola, executa ou envia o alvo/hash para fora. Não há ação corretiva, alteração
de torrents/seeding nem chamada ao Transmission. A rede é utilizada somente
pelo atualizador para obter assinaturas oficiais. Temporários de inspeção ficam
em diretório privado por chamada e são descartados no encerramento normal,
cancelamento e falhas tratadas. SIGKILL/pane do host pode deixar temporários
internos abandonados; não há limpeza automática ampla do home.

Limites fixos: **64 MiB** de entrada, **128 MiB** expandidos por contêiner,
**1.000** entradas, **8** níveis de recursão, **180 s** totais incluindo carga,
**120 s** de CPU, **2 GiB** de espaço de endereçamento, **256 MiB** por arquivo
interno criado pelo filho e **64 KiB** de saída do motor. Nice +10. Um lock
exclusivo impede cargas simultâneas de análise/atualização. O timeout interno
`max-scantime` do ClamAV é desativado: o timeout externo mata a execução e produz
INCOMPLETE, evitando aceitar um timeout interno silencioso como CLEAN.
Limites/criptografia/formato quebrado geram INCOMPLETE; detecções parciais são
preservadas no JSON. A ausência de alerta não certifica segurança nem suporte
integral a todo formato possível pelo ClamAV.

## Inventário observado

Fedora 44 real, i7-3770, RAM 7.819 MiB, g++ 16.2.1, Python 3.14.7,
systemd 259.9; pacotes `clamav-1.4.6-1.fc44.x86_64` e
`clamav-freshclam-1.4.6-1.fc44.x86_64` já instalados pelo proprietário.
ClamScan inicial usava base global daily 28091, de 13/08/2026.
`/etc/freshclam.conf` é root:root 0600, não legível por sam; FreshClam sem
configuração própria falhava no parsing. Esse arquivo e `/var/lib/clamav` foram
preservados. `Linger=yes` já existia; não foi modificado.
`clamd`, `clamonacc` e `clamav-freshclam.service` estavam e continuam inativos.
**Nenhum pacote adicional, sudo ou senha foi necessário.**

## Instalação/ativação

Nesta tarefa, a instalação inicial dos componentes B1 no espaço do usuário e a
primeira atualização foram executadas sob autorização expressa do proprietário.
O instalador foi também reexecutado e recusou destinos existentes sem sobrescrever.
Durante o desenvolvimento, somente as novas cópias B1 foram atualizadas de forma
explícita. Não foram reinstalados Core, `myct-inspect`, downloader ou hook.

Para uma instalação **nova**, revisar a branch/PR e executar:

```sh
make -C server myct-av-scan
python3 ops/av/install.py
~/.local/bin/myct-av status
~/.local/bin/myct-av update
~/.local/bin/myct-av update-status
```

O instalador recusa arquivos/configurações/unidades já existentes; não faz merge
nem chmod de diretórios preexistentes. Diretórios próprios precisam ser do usuário,
0700 e sem symlink final. Para atualizar uma instalação existente, revisar o diff
e substituir explicitamente só as cópias B1 desejadas, com backup prévio; não usar
um comando de instalação genérico sobre configurações pessoais. Checkout Git não
atualiza os executáveis instalados.

Arquivos exclusivos B1:

```text
~/.local/bin/myct-av
~/.local/bin/myct-av-scan
~/.config/myct-av/freshclam.conf
~/.config/systemd/user/myct-av-update.service
~/.config/systemd/user/myct-av-update.timer
~/.local/share/myct/clamav-db/   # main, daily, bytecode, dados internos FreshClam
~/.local/share/myct/av/          # locks, update.json, update.log, temporários
```

`freshclam --config-file=...` usa essa base gravável, sem modificar a base global.
`TestDatabases yes` verifica carregamento antes de substituir cada base. Não há
notify de clamd, hooks, modo daemon ou sudo. `myct-av update` solicita a unidade
oneshot e retorna; somente `update-status`, após término, confirma o sucesso real.
Falhas preservam `last_success`; logs mostram a causa. Assinaturas desatualizadas
não se tornam atuais só porque systemd aceitou o pedido.

Serviço: limite cgroup de 2 GiB, pressão suave em 1,5 GiB, quota de um núcleo,
Nice +10, IO idle, prazo total de 25 min incluindo espera pelo scanner; FreshClam
é limitado a 1.100 s. O processo termina e libera RAM. Um flock adicional à
serialização do systemd impede FreshClam simultâneo sobre a base e sobreposição
com carga de assinaturas pelo scanner.

**Timer instalado, inativo e desabilitado nesta entrega.** Somente após confirmação
do proprietário, ativar:

```sh
systemctl --user enable --now myct-av-update.timer
systemctl --user list-timers myct-av-update.timer
```

`OnStartupSec=5min` fornece execução inicial após iniciar o gerenciador de usuário;
se ele já tem mais de cinco minutos, a ativação pode disparar imediatamente.
`OnUnitInactiveSec=36h` conta do término/inativação do serviço, incluindo tentativa
falhada; `AccuracySec=1min` permite pequena margem do systemd. Não há processo
residente de AV: apenas o timer no gerenciador systemd já existente.

O intervalo é monotônico, **não persiste as horas desligado** nem recupera todas
as ocorrências perdidas. Após reboot, timer habilitado volta com o gerenciador
(linger já habilitado) e faz a execução inicial em ~5 min. A contagem de 36h
recomeça após essa execução. `update` permite retomada manual imediata.
Não houve reboot nem espera física de 36h; o comportamento acima decorre das
propriedades configuradas, verificadas por systemd, não de prova de longo prazo.

Para suspender futuramente apenas a agenda B1: `systemctl --user disable --now
myct-av-update.timer`. Para parar uma atualização B1: `systemctl --user stop
myct-av-update.service`; status será interrompido/erro. Não remover a base/dados
nem alterar outras unidades para desativar a agenda.

## Validação e recursos

Comandos executados:

```sh
make -C server test
python3 -m unittest discover -s ops/tests -v
python3 ops/av/validate.py
python3 ops/av/validate_concurrency.py
systemd-analyze --user verify ops/av/myct-av-update.service ops/av/myct-av-update.timer
systemctl --user show myct-av-update.timer -p ActiveState -p TimersMonotonic
systemctl --user is-enabled myct-av-update.timer
git diff --check
```

- Servidor **18/18 PASS**, incluindo os sete testes existentes de inspeção após
  extrair a validação compartilhada. Nenhuma prova Moto já aceita foi repetida.
- Operacionais **22/22 PASS**: 13 existentes + nove novos. Classificação dos
  estados, falha de carregamento, rede simulada (exit 52), freshness, metadata,
  exclusão por lock e propriedades das unidades. Esses testes são **simulados**;
  não comprovam indisponibilidade real de rede ou espera física do timer.
- Provas **reais no Fedora com ClamAV 1.4.6**: disponibilidade após download;
  benigno CLEAN; EICAR inofensivo DETECTED; ZIP com 1.002 entradas INCOMPLETE
  (`Heuristics.Limits.Exceeded.MaxFiles`); arquivo de 65 MiB recusado INCOMPLETE;
  ausente, sem permissão, nomes com espaços/acentos/metacaracteres, symlink
  final/intermediário, traversal, caminho externo, diretório e FIFO; EOF durante
  carga cancela apenas a análise e retorna INCOMPLETE.
- Fixtures comparadas antes/depois por SHA-256, inode, tamanho, modo, uid/gid,
  atime/mtime/ctime em nanossegundos e atributos estendidos. Todas preservadas;
  criação/chmod das próprias fixtures ocorre fora do intervalo medido da análise.
- Primeira atualização real **15:47:31–15:48:03 -03 (~32 s)**: FreshClam 0,
  download e teste das três bases; `daily` **28155**, 16/10/2026 06:24 UTC,
  `main` **63**, `bytecode` **339**. Base dedicada **108 MiB** no disco.
  ClamScan carregou essa base e reportou **3.628.169** assinaturas conhecidas.
  Atualização subsequente confirmou as bases já atuais e encerrou com sucesso.
- Concorrência real: **oito pedidos simultâneos durante uma análise** convergiram
  para uma invocação systemd; estado WAITING sem FreshClam durante a análise,
  depois uma única linha de início no log e SUCCESS. Não houve dois FreshClam.
  A amostragem por pgrep após a análise não capturou a execução muito curta
  (máximo amostrado 0); não é uma medição temporal perfeita. A invocação única,
  log único e lock exclusivo sustentam a exclusão mútua.
- Serviço terminou `inactive/success`, sem FreshClam/ClamScan persistentes; timer
  `disabled/inactive`, propriedades **1d 12h** e **5min** confirmadas pelo systemd.

Medição inicial real (base já baixada; cache de disco não foi descartado):

| Fixture | Total CLI | Motor incl. carga | Pico RSS | Pico PSS amostrado | CPU user + system |
| --- | --- | --- | --- | --- | --- |
| Benigna, 20 bytes | 10,92 s | 10,798 s | 984.732 KiB | 979.051 KiB | 9,806 + 0,500 s |
| EICAR | 11,23 s | 10,691 s | 984.608 KiB | 978.152 KiB | 9,795 + 0,514 s |
| ZIP / limite de entradas | 22,33 s | 22,206 s | 984.484 KiB | 973.881 KiB | 17,665 + 3,342 s |
| Cancelamento por EOF na carga | ~0,5 s | 0,456 s | 110.864 KiB | não amostrado | 0,406 + 0,048 s |

RSS vem de `wait4`; PSS é amostrado a cada ~100 ms por `/proc/*/smaps_rollup`,
podendo perder picos. Não é teste de taxa de disco frio ou benchmark geral.
Na repetição final, a entrada vazia levou **10,776 s** de CLI / **10,637 s**
de motor (984.480 KiB RSS, 9,856 + 0,477 s CPU), aproximação do custo de carga
e inicialização; não há API do ClamScan para separar exatamente esse tempo.
A mesma repetição confirmou benigno CLEAN (10,686 s), EICAR DETECTED
(10,662 s), ZIP INCOMPLETE (21,966 s) e cancelamento em 0,506 s, com as
comparações de conteúdo/metadados/valores dos xattrs aprovadas.
A análise benigna minúscula já mostra que a maior parte dos ~11 s é carga.
O runner interrompe validação se `MemAvailable` cair abaixo de 1 GiB.

Durante a análise: ~950 MiB RSS, **5.447 MiB disponíveis**, sem crescimento
observado de swap (3 MiB antes/depois). Core e Transmission permaneceram ativos;
Saúde respondeu `status: ok` durante a carga, com ~5,7 GB disponíveis. Após
execução, ~6.432 MiB disponíveis; Core ~2 MiB cgroup e Transmission ~384 MiB.
Não foi observada pressão de memória ou falha persistente. A medição não prova
throughput de torrents/latência SSH sob carga pesada; nenhum torrent ativo foi
usado como fixture. O custo de ~1 GiB e ~11 s por análise é explícito para decisão
do proprietário, sem instalar daemon para contorná-lo.

## Hipóteses corrigidas e limites de aceite

O primeiro teste simultâneo, executado durante uma análise, revelou recusa por
lock em vez de espera e status histórico ambíguo após falha de unidade. Hipótese:
o lock não bloqueante era adequado ao scanner, mas não ao pedido de atualização.
Correção: update espera pelo mesmo lock, expõe WAITING e status considera o
resultado efetivo do systemd; teste mínimo com oito pedidos confirmou o fluxo.
Uma expectativa do teste de entrada vazia foi ajustada: o ClamScan pode reportar
stdin vazio como inspecionado; o adaptador usa a contagem/resultado real do motor,
sem inferir ausência de inspeção apenas do tamanho zero.

Não foram feitos teste de timeout de 180 s completo, esgotamento deliberado de RAM,
reboot, espera de 36h, falha real de rede, ensaio de todos os formatos compactados
ou integração Android B2. Falhas/timeout são cobertos proporcionalmente por
classificação simulada e cancelamento real. Nenhum teste aguarda instalação de
pacotes; todos os pacotes necessários já estavam instalados pelo proprietário.
**Pendências para aceite:** revisão do proprietário sobre utilidade/consumo e
confirmação separada para ativação permanente do timer. Manter PR sem merge até
sua validação. Não iniciar B2 por consequência desta entrega.

Referências técnicas: [configuração FreshClam](https://docs.clamav.net/manual/Usage/Configuration.html),
[gestão de assinaturas](https://docs.clamav.net/manual/Usage/SignatureManagement.html),
`man clamscan`, `man freshclam.conf`, `man systemd.timer` instalados no Fedora.
