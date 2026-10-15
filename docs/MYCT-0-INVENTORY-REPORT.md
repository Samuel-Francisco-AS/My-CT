# MYCT-0 — Inventário técnico inaugural e análise de viabilidade

**Data da coleta:** 2026-10-14 (ambiente `America/Fortaleza`)
**Branch:** `myct-0-inventory`
**Escopo:** consultas não invasivas e documentação. Nenhuma configuração de servidor, serviço, firewall, SSH ou PostgreSQL foi alterada; nenhum pacote foi instalado.

## Resumo executivo

O ambiente observado corresponde ao hardware de referência: Fedora Linux 44 Workstation, kernel 7.2.8, x86_64, Intel Core i7-3770 (4 núcleos/8 threads), 7.6 GiB RAM e SSD identificado como 223.6 GiB. No instante da coleta havia 6.6 GiB de memória disponível, swap zram de 7.6 GiB sem uso e 133 GiB livres no volume Btrfs principal. O sistema está em `multi-user.target`, sessão TTY, sem DISPLAY/Wayland; isso comprova o estado atual sem provar que todo serviço gráfico esteja removido.

A base é suficiente para desenvolvimento e para um serviço enxuto. O compilador GCC 16 aceita sintaxe C++20. OpenSSH server está ativo e habilitado; systemd, curl/libcurl runtime, qBittorrent gráfico e ferramentas Android estão presentes. Porém, não existe ainda uma toolchain C++ de build completa: CMake e cabeçalhos/libpq/libpqxx/libcurl de desenvolvimento estão ausentes, assim como PostgreSQL e um daemon BitTorrent headless identificado. A configuração de SSH e regras efetivas do firewall não puderam ser lidas sem privilégio adicional.

O SDK Android ocupa aproximadamente 1.8 GiB e inclui platform-tools, plataformas 36/36.1 e Build Tools 35/36. Java 21 e cache AGP 8.13.0 estão presentes. Gradle executável e wrapper do projeto não foram encontrados. A consulta ADB iniciou o daemon local de depuração, mas não havia dispositivo listado; portanto o Moto G06, APK por CLI e UX no aparelho continuam sem validação.

**Estado geral:** arquitetura tecnicamente plausível, mas a primeira compilação/integracão de backend e a construção de APK ainda estão bloqueadas por dependências e provas ausentes. Nada neste relatório deve ser interpretado como implementação do produto.

## Inventário técnico observado

### Sistema e capacidade

| Item | Observação |
| --- | --- |
| Distribuição | Fedora Linux 44 (Workstation Edition) |
| Kernel / arquitetura | Linux 7.2.8-200.fc44.x86_64; x86_64 |
| CPU | Intel Core i7-3770 @ 3.40 GHz; 1 socket, 4 cores, 2 threads/core, 8 CPUs lógicas |
| RAM | 7.6 GiB total; amostra `free`: 1.0 GiB usada, 3.0 GiB livre, 6.6 GiB disponível |
| Swap | 7.6 GiB em zram; 0 B usados na amostra |
| Disco | `/dev/sda`, 223.6 GiB; raiz/home em Btrfs de 222 GiB, 89 GiB usados e 133 GiB disponíveis (41%); `/boot` ext4 2 GiB, 1.3 GiB disponíveis |
| Estado gráfico | `multi-user.target`; sessão `tty`; variáveis DISPLAY e WAYLAND_DISPLAY não definidas. Pacote Fedora é Workstation, mas sessão observada está sem GUI ativa para o usuário |
| Amostra de carga | `vmstat 1 2`: CPU 1% user, 0% system, 99% idle na última amostra; sem swap-in/out observado. Snapshot curto, não benchmark |

A partição Btrfs é compartilhada entre `/` e `/home`; o `df` não representa cotas/subvolumes Btrfs nem espaço reservado. O tamanho em GiB é coerente com SSD anunciado como 240 GB decimal, mas o modelo/saúde SMART não foram consultados.

### Ferramentas e serviços

- GCC/G++ 16.2.1; compilação apenas sintática de `static_assert(__cplusplus >= 202002L)` com `g++ -std=c++20 -fsyntax-only` passou (exit 0). Não foi produzido binário nem projeto.
- Ninja 1.13.2, pkg-config 2.5.1, GNU Make 4.4.1 e Git 2.55.0 presentes.
- CMake, Clang/Clang++, htop, btop, iostat, sar e perf não encontrados. `top`, `vmstat`, `journalctl` e `systemd-analyze` encontrados.
- systemd está presente; default target `multi-user.target`. NetworkManager, systemd-resolved, firewalld e sshd aparecem ativos. `sshd` também está habilitado.
- Serviços ativos incluem ainda, entre outros, Avahi, CUPS, Bluetooth, cron, chronyd, auditd e unidades ABRT. A relevância/necessidade destes serviços para o uso headless deve ser discutida; não foram modificados.
- A tabela `ss -lntup` indicou TCP nas portas 22, 53, 631, 5037 e 5355, além de uma porta TCP alta associada a processo Codex; UDP incluiu 53, 123, 5353, 5355 e portas dinâmicas. Endereços e PID foram omitidos. `ss` associa parte das portas a sshd, resolved, CUPS e ADB conforme a saída disponível, mas a associação foi incompleta; não conclua exposição à Internet apenas por essa listagem.
- Interface Wi-Fi ativa e interface Ethernet `DOWN`; rota default e DNS presentes. Endereços IP/SSID não foram registrados. A conectividade observada parece estar em LAN privada `192.168.0.0/24` (prefixo coletado, sem host address).
- firewalld está ativo e a zona `default` foi indicada na consulta. `firewall-cmd --state`/consulta de zonas retornou falha de autorização via polkit; regras efetivas, serviços liberados e binding não verificados.
- OpenSSH 10.2p1 server instalado, serviço ativo/habilitado; `ssh`, `sftp` disponíveis. Porta TCP 22 apareceu na listagem. Leitura de `/etc/ssh/sshd_config` negada; opções efetivas (autenticação, root login, forwarding, subsystem SFTP) não verificadas. Não houve tentativa de conexão SSH.

### Banco, HTTP e downloads

- `psql`, `pg_config`, `postgresql`, `postgresql-server`, `libpq`, `libpq-devel`, `libpqxx`, `libpqxx-devel` e pacote `libpqxx-devel` não encontrados. PostgreSQL não instalado conforme consulta RPM/comandos.
- `curl` 8.18.0 e runtime `libcurl` 8.18.0 estão presentes (pacotes x86_64 e i686). `pkg-config --modversion libcurl` falhou e `libcurl-devel` não está instalado: headers e integração de build indisponíveis/não comprovados.
- qBittorrent 5.2.3 instalado, mas o pacote expõe `/usr/bin/qbittorrent`; `qbittorrent-nox`, `transmission-daemon` e `transmission-remote` não encontrados. Não foi identificado serviço headless de torrent. Instalação do cliente gráfico não valida uso headless nem sua API RPC.
- Tailscale binário/pacote e serviço não encontrados; alternativa de VPN privada ainda sem comprovação local.

### Ambiente Android

- Java OpenJDK 21.0.10 (JBR do Android Studio em `/opt/android-studio/jbr`). `JAVA_HOME` aponta para esse JBR; `ANDROID_HOME=/home/sam/Android/Sdk`.
- Android SDK ocupa 1.8 GiB. Encontrados Platform Tools/ADB 37.0.0, Command-line Tools 22.0, Build Tools 35.0.0 e 36.0.0, plataformas Android 36 e 36.1, emulator 36.6.11 e sources 36.1.
- Cache Gradle contém Android Gradle Plugin 8.13.0; Gradle instalado como comando e wrapper `gradlew` não encontrados. Não existe estrutura Gradle do produto no repositório. Presença de cache não prova distribuição Gradle utilizável offline nem build APK completo.
- `adb devices -l` listou zero dispositivos. ADB iniciou daemon local durante a consulta. Moto G06 não conectado/detectado; compatibilidade e fluxo USB/depuração permanecem pendentes.
- Nenhum emulador foi iniciado. Nenhum APK foi construído. Disponibilidade de RAM e espaço atuais é favorável para começar sem emulador; build Android e daemon Gradle podem, porém, pressionar 8 GB e consumir o SSD/cache. Medir durante build real e limitar paralelismo/cache conforme evidência.

## Tabela de dependências e estados

Estados descrevem evidência neste ambiente: **Disponível e comprovado** exige que a capacidade tenha sido exercitada; **Disponível, mas não validado** indica presença sem prova funcional da integração; **Ausente** indica ferramenta/componente não localizado; **Bloqueado** indica dependência concreta que impede prova; **Não verificado** indica ausência de evidência suficiente.

| Conjunto | Estado | Evidência / limite |
| --- | --- | --- |
| C++20 + compilador | Disponível e comprovado (sintaxe) | G++ 16 e teste sintático C++20 passaram; build de projeto não executado |
| C++20 + CMake | Bloqueado | G++ presente; CMake ausente, sem build reprodutível |
| API HTTP C++ (cpp-httplib ou equivalente) | Não verificado | Nenhuma biblioteca/servidor HTTP C++ consultado/instalado; proposta ainda aberta |
| PostgreSQL + libpq + libpqxx | Bloqueado | PostgreSQL e dependências de desenvolvimento ausentes; nenhuma integração testada |
| Kotlin + Jetpack Compose / APK CLI | Bloqueado | Java, SDK, Build Tools e cache AGP presentes; Gradle/wrapper e projeto ausentes; sem APK |
| SSH/PTTY dentro do Android | Não verificado | OpenSSH ativo no host; cliente Android e renderer não escolhidos/provados |
| SSH/SFTP do Fedora | Disponível, mas não validado | OpenSSH server ativo/habilitado, binários ssh/sftp; configuração efetiva protegida e nenhuma sessão/teste de transferência |
| SFTP + Android SAF | Não verificado | Nenhum app/integração SAF implementada; sem aparelho para prova |
| HTTP/HTTPS para downloads | Disponível, mas não validado | curl e libcurl runtime disponíveis; headers de desenvolvimento ausentes, retomada/cancelamento não demonstrados |
| BitTorrent headless | Ausente | qBittorrent gráfico presente; daemon/nox e Transmission não localizados |
| systemd para supervisão | Disponível, mas não validado | systemd operacional e target multi-user; nenhuma unit My-CT nem política de serviço validada |
| Rede privada / Tailscale | Não verificado | LAN ativa; Tailscale ausente. Firewalld ativo mas regras inacessíveis sem privilégio |

## Avaliação de viabilidade

### C++20 + CMake + API HTTP

**Viável**, com dependências ausentes e escolha HTTP ainda revisável. A arquitetura é adequada a footprint baixo, mas o único dado comprovado é a aceitação de sintaxe C++20 pelo GCC. CMake está ausente. cpp-httplib é apenas candidato documental; deve ser comparado com alternativas de baixo custo de compilação/empacotamento, manutenção e segurança. Evitar framework interno e dependências grandes. Critério inicial: endpoint mínimo de saúde com build reproduzível no Fedora, escutando apenas em interface local/LAN privada após desenho explícito de autenticação e transporte.

### C++20 + PostgreSQL + libpqxx

**Viável, porém bloqueado por instalação e sem validação.** O hardware comporta uma instância conservadora de PostgreSQL, mas ela adicionará RAM, armazenamento, atualizações e backup a um host de 8 GB. O requisito de Postgres está decidido pelo proprietário; isso não justifica inicializá-lo antes de confirmar necessidade mínima, configuração restrita, ownership de migrações, backup e restauração. libpqxx é proposta revisável; libpq direto é alternativa menor se a necessidade não justificar abstração.

### Kotlin + Compose + SSH/PTTY

**Viável como direção; risco técnico alto até prova no Moto G06.** JDK/SDK existem, mas Gradle de CLI e build ainda não comprovados. A dificuldade principal é o terminal de verdade: gestão de channel PTY, ANSI/escape sequences, IME, Ctrl/Tab, setas, resize, lifecycle, reconexão e verificação de host key. SSH não deve ser implementado do zero. A prova precisa ser um app mínimo com sessão SSH real e PTY, testado em aparelho físico; compilar isoladamente não é aceite.

### Kotlin + SFTP + Storage Access Framework

**Não verificado.** É uma combinação padrão e apropriada a arquivos Android, mas picker SAF, permissões URI, streaming, arquivos grandes, conflitos, cancelamento e retorno de erros dependem de integração real. Provar upload e download de arquivo escolhido no Moto G06 antes de projetar um explorador extenso.

### C++ + downloads HTTP/HTTPS

**Viável com dependência de desenvolvimento ausente.** libcurl runtime existe, mas headers/pkg-config de desenvolvimento não. Destinos, redirecionamentos, SSRF, paths, disco cheio, arquivo temporário/atômico, retomada e cancelamento precisam ser projetados e testados. Não tornar API C++ um proxy arbitrário; serviço downloader deve falhar sem derrubar SSH, API principal ou banco.

### Daemon BitTorrent headless

**Plausível, atualmente ausente e não validado.** qBittorrent desktop instalado não é evidência de serviço headless. Transmission-daemon foi proposto; qBittorrent-nox é uma alternativa se o pacote Fedora e seu custo operacional forem adequados. Comparar disponibilidade/versionamento, API, consumo ocioso, isolamento, permissões e risco de RPC exposto. Não integrar antes do fluxo HTTP e política de espaço/seed/quotas.

### Gerenciamento via systemd

**Disponível, ainda não validado para My-CT.** systemd e target headless existem. Unidades futuras devem usar usuário sem privilégios, diretórios explicitamente permitidos, hardening proporcional, limites/restart claros e logs consultáveis. Core, PostgreSQL e downloader devem ser processos independentes; não mexer em serviços atuais nesta missão.

## Gargalos e riscos principais

1. **Terminal Android:** uma biblioteca SSH pode abrir conexão sem oferecer terminal correto. PTY/ANSI/teclado virtual/resize/host-key e comportamento em background exigem experimento físico cedo.
2. **Build Android sem caminho confirmado:** falta Gradle executável/wrapper; SDK/caches ocupam 1.8 GiB. ADB não encontrou o Moto G06. Tempo e espaço podem ser consumidos antes da primeira prova.
3. **RAM e concorrência:** 7.6 GiB atendem serviço pessoal leve, mas PostgreSQL, JVM/Gradle, torrent, cache de arquivo e outras tarefas simultâneas podem disputar memória. Swap é zram, não capacidade equivalente a RAM; medir picos e limitar paralelismo.
4. **SSD único / torrents:** 133 GiB estavam livres, compartilhados com sistema, home, banco e downloads. Torrent/seeding ou retenção sem quota pode esgotar espaço e afetar o sistema; não há redundância/backup observado nesta coleta.
5. **Superfície de rede e configuração não inspecionável:** SSH está ativo, firewalld ativo, listeners 22/53/631/5037/5355 e outros foram observados, mas regras de firewall e parâmetros efetivos do SSH não puderam ser lidos. Não expor API, Postgres ou RPC até validar bind, autenticação, host key e caminho privado.

Riscos adicionais: regras SSRF e traversal; perda/corrupção do SSD; manutenção de dependências Android/SSH; CUPS/serviços presentes sem necessidade headless já decidida; exposição transitiva por daemon torrent; indisponibilidade de rede/energia.

## Dependências realmente ausentes ou bloqueantes

- CMake.
- PostgreSQL server/client e ferramentas `psql`/`pg_config`.
- Headers e pacotes de desenvolvimento `libpq`, `libpqxx` e `libcurl`.
- Gradle CLI/wrapper no projeto; nenhum build APK demonstrado.
- qBittorrent-nox/daemon ou Transmission daemon.
- Tailscale, caso seja escolhido para acesso remoto.
- Cliente Android SSH/PTTY, renderer de terminal e integração SFTP/SAF ainda não implementados nem validados.
- Biblioteca/API HTTP C++ escolhida e validada.

Ausência de `htop`, `btop`, `iostat`, `sar` e `perf` não impede inventário inicial: `top`, `vmstat`, `systemd-analyze` e `journalctl` existem. Não instalar ferramentas para esta missão.

## Recomendações de implementação (não são decisões tomadas)

1. Revalidar com o proprietário a prioridade inicial usando resultados concretos: o host tem SSH operacional, mas ainda não há caminho comprovado de APK ou API. Antes de código, fechar aceite pequeno e preparar dependências com comandos revisáveis em outra missão.
2. Fazer primeiro uma prova Android vertical pequena: obter build CLI reproduzível sem emulador, instalar no Moto G06 e abrir sessão OpenSSH real com host key verificada, PTY, comando interativo, Ctrl-C e resize. Se renderer/cliente falhar, cortar escopo ou trocar candidato antes de UI geral.
3. Em seguida, provar SFTP bidirecional com SAF e integridade de um arquivo real no aparelho; isso entrega utilidade direta e evita depender da API/Postgres para funções que OpenSSH já oferece.
4. Construir Core C++ como etapa isolada: primeiro serviço de saúde mínimo e privado; depois uma escrita/leitura PostgreSQL mínima e recuperação. Manter processo Core e banco locais e independentes.
5. Implementar download HTTP(S) em processo separado, com política de destino/espaço, teste offline e continuidade após fechar o app. Só depois selecionar daemon torrent e medir RAM/espaço com conteúdo legal de teste.
6. Usar systemd apenas após binários/configuração/logs serem reproduzíveis e reversíveis; manter SSH independente de falhas do Core/downloader.
7. Não manter emulador, desktop, containers ou framework de agentes como infraestrutura do MVP. Medir RAM, CPU e disco em cada prova significativa; limitar jobs de build conforme a amostra real.

### Ordem sugerida por utilidade prática

**Android CLI/device readiness → SSH/PTTY físico → SFTP/SAF físico → health API C++ privada → PostgreSQL mínimo → download HTTP(S) → daemon BitTorrent → automação allowlisted e recuperação via systemd.** Esta ordem é uma recomendação para discussão. O planejamento inaugural não foi reescrito e decisões tecnológicas permanecem revisáveis.

### Menor primeira entrega verificável ponta a ponta

APK nativo mínimo compilado pela CLI e instalado no Moto G06, conectando ao SSH do Fedora com host key verificada e oferecendo shell remoto com PTY suficiente para executar `pwd`, receber saída interativa e enviar Ctrl-C. Aceitação exige registro de versões/comandos, evidência no aparelho físico, erro claro para host key desconhecida/trocada e reconexão após fechar e reabrir a tela. Se o alvo físico não estiver disponível, a missão pode entregar somente a preparação de build, claramente marcada como bloqueada, sem declarar a jornada aceita.

Depois dessa prova, acrescentar uma transferência SFTP de arquivo pequeno nos dois sentidos com Android SAF e verificação de conteúdo.

## Evidências de comandos (resumo sanitizado)

| Consulta executada | Resultado relevante |
| --- | --- |
| `cat /etc/os-release`; `uname -srmo` | Fedora 44 Workstation; kernel 7.2.8; x86_64 |
| `lscpu` (campos selecionados) | i7-3770; 4 cores, 8 CPUs lógicas |
| `free -h`; `lsblk`; `df -hT` | 7.6 GiB RAM/swap zram; disco 223.6 GiB; 133 GiB disponíveis na raiz/home |
| `systemctl get-default`; `systemctl --type=service --state=running` | `multi-user.target`; sshd/firewalld/NetworkManager/resolved ativos; lista consultada sem alterar unidades |
| versões de `gcc`, `g++`, `cmake`, `ninja`, `pkg-config`, `make`, `git` | GCC/G++ 16.2.1, CMake ausente, Ninja 1.13.2, pkg-config 2.5.1, Make 4.4.1, Git 2.55.0 |
| `printf ... | g++ -std=c++20 -x c++ -fsyntax-only -` | teste sintático C++20 exit 0 |
| `pkg-config --modversion libpq libpqxx libcurl`; `rpm -q ...` | sem libpq/libpqxx/libcurl de desenvolvimento; runtime libcurl 8.18.0 |
| verificação de comandos e RPMs PostgreSQL/torrent | PostgreSQL e Transmission ausentes; qBittorrent 5.2.3 presente apenas como `/usr/bin/qbittorrent`; `qbittorrent-nox` ausente |
| `java -version`; SDK/ADB; cache Gradle | OpenJDK 21.0.10, SDK 1.8 GiB, Build Tools 35/36, platforms 36/36.1, AGP cache 8.13.0; sem Gradle/wrapper detectados |
| `adb devices -l` | lista vazia; daemon ADB local foi iniciado pela própria consulta |
| `ss -lntup` resumido | portas observadas 22, 53, 631, 5037, 5355 e dinâmica; endereços e PID omitidos |
| `firewall-cmd --state`, `--get-active-zones` | firewalld ativo, zona default; detalhes negados por autorização polkit |
| `sshd -T` | leitura de `/etc/ssh/sshd_config` negada; parâmetros efetivos não registrados |
| `ip -brief link`, `ip route` sanitizado | Wi-Fi UP, Ethernet DOWN, rota default e LAN; IPs omitidos |
| `vmstat 1 2` | amostra curta: 99% idle, sem swap-in/out observado |
| `gh auth status` | autenticação GitHub não válida; token nunca incluído no relatório |

Nenhum comando de instalação, atualização, alteração de serviço/firewall/SSH/rede, criação de banco/usuário, abertura de porta ou download de dependência foi executado. Não foi executado teste no Moto G06 nem build do APK. A consulta ADB iniciou somente o daemon ADB de usuário local; nenhuma conexão física apareceu.

## Pendências de validação

- Confirmar se esse Fedora é exatamente o servidor alvo definitivo; dados foram observados no host acessível a esta sessão.
- Verificar configuração efetiva de sshd e regras/serviços de firewalld com autorização apropriada do proprietário; não foi possível nesta coleta.
- Confirmar política de host key e método de acesso privado do Moto G06; Tailscale não está instalado.
- Conectar Moto G06 via ADB e testar build/instalação/PTY/SFTP reais; sem isso não há aceitação Android.
- Confirmar caminho Gradle CLI (wrapper/distro offline ou dependências autorizadas) e custo de build antes de gerar caches.
- Confirmar necessidade, configuração conservadora, política de backup/restauração e custo de PostgreSQL no host.
- Selecionar API HTTP C++ e daemon BitTorrent somente após comparar manutenção, segurança, pacote disponível e consumo.
- Medir estado SMART, consumo em repouso/picos, uso Btrfs real e espaço reservado antes de estabelecer quotas ou limites.
- Fazer inventário de perfis/serviços gráficos apenas se necessário; o target e a sessão observados eram headless, mas o Fedora é edição Workstation.

## Próxima missão sugerida

**MYCT-1 — Prova de build Android CLI e SSH/PTTY no aparelho, sem API/Core.** Escopo mínimo: registrar ferramentas/versões existentes, montar apenas o mínimo necessário para build reproduzível, criar APK simples Kotlin/Compose com conexão SSH para OpenSSH existente, verificar identidade do host e PTY, instalar no Moto G06, executar comando interativo e Ctrl-C, fechar/reabrir a tela e documentar comportamento/recursos. Não incorporar execução de comandos pela API. Critérios de aceite: build CLI repetível sem emulador; Moto G06 listado pelo ADB e APK instalado; host key verificada e troca recusada; `pwd` executado em sessão real; entrada/saída, Ctrl-C e resize utilizáveis; versões, RAM/disco, falhas e instruções de retomada registrados. Se o aparelho não estiver disponível, registrar bloqueio de validação física e não marcar requisito aceito. Dependências a preparar somente após missão autorizada: Gradle de CLI compatível e biblioteca/renderer SSH escolhidos por manutenção e teste.
