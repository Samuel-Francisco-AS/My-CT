# MYCT-5C — BitTorrent/magnet por SSH

Data: 16/10/2026. Branch: `myct-5c-bittorrent`, criada de `origin/main`
`83e053737ff782ecf523bf31776fec9da55889dd` (PR #16).
**Implementação candidata; teste do motor real bloqueado por pacote ausente.
Sem aceite físico no Moto e sem merge.** Este relatório não encerra a MYCT-5C.

## Aceite definido antes do código

Adicionar magnet pelo Moto, consultar nome/estado/progresso/velocidades/peers,
pausar/retomar, remover somente da lista e acessar arquivos via SFTP.
Transferência pertence ao Fedora e deve continuar sem SSH/app. Polling só na
seção visível com SSH ativo, a cada 4 s; campos/botões estáveis durante consultas,
prioridade para toque manual e nenhuma repetição automática de mutações.
Prova Fedora e aceite Moto são gates distintos de build/testes simulados.

## Inventário e escolha

Fedora 44 x86_64, GCC 16.2.1, Python 3.14.7, Java 21.0.10; SDK 36 existente,
Gradle 8.14.3/AGP 8.13/Kotlin 2.2.20 existentes. Não foram instaladas dependências.
`transmission-daemon` e `transmission-remote` ausentes; pacotes daemon/CLI não
instalados, nenhuma unidade Transmission de sistema/usuário, 9091 sem listener.
Consulta Fedora ofereceu `transmission-daemon-4.1.3-1.fc44.x86_64` (também 4.1.1)
e `transmission-cli-4.1.3-1.fc44.x86_64`. O daemon tem aproximadamente 2,2 MiB
instalados **sem somar dependências**. Bibliotecas declaradas incluem libcurl,
libevent, OpenSSL, libdeflate, libminiupnpc, libnatpmp, libpsl e systemd.
A transação de instalação mostrará o custo efetivo.

Inventário inicial: 7.819 MiB RAM, 6.456 MiB disponíveis, swap sem uso,
132 GiB livres no filesystem do projeto. `loginctl show-user sam -p Linger`
retornou `Linger=yes`; não foi alterado. A consulta DNF encontrou aviso de chave
no repositório ChatGPT alheio; nenhuma importação ou alteração desse repo foi
feita. Os resultados dos pacotes vieram dos repositórios Fedora.

Transmission foi escolhido por ser headless e manter estado/resume próprios,
sem PostgreSQL extra. O contrato usa JSON-RPC 2.0 do **Transmission 4.1+**.
A [especificação oficial 4.1.3](https://github.com/transmission/transmission/blob/4.1.3/docs/rpc-spec.md)
documenta respostas estruturadas, hashes estáveis e negociação de sessão HTTP
409; não interpretar tabelas localizadas de `transmission-remote` evita parser
frágil e dispensa instalar `transmission-cli`.

## Arquitetura entregue no código

`Downloads → Torrents → ChannelExec SSH → ~/.local/bin/myct-torrent →
127.0.0.1:19091/transmission/rpc → transmission-daemon`.

O adaptador `ops/myct-torrent` é Python com biblioteca padrão, executado por
comando e encerrado em seguida. Exceção pequena de linguagem registrada em
docs/05: evita adicionar biblioteca JSON/HTTP C++ ou dependência Python de
terceiros para um wrapper operacional. Core e motor HTTP C++20 permanecem
inalterados. Não há API nova no Core, proxy público nem processo Python residente.

Instância dedicada ao usuário com configuração em `~/.config/myct-transmission`,
supervisionada por `myct-transmission.service`. RPC somente IPv4 loopback,
whitelists de endereço/host preservadas; sem autenticação RPC adicional neste
servidor pessoal. Processos locais também podem acessar esse RPC: loopback não
é isolamento entre usuários. SSH continua autenticado com identidade verificada.
Não encaminhar essa porta. UPnP/NAT-PMP desativados na instância proposta; sem
alteração em firewall/roteador, SSHD, Core instalado, PostgreSQL, Cofre ou Narys.
Peer traffic é administrado pelo Transmission, que pode abrir sua porta de peers;
nenhuma liberação de firewall é feita pelo My-CT.

Cada torrent usa `~/Downloads/My-CT/Torrents/HASH/`, separado dos UUIDs HTTP.
O hash evita colisão entre torrents diferentes com o mesmo nome. A CLI recusa
symlinks no destino e só lista/controla torrents no diretório do hash esperado.
Dados existentes desse mesmo torrent são deixados para a verificação/retomada do
Transmission; nenhuma limpeza ou exclusão é feita pela CLI.

Comandos:

```sh
~/.local/bin/myct-torrent add 'magnet:?xt=urn:btih:HASH_REAL'
~/.local/bin/myct-torrent list
~/.local/bin/myct-torrent status HASH_REAL
~/.local/bin/myct-torrent pause HASH_REAL
~/.local/bin/myct-torrent resume HASH_REAL
~/.local/bin/myct-torrent remove HASH_REAL
```

`HASH_REAL` é placeholder: substituir pelo hash hexadecimal de 40 caracteres.
Controles usam hash imutável, nunca o ID numérico mutável após reinício.
Inclusão informa ID/hash e `duplicate` explicitamente; duplicata não é reiniciada
nem alterada silenciosamente. Remoção envia sempre `delete_local_data=false`.
Mutações não são repetidas após falha/timeout. Somente 409 anterior à execução
permite reenviar o pedido na negociação CSRF, com limite de uma tentativa extra.

Magnet v1 hexadecimal/base32, limitado a 8.192 caracteres; aceita `xt`, `dn` e
`tr`. Trackers precisam ser HTTP/HTTPS/UDP públicos na resolução inicial;
credenciais, fontes `xs/as/ws`, controles e outras opções são recusados. O motor
resolve DNS e peers independentemente: essa checagem **não é sandbox de rede**
nem impede rebind posterior ou destinos originados nos metadados/peers. Use
conteúdo confiável. Não confundir essa política com a fixação DNS do motor HTTP.
A CLI não aceita caminhos `.torrent`, URLs HTTP de metadados nem destino arbitrário.
A biblioteca Transmission mantém responsabilidade pelo protocolo e pelos caminhos
internos dos metadados; My-CT não implementa BitTorrent.

Android mostra estados 0–6, nome, ID, total e progresso quando há metadados,
velocidades download/upload, peers, pasta e erro do motor. Metadados pendentes
mostram tamanho/progresso indisponíveis. Aviso visível: **concluído pode continuar
compartilhando**; Pausar interrompe o torrent/seeding. Limites de banda, ratio e
seeding do motor não são alterados pela CLI nem pela configuração proposta.
Botão Remover abre confirmação de preservação dos dados. Ver em Arquivos utiliza
`FilesModel.load` existente e aguarda disponibilidade do SFTP.

Mesmos canais exec independentes/limites MYCT-5B: prazo 25 s incluindo connect,
256 KiB por saída, fechamento do canal sem fechar PTY/sessão. Consultas RPC têm
7 s por conexão. Saída inválida marca dados antigos como não confirmados.
Seção HTTP/HTTPS conserva formulário/modelo/comandos; somente o transporte exec
limitado e a abertura de canal foram extraídos para reutilização.

## Instalação e ativação — pendentes, somente com autorização

Foi pedido ao proprietário instalar o pacote mínimo:

```sh
sudo dnf install transmission-daemon
```

Depois, **com autorização explícita para preparar/ativar esta nova unidade**:

```sh
cd ~/Projetos/My-CT
python3 ops/torrent/prepare.py
systemctl --user daemon-reload
systemctl --user enable --now myct-transmission.service
systemctl --user status myct-transmission.service --no-pager
ss -ltnp '( sport = :19091 )'
~/.local/bin/myct-torrent list
```

O preparo copia CLI/unidade e grava configuração privada; recusa sobrescrever
qualquer arquivo instalado. Ele não instala pacotes nem inicia serviços.
`git pull` não atualiza o adaptador instalado. Atualizações posteriores do wrapper:
revisar diff e, quando autorizado, `install -Dm0700 ops/myct-torrent
"$HOME/.local/bin/myct-torrent"`. Não substituir settings gerados pelo daemon.

Reversão não destrutiva, pelo proprietário:

```sh
systemctl --user disable --now myct-transmission.service
```

Isso para downloads/seeding; mantém configuração, resume e arquivos.
Não é necessário remover pacote, apagar diretórios ou tocar em serviços antigos.
Para retomar: `systemctl --user enable --now myct-transmission.service`.
Com enable + linger existente, espera-se início no boot e sobrevida ao logout;
**não comprovado com esse motor nesta entrega**. Nenhum reboot foi executado.

## Validação

Resultados finais de build/testes e artefatos registrados na seção final.
Testes Python usam doubles RPC e servidor HTTP **simulado em loopback**, não
Transmission. Testes JVM usam transporte simulado; não são provas de foco/IME
no aparelho. Testes HTTP existentes usam curl/systemd reais em HOME temporário.
A execução real de `ops/myct-torrent list` sem motor retornou código 3 e mensagem
controlada. `systemd-analyze --user verify` apontou executável Transmission
ausente; a unidade ainda não foi validada com o pacote presente.

Antes de qualquer nova sequência de correções: registrar hipótese, teste mínimo
e resultado. O primeiro teste JVM de quoting usou magnet com caracteres que
`java.net.URI` recusa antes do shell; corrigiu-se a fixture para uma URI válida
contendo aspas/`$(id)`, mantendo verificação dos argumentos literais. Dois builds
acabaram sobrepostos e compartilharam log intermediário; foram aguardados antes
da execução final isolada, cujo log é `android-final.log`.

## Aceite Fedora e Moto G06 — não executado

1. Concluir instalação/ativação autorizadas. Verificar versão, estado da unidade
   e RPC escutando exclusivamente `127.0.0.1:19091`, nunca wildcard/LAN.
2. Usar magnet **legal e confiável, de arquivo pequeno**. Sem fixture pública
   com peers comprovados nesta execução; escolher e registrar origem e tamanho
   antes de iniciar. Não usar ISO volumosa como teste obrigatório.
3. Fedora: adicionar, registrar ID/hash, repetir inclusão para conferir duplicate,
   consultar estado/bytes reais; pausar e confirmar status 0; retomar e consultar.
   Se não houver peers/metadados, registrar exatamente isso, sem alegar conclusão.
4. Pelo Terminal SSH do Moto, iniciar/consultar e sair do SSH/fechar o aplicativo
   durante transferência. Reconectar depois, comparar bytes/estado e hash do
   conteúdo esperado. Comprovar continuidade, não apenas presença na lista.
5. Instalar o APK sobre 0.5.2, sem desinstalar. Conectar pelo Terminal, abrir
   Downloads → Torrents, colar magnet e Adicionar. Conferir nome, estado,
   progresso e velocidades; repetir Pausar/Retomar pela seção Android.
6. Digitar durante pelo menos 16 s: foco/IME e botões devem permanecer estáveis.
   Tocar uma ação durante polling deve produzir efeito uma vez ou erro claro.
   Alternar seções/abas, desconectar e reconectar sem repetição de mutações.
7. Fechar o app durante download, retornar/conectar e conferir estado real.
   Ver em Arquivos deve abrir a pasta HASH; acessar arquivo usando SFTP existente.
8. Remover e conferir que desapareceu da lista e que os arquivos permaneceram.
   Registrar versão, magnet não sensível, ID/hash, estados, bytes e resultados.

O aceite principal exige Fedora executando o download sem Android/SSH. Nenhum
resultado JVM/build/mocks substitui isso. Não reabrir as dívidas HTTP/SFTP/terminal
da issue #9 nem repetir gates MYCT-4/4B/5A já aceitos.

## Limitações e retomada

- Motor ausente e ativação pendente impedem prova de magnet/progresso real,
  pausa/retomada real, remoção real e independência do SSH. Não declarar pronto.
- `.torrent` via SAF/SFTP fica complementar pendente: seleção/confirmação atual
  está acoplada a FilesModel e requer integração adicional. Não foi implementado.
- Sem quota nova, limpeza, indexação, busca, streaming, agendamento ou migração
  dos metadados Transmission para PostgreSQL. Monitorar espaço em Saúde.
- Sem teste físico Moto, teste de reboot ou benchmark de memória do daemon.
  Medir idle/transferência reais após instalação, em vez de inferir RAM pelo RPM.
- Se o diretório/arquivo for alterado externamente, o motor pode precisar de
  verificação; não mover/apagar automaticamente para corrigir uma falha.
- Toda validação e ativação pendente deve atualizar este relatório e o PR draft.

Arquivos: `ops/myct-torrent`, `ops/torrent/{prepare.py,myct-transmission.service}`,
`ops/tests/test_torrent.py`, Android `Torrents{,Model,Screen}.kt` e testes;
extração pequena do exec Downloads/SSH, vínculo da sessão, versão e documentação.

## Resultados finais da candidata 0.5.3

- Android: `67` testes contabilizados, **66 PASS / 1 opcional ignorado**,
  zero falhas/erros. Oito testes adicionais de Torrents: estados/metadados,
  quoting real em `/bin/sh`, erros de CLI/motor/SSH, reconexão, hide/desconexão,
  quatro ciclos passivos e prioridade de ações sem duplicação.
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain`:
  aprovado. Lint **0 erros / 6 avisos existentes / 1 hint**. Sem novas
  dependências Android; Gradle manteve heap 1.536 MiB/metaspace 512 MiB/2 workers.
- `python3 -m unittest discover -s ops/tests -v`: **10/10 PASS** (6 Cofre offline,
  4 adaptador Torrent), sem acessar segredo/cofre real.
- `make -C server test`: **11/11 PASS**. Nenhuma mudança em código do servidor.
- `python3 android/terminal/upstream/verify.py`: **21 arquivos verificados**, dois
  patches documentados preservados. `git diff --check`: aprovado.
- ADB: nenhum aparelho. **Nenhuma prova real Transmission, SSH/app Torrent,
  independência ou reboot** nesta execução; pacote/ativação continuam pendentes.

APK: `artifacts/myct-5c-0.5.3-debug.apk`, pacote `dev.myct`,
**0.5.3-torrents / versionCode 10**, 13,749,007 bytes.
SHA-256: `3686101a5e18d49a33ec1eb8db1e76d6b0eadb0a26dafd003526c3e64c73fabf`.
`apksigner verify --print-certs` confirmou assinatura SHA-256
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`, igual à
0.5.2. Atualizar sem desinstalar; não foi instalado no Moto nesta execução.

```sh
(cd artifacts && sha256sum -c myct-5c-0.5.3-debug.apk.sha256)
# Com aparelho conectado e ADB autorizado pelo proprietário:
adb install -r artifacts/myct-5c-0.5.3-debug.apk
```

Logs/JUnit/lint/medidas locais em `artifacts/myct-5c-validation/`, fora do Git.

Medidas aproximadas desta execução: build/testes/lint Android final 95,07 s,
CPU acumulada 449,51 s e RSS máximo 1.800.524 KiB (~1,72 GiB), sem swap;
servidor 47,29 s e RSS máximo 326.720 KiB (~319 MiB), sem swap. Gradle avisou
sobre metaspace ao encerrar, mas terminou com sucesso, sem elevar limites.
CLI no caso real **motor ausente**: 0,15 s, RSS 17.696 KiB (~17,3 MiB), saída 3.
Isso não mede o daemon, o app no Moto nem todos os filhos systemd dos testes.

## Publicação e referência de retomada

- Implementação/APK: commit `5800d79` (hash completo consultável pelo tag abaixo).
- [PR #17 em rascunho](https://github.com/Samuel-Francisco-AS/My-CT/pull/17),
  base `main`, branch `myct-5c-bittorrent`. **Sem merge.**
- [Prévia técnica 0.5.3 e checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5c-v0.5.3-rc1).
  Tag identifica o commit da implementação testada. Release identificada como
  candidata com motor real/aceite pendentes, não como entrega funcional aceita.
- Commits posteriores podem apenas completar este relatório. Para obter o SHA
  final da documentação: `gh pr view 17 --repo Samuel-Francisco-AS/My-CT
  --json headRefOid`. O histórico do PR registra todos os commits sem autorreferência.

Retomada imediata: instalar pacote pelo proprietário, autorizar preparo/ativação
da unidade proposta, executar gate Fedora pequeno e só então gate físico Moto.
Não tentar contornar essa dependência com pacote extraído, sudo automatizado ou
serviço substituto. Atualizar relatório/PR com resultados reais antes de declarar
MYCT-5C pronta para aceite. Questões de instalação/ativação foram apresentadas
na sessão; nenhuma autorização explícita recebida até esta publicação.
