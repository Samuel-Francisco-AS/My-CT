# MYCT-5C — BitTorrent/magnet por SSH

## Pós-aceite — correção pontual DNS de trackers (17/10/2026; aguardando instalação e reteste)

No Moto G06, 2 de 3 magnets deram "Transmission indisponível", embora o serviço de usuário estivesse `active`/`enabled` e a CLI `myct-torrent list` respondesse com exit 0 via RPC local em `127.0.0.1:19091`. No magnet investigado, 4 de 27 trackers falharam na resolução DNS. A causa foi localizada na validação `magnet()`: `socket.gaierror` escapava como `OSError` e virava o código 3 (serviço indisponível).

Correção na branch `fix/torrent-dns-trackers`: ignorar **somente os trackers cujo DNS não resolve**, antes do `torrent_add`; preservar `xt`, `dn`, ordem e demais trackers. Endereços privados/não públicos resolvidos continuam rejeitados; protocolos inválidos e credenciais continuam proibidos. Se todos os trackers estiverem indisponíveis, o magnet sem `tr` ainda poderá ser entregue ao Transmission, sem garantia de descoberta via DHT. Esta filtragem não fornece isolamento de rede contra DNS rebinding ou metadados maliciosos.

Adicionados testes de filtragem, encaminhamento da URI ao RPC e manutenção das rejeições. **Não houve instalação em `~/.local/bin`, reinício do daemon ou alteração do APK.** Exige execução dos testes no Fedora, atualização explícita da CLI e aceite com um magnet anteriormente recusado. Não declarar corrigido em produção antes disso.

---

## Encerramento — aceite físico do proprietário (16/10/2026)

**MYCT-5C ACEITA no Fedora 44 e Moto G06.** PR [#17](https://github.com/Samuel-Francisco-AS/My-CT/pull/17), candidata final **0.5.4-torrents / code 11**. Este fechamento é posterior às seções históricas abaixo e substitui qualquer anotação anterior de "teste pendente". **Merge realizado:** [PR #17](https://github.com/Samuel-Francisco-AS/My-CT/pull/17) integrado à `main` em `30d909be5c4ba17dbf46c2008c42448c3c08b9c9`. As branches de trabalho podem ser removidas pelo proprietário após sincronizar o Fedora, conforme bloco Git fornecido. A exclusão das branches não remove arquivos, serviço, seeding, hook ou registros do Transmission.

**Provas físicas relatadas pelo proprietário na conversa, e consolidadas no PR:**
1. Transmission 4.1.3 dedicado ao usuário, serviço systemd ativo, RPC `127.0.0.1:19091`, CLI real `~/.local/bin/myct-torrent` consultando o daemon.
2. Magnet Tiny Core Linux: download real de **18.616.320 bytes**, metadados 100%, arquivo ISO no Fedora e `status:6`, sem erro. Pausa efetivamente `status:0` após atualização assíncrona, retomada e controles Android funcionando.
3. Android 0.5.3: listagem, pausa/retomada, **Ver em Arquivos** para o diretório SFTP correto; **Remover** da lista mantendo o arquivo no disco.
4. Outro torrent escolhido pelo proprietário foi iniciado pelo app; ele encerrou o SSH e, após reconectar, observou **continuidade do download sem a conexão Android** (sem coleta formal de série temporal ou taxa de bytes).
5. Correção 0.5.4 (commit `10950f7`): hook de conclusão lê **título real dos metadados** e pede ao motor relocation via RPC para `<título-sanitizado>-<hash8>`. Teste controlado de 65.536 bytes no Transmission real comprovou conteúdo íntegro, pasta legível, seeding disponível e arquivos/torrents anteriores intactos. **O proprietário confirmou no Moto G06 que, ao concluir um novo download, a pasta recebeu corretamente o nome legível.** Este era o último gate pendente; foi aprovado.
6. Build Android 0.5.4: **67 PASS, 1 ignorado**, lint sem erros; operacionais Python **13/13 PASS**. Não confundir testes automatizados com as evidências físicas acima. APK/sha256 na [release 0.5.4](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5c-v0.5.4-rc1).

**Entregue:** download e seeding gerenciados pelo Fedora sem exigir SSH/app conectado; magnet, progresso/peers/taxa, controles e acesso por SFTP; pastas legíveis para novos torrents ao concluir; legados preservados; nenhuma alteração de limites de upload/ratio ou arquivos internos.

**Pendências não bloqueantes:** importar arquivo `.torrent` pelo SAF (somente magnet via Android nesta fase); provar upload efetivo para peer externo (estado 6 não mede bytes enviados); reinício/reboot específico do Transmission/hook não comprovado; conferir persistência da ativação do hook caso o daemon seja reiniciado abruptamente; dívidas anteriores de UX/HTTP/SFTP na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). A [issue #4](https://github.com/Samuel-Francisco-AS/My-CT/issues/4) permanece aberta porque também cobre a primeira automação do servidor.

**Próxima fase autorizada, não iniciada:** [MYCT-5D — inspeção e segurança de arquivos, issue #18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18) e [plano](MYCT-5D-PLANEJAMENTO-VERIFICACAO-ARQUIVOS.md), começando por formato/tamanho/SHA-256 antes de avaliar ClamAV com autorização. Refinamento Android MYCT-R e automações MYCT-6 permanecem posteriores.

**Operação:** o Git não reinstala CLI/hook: as cópias no Fedora já foram atualizadas pelo executor na correção 0.5.4. Não desinstalar/transplantar cópias antigas nem reiniciar motor apenas para fechar o PR. Dados de seeding, inclusive pastas hash antigas, ficam onde estão. Iniciar próxima fase em branch nova após sincronizar `main`.

---

## Correção 0.5.4 — nomes reais, sem migrar existentes (16/10/2026)

**Estado atual:** os gates essenciais MYCT-5C foram comprovados pelo proprietário
após a publicação inicial: motor real, adição pelo Moto, download sem SSH,
consulta, pausa/retomada, SFTP e remoção preservando arquivos. Fontes:
[comentários e decisão de nomes no PR #17](https://github.com/Samuel-Francisco-AS/My-CT/pull/17#issuecomment-6218182578)
(ver também os demais comentários do PR). A correção de nomes descrita abaixo
foi comprovada no Fedora; **o proprietário confirmou o nome legível no Moto G06 com APK 0.5.4**.
O merge é tratado no encerramento administrativo do PR #17. O registro inicial ao final é histórico:
Transmission não está mais ausente.

### Aceite e solução mínima

Antes de implementar: somente torrents novos, título real confirmado pelo motor,
sufixo curto, nada de renomear arquivos internos, preservar localização dos
existentes e seeding, nenhuma dependência do Android e nenhum serviço novo.

A opção adotada é **finalizar o nome ao concluir o download**, quando os metadados
já estão completos. Antes disso a pasta permanece por hash. Isso evita um worker
residente, timer ou polling adicional para esperar metadados; é uma escolha
explícita de simplicidade. Não usa `dn` como título final.

O hook nativo de conclusão `myct-torrent-done` chama `myct-torrent organize HASH`.
A CLI consulta `torrent_get.name` e `metadata_percent_complete == 1` +
`left_until_done == 0`, calcula `<título-sanitizado>-<hash8>` e envia
`torrent_set_location` com `move:true`. Não há `mv`, rename manual dos dados ou
`torrent_rename_path`. Somente o Transmission move os dados e atualiza referências;
a árvore relativa e o infohash permanecem iguais. O seeding não é pausado pela
nomeação, nem seus limites alterados. Referências oficiais:
[hook de conclusão](https://github.com/transmission/transmission/blob/4.1.3/docs/Scripts.md#on-torrent-completion),
[RPC de localização](https://github.com/transmission/transmission/blob/4.1.3/docs/rpc-spec.md#36-moving-a-torrent).

Unicode NFC útil preservado; separadores, controles, bidi e caracteres especiais
viram hífen, nomes reservados recebem prefixo, títulos vazios recebem `torrent`.
Título limitado a 120 bytes UTF-8, mais sufixo; caracteres Unicode suplementares
também aceitos pelo Android. Destinos precisam ser filhos diretos de Torrents,
sem symlinks. A pasta final é reservada vazia com mkdir exclusivo; se já existir,
**não mescla nem substitui**: registra `blocked` e conserva a pasta original.
Hash8 não é garantia matemática contra colisão: qualquer colisão é recusada.

Registro privado em `~/.local/share/myct/torrent-names/HASH.json` só é criado para
inclusão realmente nova, sem diretório anterior e sem registro anterior. Adiciona
pausado, grava elegibilidade e só então inicia: um arquivo pequeno não conclui
antes do registro. Duplicatas não são reiniciadas nem se tornam elegíveis.
Torrent/pasta anterior sem registro **nunca é migrado**, mesmo se o hook receber
um evento de conclusão dele. Readição de torrent com registro mantém o destino
registrado, sem inventar uma nova pasta por `dn`.

`records()` continua aceitando `root/HASH` e aceita o caminho legível **somente
quando corresponde ao registro daquele hash**; não basta estar abaixo de Torrents.
Consulta passiva não move nada e não grava registro. Android usa o `download_dir`
real recebido para SFTP, aceitando as duas formas e recusando escapes.

Lock por hash + gravação atômica/fsync protegem a transição `pending → submitted
→ done`. Estado `submitted` é gravado **antes** do RPC: se a resposta ficar
ambígua, nenhum novo move é reenviado automaticamente. Reexecução só confirma
localização atual; `blocked`/`submitted` precisam de inspeção do proprietário se
não houver conclusão. O motor pode parar esse torrent se uma falha real de disco
ocorrer durante move; não forçar retomada nem mover manualmente para esconder erro.
Sem limpeza automática de pastas vazias/registro, migração em lote ou retry infinito.

### Instalação efetivamente executada nesta correção

No inventário desta retomada: Transmission `4.1.3-1.fc44`, unidade dedicada ativa,
RPC `127.0.0.1:19091`, hook de conclusão previamente desativado e vazio. A cópia
instalada da CLI foi comparada ao commit anterior antes de ser atualizada.
Dentro da correção/teste solicitados pelo proprietário foram executados:

```sh
install -Dm0700 ops/myct-torrent "$HOME/.local/bin/myct-torrent"
install -Dm0700 ops/torrent/myct-torrent-done "$HOME/.local/bin/myct-torrent-done"
~/.local/bin/myct-torrent enable-naming
```

`enable-naming` confirma `config_dir == ~/.config/myct-transmission`, recusa
substituir outro hook e usa apenas `session_set` para habilitar o hook na instância
My-CT. Não toca no daemon global, unidade systemd, settings.json em execução,
Core, SSHD, firewall, banco ou Cofre. **Não houve restart nem interrupção dos
existentes.** Instalação nova por `prepare.py` já inclui o hook nos settings.
Na instância existente a ativação é em runtime; o Transmission salva settings no
encerramento normal ([código oficial](https://github.com/transmission/transmission/blob/4.1.3/daemon/daemon.cc#L953)).
Não se testou reinício/reboot. Se houver kill abrupto ou reload de configuração
antiga, conferir `session_get` e repetir `enable-naming` antes de novos downloads.
Não editar settings vivos nem reiniciar os torrents existentes só para testar isso.

Reversão da automação: desabilitar apenas `script_torrent_done_enabled` por
`session_set` na instância dedicada. Manter CLI atual, registros e pastas legíveis
para que continuem consultáveis. Não instalar CLI 0.5.3 sobre esta configuração:
seu filtro antigo rejeita pastas legíveis. Nada deve ser movido de volta em lote.

### Prova real e limites

Fixture própria CC0, **65.536 bytes**, servida por HTTP webseed em loopback;
Transmission real recebeu os metadados e fez download/verificação de peças.
Não implementamos cliente/tracker BitTorrent nem instalamos dependências.
Magnet inicial tinha `dn=ERRADO-SUGESTAO-MAGNET`; título real dos metadados:
`MYCT prova ação 4209c56e.txt`, hash `a8146ba725c5e302aefed8c93a9e311380dc6606`.

- Callback do daemon, sem sessão Android, mudou a localização automaticamente de
  `Torrents/a8146ba725c5e302aefed8c93a9e311380dc6606` para
  **`Torrents/MYCT-prova-ação-4209c56e.txt-a8146ba7`**.
- `metadata_percent_complete:1`, `percent_done:1`, `status:6`, `error:0`; arquivo
  interno continuou `MYCT prova ação 4209c56e.txt`, mesmo conteúdo e tamanho.
- SHA-256: `5460f7d30ff4d442a7e8cfa666e894795adce417580732e5c0b2985a0f9fb249`.
- Duplicata com outro `dn` não mudou localização/estado. Pausa chegou a 0;
  `torrent_verify` preservou 100% sem erro; retomada chegou a **6 (seeding)**.
  Outra chamada de organize não moveu novamente.
- Remoção da fixture pela CLI preservou o arquivo/hash no caminho legível.
  Somente os torrents sintéticos criados pelo teste foram removidos da lista;
  arquivos de teste permanecem. Diretórios e estados dos torrents preexistentes
  foram comparados antes/depois e ficaram idênticos. Ratio/limites de banda
  também foram comparados, sem mudança.
- **Não foi medido upload para peer externo**: a fixture controlada usou webseed
  local, não swarm público. Não equivale a novo aceite no Moto ou reboot.

Hipótese/teste mínimo inicial: `torrent_add` duplicado com metainfo preencheria
um magnet pendente. Resultado: continuou sem metadados; tentativa encerrada em
70 s. Não alteramos o produto para contornar isso. A fixture real então removeu
**apenas o próprio magnet sintético** e o reincluiu com metainfo, conservando o
registro novo. Isso exercitou conclusão/move reais; não é suporte `.torrent`
na UI nem prova de descoberta pública de metadados. A tentativa incompleta foi
removida da lista, sem excluir dados.

Fixture opt-in reproduzível (adiciona 64 KiB ao motor real; requer CLI/hook já
instalados/ativados, preserva arquivo e deixa a fixture em seeding):

```sh
python3 ops/tests/integration_torrent_naming.py --run-real-fixture
```

Logs/JSON antes/depois, controles, hipóteses e medidas locais em
`artifacts/myct-5c-validation/0.5.4/`, sem títulos privados no relatório público.
A fixture levou 5,30 s; RSS do processo de teste 22.804 KiB, não memória do daemon.
Amostra RSS do Transmission existente: 29.408 KiB; cgroup inclui cache de arquivos,
não confundir com RSS. Não é benchmark de seeding prolongado.

### Validação e APK da correção

- Android final: **67 PASS / 1 opcional ignorado**, zero falhas/erros;
  assembleDebug/testDebugUnitTest/lintDebug offline aprovados. Lint sem erros,
  seis avisos existentes. Novo teste aceita Unicode/caminhos legíveis e recusa
  escape, separadores, sufixo incompatível e tamanho excessivo.
- Python: **13/13 PASS**, incluindo registro somente para novos diretórios antes
  de iniciar, metadados pendentes, título real, legado intacto, colisões/symlinks,
  uma única relocation e resultado RPC ambíguo sem reenvio.
- Fixture real, controles e invariantes antes/depois: **PASS** conforme seção acima.
- Terminal upstream: 21 arquivos verificados, dois patches preservados.
  `git diff --check` aprovado. Nenhum aparelho conectado nesta correção.
- Core/HTTP/Cofre não foram alterados; suas provas físicas anteriores não foram
  repetidas. Testes Android preservam também a suíte HTTP existente.

APK `artifacts/myct-5c-0.5.4-debug.apk`, pacote `dev.myct`,
**0.5.4-torrents / code 11**, 13749736 bytes. SHA-256:
`599c8ffc4ea3d0653c5ad95fb91c08186f239a4cdeafd7aaa7a3f7194c72abac`.
`apksigner` confirmou a assinatura debug anterior
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
[APK e checksum](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5c-v0.5.4-rc1).
Build/testes/lint: 81,95 s, CPU acumulada 345,50 s e RSS máximo
1.693.068 KiB (~1,61 GiB), sem swap, limites Gradle inalterados. Logs/medidas
em `artifacts/myct-5c-validation/0.5.4/`. SHA da correção identificado pelo tag da prévia; commits no PR #17.

### Retomada e aceite curto no Moto

Atualizar para APK 0.5.4/code 11 **sem desinstalar**. A CLI/hook já foram atualizados
neste Fedora. Conferir os torrents antigos em suas pastas atuais; adicionar um
novo magnet pequeno/legal, esperar conclusão e confirmar pasta com título real
no SFTP, pausa/retomada e seeding. Sem migração das pastas antigas. Não repetir
os gates anteriores por obrigação. `.torrent` SAF continua fora desta correção.


## Registro histórico da candidata 0.5.3

As seções abaixo preservam o inventário e bloqueios da entrega inicial, superados
pelos gates relatados nos comentários do PR e na correção acima.


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
`tr`. Trackers resolvidos precisam ser HTTP/HTTPS/UDP públicos; os que não
resolvem DNS são descartados antes da inclusão (correção posterior de 17/10).
Credenciais, fontes `xs/as/ws`, controles e outras opções são recusados. O motor
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
