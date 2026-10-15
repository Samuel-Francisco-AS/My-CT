# MYCT-5A — downloads HTTP/HTTPS headless

15/10/2026. Base `main` em `71ca4d7528facfe75fc44a49a092f035cef227b8`;
branch `myct-5a-http-downloads`. Implementação e testes Fedora entregues para revisão, sem instalação ou
substituição de binários ativos. **Aceite real no Moto G06 confirmado pelo
proprietário em 15/10/2026**: continuidade após encerrar SSH/app e SHA-256 correto.
PR #15 pronto para integração na `main` após esse aceite.
Relacionado à issue #4; torrent, automações e interface Android continuam fora.

## Comportamento e decisão

`myct-download` é uma CLI C++20 separada do Core. Usa `/usr/bin/curl` como motor
HTTP/TLS, sem implementar esses protocolos. `start` cria uma unidade **transitória
do systemd de usuário**, sem arquivo `.service`, enable, sudo ou daemon novo
permanente. O gerenciador do usuário possui o worker e o curl; a CLI iniciadora
encerra imediatamente. Exige `Linger=yes` previamente configurado para não depender
da existência de sessões. O Fedora inspecionado já tinha esse valor e o gerenciador
estava `running`; nenhuma configuração foi alterada.

Escolha: curl 8.18.0 instalado, sem headers/libcurl-devel; reutilizar o executável
poupou instalação privilegiada. Metadados em pequenos arquivos privados substituem
PostgreSQL **somente neste fluxo**: não há benefício em manter o banco ativo para
um download pessoal. Estado é publicado por rename de arquivo temporário. O Core,
seu serviço, API, banco, Android, cofre e Narys não foram modificados.

Aceite definido antes do código: download real e hash, consulta separada de
bytes/total/estado, cancelamento, parcial distinto do final, independência do SSH
e preservação da Saúde. Não há fila: um lock permite **um worker transferindo por
vez**. Outro pedido recebe ID e estado `failed`, explicando que deve aguardar ou
cancelar. Não há retries automáticos.

## Compilação, instalação opcional e uso

Ferramentas verificadas: Fedora 44, GCC 16.2.1, curl 8.18.0, systemd 259,
Python 3.14.7 (apenas testes), gh 2.97.0. Nenhuma dependência instalada.

```sh
cd ~/Projetos/My-CT
make -C server myct-download
./server/myct-download start 'https://example.com/' exemplo.html
# Copiar o UUID impresso e substituir ID nos comandos seguintes:
./server/myct-download list
./server/myct-download status ID
./server/myct-download cancel ID
```

Os comandos funcionam pelo Terminal SSH do app. `status` informa `state`, `bytes`,
`total` (`-1` = desconhecido), `path` e `message`. `list` permite recuperar o ID
após reconectar. `start` aceita o pedido; o resultado é consultado por ID.
Para acompanhar continuamente: `watch -n 1 './server/myct-download status ID'`.

Não é preciso instalar para usar o checkout. Se desejar um comando estável no
PATH, **o proprietário pode executar**, com nenhuma transferência ativa:

```sh
install -Dm0700 server/myct-download "$HOME/.local/bin/myct-download"
# Depois usar ~/.local/bin/myct-download start|list|status|cancel ...
```

Nenhum comando sudo é necessário neste Fedora. Em outro ambiente com linger
desativado, `start` recusa execução; avaliar e executar manualmente, somente
pelo proprietário, `sudo loginctl enable-linger "$USER"`. A CLI nunca faz isso.
Não trocar/recompilar o executável enquanto uma unidade ainda puder iniciá-lo.

Destino: `~/Downloads/My-CT/ID/nome` (nome padrão `download`). Nome ASCII simples,
até 120 caracteres; não aceita caminhos, nome iniciado por ponto/hífen ou sufixo
`.part`. Cada ID tem diretório próprio. O curl grava `nome.part`; só depois de
HTTP 2xx e saída zero um hard link publica `nome` sem substituir destino existente.
O `.part` então é removido. Não se usa nome fornecido por Content-Disposition.
Arquivos podem ser encontrados pelo SFTP já existente no Android.

Metadados: `~/.local/share/myct/downloads/ID/`, privados ao usuário (umask 077).
Incluem URL, nome, estado, headers, resposta e diagnóstico `curl-error`.
URLs podem ter parâmetros sensíveis: esses arquivos não devem ser publicados.
Não há suporte a credenciais na URL, cookies ou headers de autenticação.

## Limites, falhas e recuperação

- Apenas HTTP/HTTPS, com TLS verificado pelo curl; sem shell para executar URL.
  Até cinco redirecionamentos, inclusive relativos resolvidos pelo próprio curl.
  Cada destino passa por validação de protocolo/DNS e endereços públicos;
  endereços resolvidos são fixados com `--resolve` para impedir nova resolução
  divergente. Respostas DNS com qualquer endereço não público são recusadas.
  Redes especiais/reservadas têm bloqueio conservador; host deve ser ASCII.
- LAN/loopback só com `--allow-private` explícito. Essa opção também autoriza
  destinos privados em redirects; usá-la apenas para URL confiável da própria LAN.
  Proxies do ambiente e `.curlrc` não são utilizados.
- Timeout de conexão 15 s; transferência sem progresso (<1 byte/s por 30 s)
  falha; limite total de 24 h **por resposta**. Sem retomada HTTP Range.
- Reserva inicial de 16 MiB livres e limite de tamanho igual ao disponível menos
  a reserva, inclusive quando o total é desconhecido no curl instalado. Outros
  processos ainda podem consumir disco durante o download; falhas de escrita
  mantêm o parcial e não publicam sucesso. Não há quota permanente por usuário.
  Referência: [curl --max-filesize](https://curl.se/docs/manpage.html#--max-filesize).
- Estados: `starting`, `running`, `completed`, `failed`, `canceled`,
  `interrupted`. Consulta com estado ativo persistido mas unidade ausente informa
  `interrupted` (ex.: reboot, SIGKILL ou falha de gravação de estado). Metadados
  não são prova contra falha elétrica; não há recuperação transacional com fsync.
- Cancelamento para somente a unidade desse ID e seus processos. Falhas/cancelamento
  preservam `.part`; cancelamento antes de abrir o destino pode não criar arquivo.
  Após falha, consultar `message` e, se necessário,
  `cat ~/.local/share/myct/downloads/ID/curl-error`. Não copiar diagnóstico sensível
  para issues. Rodar `start` novamente cria outro ID, sem reaproveitar parcial.
- Não continua após reboot do Fedora; conserva resultado/metadados, marca trabalho
  antigo como interrompido e permite novo pedido. Não há agendamento/reinício
  automático, BitTorrent, API de controle ou dependência do banco/app.
- Resultados já concluídos podem ser movidos manualmente; a CLI não acompanha
  renomeações/movimentações externas. Não há comando automático de limpeza.

## Evidências no Fedora

`make -C server test`: **10/10 PASS** (3 Core + 7 downloads), aproximadamente
37 s. Testes usam curl real, unidades transitórias reais, HOME temporário e
HTTP fixture em loopback, com 64 KiB por arquivo:

- HTTP, redirect relativo, tamanho desconhecido, comparação SHA-256 e consulta
  posterior pelo ID; listagem de três resultados com mesmo nome sem colisão.
- Progresso `running`/bytes/total durante transferência lenta; bloqueio de segundo
  worker; cancelamento preservando parcial estável e sem arquivo final.
- HTTP 404, conexão interrompida, redirect para `file://`, cabeçalho com tamanho
  acima do espaço disponível, timeout real de inatividade e validação de URL,
  endereço privado, nome e ID.
- Destino final criado durante transferência não é sobrescrito.
- Limite real do kernel `RLIMIT_FSIZE=4096` força falha de escrita, sem preencher
  disco. **Não é teste de ENOSPC físico.** Estado antigo sem unidade é simulado
  para testar diagnóstico `interrupted`; não houve reboot.

Primeira fixture de timeout enviava 1 KiB e encerrava a conexão em 33 s; o curl
reportou corretamente transferência truncada (18), antes de atingir sua janela
sem progresso. Hipótese confirmada pelo resultado: corrigir a fixture para não
enviar corpo e mantê-la aberta por 36 s. Nova execução passou com timeout (28).

HTTPS externo real: `https://example.com/`, 577 bytes, comparação byte a byte com
segunda cópia independente via curl. SHA-256:
`25ddf2c883e0d1958ea971d279a7e4f0fd446724ee3db7db19dadabd4a62e484`.

Continuidade **apenas após saída da CLI iniciadora**, comprovada em transferência
controlada: consulta posterior mostrou 8.192/65.536 bytes, depois `completed`
65.536/65.536 com comparação de conteúdo. Isso **não substitui prova SSH**.
Na amostra em andamento: `MemoryCurrent=MemoryPeak=2691072` bytes (~2,57 MiB),
`CPUUsageNSec=15695000`, 2 tarefas (worker/curl); não é benchmark prolongado.
Binário de cerca de 219 KiB; metadados de sucesso local amostrados em 191 bytes,
excluindo diretórios e o arquivo obtido. Não há consumo de worker após conclusão.

`python3 -m unittest discover -s ops/tests -v`: **6/6 PASS**; testes offline do
helper, sem desbloquear cofre. Serviço instalado permaneceu `active`, PID **734**,
mesma ativação `2026-10-15 14:30:54 -03` antes/depois. GET
`http://127.0.0.1:8765/v1/health` retornou `status: ok` antes/depois. Testes do
Core usam outra porta efêmera, nunca segunda instância na 8765. Banco e aceite
físico anterior do app/cofre não foram repetidos nem alterados.

## Prova SSH/Moto — aceite funcional confirmado

A identidade local do servidor foi verificada com a chave pública
`/etc/ssh/ssh_host_ed25519_key.pub`, em known_hosts temporário e
`StrictHostKeyChecking=yes`. A tentativa batch real foi recusada com
`Permission denied (publickey,gssapi-keyex,gssapi-with-mic,password)`; não havia
agente SSH disponível. Nenhuma chave privada/credencial foi lida ou alterada,
nenhuma configuração SSH foi modificada. **Na execução original do Codex** não houve SSH autenticado nem fechamento
do Android; a evidência física foi fornecida posteriormente pelo proprietário,
conforme o aceite abaixo.

Procedimento (aproximadamente 1 minuto de transferência, só 256 KiB, loopback):

```sh
# Pelo Terminal SSH do app, no Fedora, com nenhum outro download ativo:
cd ~/Projetos/My-CT
systemd-run --user --collect --unit=myct-5a-fixture \
  /usr/bin/python3 "$PWD/server/tests/test_download.py" --serve-ssh-fixture
# Se a porta local 18769 estiver ocupada, a fixture falha; não alterar serviço alheio.
# Aguardar a fixture aparecer como ativa, e iniciar:
./server/myct-download start 'http://127.0.0.1:18769/slow' prova.bin --allow-private
./server/myct-download status ID
# Confirmar running e bytes < 262144, anotar ID, executar exit e fechar o app.
# Esperar cerca de 70 s fora da sessão. Reconectar pelo app:
cd ~/Projetos/My-CT
./server/myct-download list
./server/myct-download status ID
sha256sum "$HOME/Downloads/My-CT/ID/prova.bin"
# Somente após consultar/registrar o resultado, encerrar a fixture de teste:
systemctl --user stop myct-5a-fixture.service
```

Esperado: `completed`, 262144 bytes, SHA-256
`2312394bd99545d9de131c24efb781e765ac1aec243f2ed9347597a793a415e9`.
Registrar ID, estado antes de desconectar, saída/hash após reconectar e confirmação
de que o app foi fechado. Se falhar, preservar `.part`/metadados e relatar o estado;
não modificar SSHD/systemd/Core para forçar aprovação. A fixture só serve bytes
controlados em loopback; não é API de controle nem componente instalado.

### Resultado físico registrado em 15/10/2026

O proprietário iniciou o download de prova pelo Terminal SSH do Moto G06,
encerrou a conexão e fechou o aplicativo. Na reconexão ao Fedora, o comando
`./server/myct-download list` confirmou dois IDs:

- `3b3df8b5-d8ed-4948-aeb0-c205dc973570` — `failed`, 0 bytes,
  `curl 7` (falha de conexão à origem), sem arquivo final publicado.
  Ocorrência registrada, causa específica não demonstrada.
- `158ab86e-1fbe-4d6f-b337-65c96b3c353a` — `completed`,
  **262144/262144 bytes**, arquivo final
  `~/Downloads/My-CT/158ab86e-1fbe-4d6f-b337-65c96b3c353a/prova.bin`.
  Consulta `status` confirmou novamente `completed`.

A execução real de `sha256sum` pelo proprietário retornou:

```text
2312394bd99545d9de131c24efb781e765ac1aec243f2ed9347597a793a415e9
```

O valor **coincide exatamente** com o esperado pela fixture controlada,
confirmando a integridade da transferência independente de SSH/app.
**Gate físico MYCT-5A aprovado**; a primeira falha isolada não invalida a
segunda transferência íntegra. Não há demonstração de persistência após
reinicialização do Fedora, que permanece explicitamente fora do escopo.

Importante: `ID` nos exemplos deste documento é um *placeholder*,
não um identificador literal; substituir pelo UUID obtido em `start` ou `list`.

## Arquivos e retomada

- `server/download.cpp`: CLI, worker, supervisão e persistência mínima.
- `server/Makefile`, `server/.gitignore`: build/testes e exclusão do binário.
- `server/tests/test_download.py`: integração e fixture para prova SSH.
- README, server/README, AGENTS, docs/05 e docs/10: estado e acesso a este relatório.

Para retomar: partir da `main` após integração do PR #15, compilar com
`make -C server myct-download` e usar a CLI pelo Terminal SSH do app.
Não refazer o gate físico já aprovado sem razão; preservar registros existentes.
Nenhuma instalação privilegiada ou deploy do Core foi feita nesta fase.
