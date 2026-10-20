# MYCT-SETUP-2 — Transmission e Prowlarr opcionais

20/10/2026 · **Candidata; aceite físico deste APK pendente.**
Branch `feat/myct-setup-2`, criada no próprio `~/Projetos/My-CT`, árvore limpa,
base `feat/myct-setup-1` / `8a2212f`. Não se criou worktree, clone ou checkout
paralelo. PR de implementação direcionada à `feat/myct-setup-1`.

A [issue #53](https://github.com/Samuel-Francisco-AS/My-CT/issues/53) continua
aberta. [PR #54](https://github.com/Samuel-Francisco-AS/My-CT/pull/54) e
[PR #55](https://github.com/Samuel-Francisco-AS/My-CT/pull/55) estavam OPEN/DRAFT,
sem merge, na inspeção. Seus conteúdos/branches não foram modificados.
Plano/ADR-019 foram lidos em `origin/docs/myct-setup-roadmap`; sem duplicação.

**Aceite SETUP-1 informado pelo proprietário nesta missão:** APK code 44 no
Moto G06, diagnóstico SSH funcional, Fedora 44 reconhecido, sem regressão
percebida e bloqueio correto de adoção de Core/PostgreSQL legados.
**Instalação completa da Base em Fedora físico limpo continua sem aceite.**
A autorização desta missão permite desenvolver SETUP-2 apesar dessa pendência;
não transforma o resultado de contêiner em aceite físico nem encerra etapas.

## Critérios e fluxo

Critérios adotados antes de implementar: (1) diagnóstico pessoal só leitura,
reconhecendo legados ativos/habilitados e recusando adoção; (2) instalação
opcional por componente em Fedora descartável; (3) reexecução sem mudanças
ou duplicação; (4) unidade active/enabled e autostart; (5) contratos dos helpers
preservados; (6) build/JVM/lint; (7) explicitar limites físicos.

**Opções → Configurar servidor → Base / Transmission / Prowlarr**.
Cada seleção consulta seu estado real e possui seu próprio plano, hash,
aprovação e progresso. Instalar Base não instala opcionais; instalar um
opcional não instala Core/PostgreSQL nem o outro opcional. Não adiciona nova
aba Busca ou motor de pesquisa. Não há agente/IA no uso normal.

Inventário distingue ausente, instalação gerenciada completa/incompleta,
instalação anterior não gerenciada, execução, autostart, linger, API local,
versão e intervenção/falha. Legados não são chamados de ausentes; bloqueios
não impedem inspeção. Senha sudo continua temporária no stdin do transporte
SETUP-1, sem preferências/arquivos/argv/logs. Capturas permanecem protegidas.

## Implementação e arquivos

- `ops/setup/addons.py`: extensão pequena do executor existente, dois alvos
  fixos, inventário, publicação exclusiva, recibos, instalação/verificação.
- `manifest.json`: pacotes/versões, artefatos oficiais por arquitetura,
  tamanhos/SHA-256, fontes dos helpers e indicação de componentes opcionais.
- `build_bundle.py`: incorpora a extensão e helpers no mesmo ZIP verificado.
  Não baixa código ao construir o APK. O diagnóstico permanece em memória;
  compressão zlib evita ultrapassar limites de argumento SSH com o novo fonte.
- `setup.py`: despacho explícito `--component base|transmission|prowlarr`, com
  `base` como padrão. Motor Base, SQL e migração permanecem no fluxo original.
- `ServerSetup*.kt`, `SshConnection.kt`, Gradle: seletor e inventário, validação
  de plano específico, alvo vinculado ao comando aprovado, assets e code 45.
- Testes Python/Android e `validate_addons_container.py`: provas abaixo.
  Helpers originais `myct-torrent`, `myct-search`, hook e contratos não editados.

Recibos/diário separados por componente em
`~/.local/state/myct-setup/{transmission,prowlarr}`. Mesmo lock da Base impede
instaladores simultâneos. Reconsulta antes e sob lock; repete verificação de
conflitos após DNF e antes de publicar/ativar. Nada de executor arbitrário no
Core, daemon root, API administrativa pública ou mudança de SSH/firewall.

Arquivos existentes divergentes, links, dono inesperado, unidade mascarada,
override, outra instância ou versão incompatível geram intervenção. Não há
adoção automática. Reexecução de instalação completa retorna estado real sem
escrever diário/configuração, reinstalar ou reiniciar serviços. Interrupção
preserva etapas concluídas; arquivo parcial divergente exige revisão, sem
limpeza destrutiva/rollback automático. Ativar instância nova usa enable/start;
serviço ativo não recebe restart. O programa não faz stop/disable.

## Transmission

Pacote Fedora `transmission-daemon`, assinatura RPM/GPG obrigatória. Versão
mínima efetiva **4.1**, exigida pelo JSON-RPC 2.0 já usado pelo helper. Versão
antiga existente não é atualizada/adotada automaticamente. O pacote disponível
é ferramenta; unidade genérica de sistema apenas disponível/inativa não é
confundida com instância dedicada. Unidades customizadas/ativas e diretórios
anteriores são detectados e preservados.

Nova instalação:

- Config `~/.config/myct-transmission/settings.json` criada uma vez, privada.
- `myct-transmission.service` de **usuário**, mesmo contrato da unidade atual.
- RPC bind `127.0.0.1:19091`, whitelist local e host whitelist; sem UPnP/port
  forwarding, sem abrir firewall. Peer networking segue os defaults do motor;
  limites de upload, peers e seeding não são impostos pelo instalador.
- Helpers `~/.local/bin/myct-torrent` e `myct-torrent-done`, modo 0700.
- Destino `~/Downloads/My-CT/Torrents`, sem migração de nomes legados.
  Hook nativo de conclusão aponta ao organizador existente; nenhuma chamada
  de `session_set`, organize ou inclusão ocorre durante diagnóstico.

Somente probe `session_get` na inspeção: versão/config dir/hook. A resposta é
reduzida a metadados; não consulta/adiciona torrents para detectar instalação.

## Prowlarr

Artefato **oficial 2.6.5.5623**, glibc, self-contained. Fontes:
[release upstream](https://github.com/Prowlarr/Prowlarr/releases/tag/v2.6.5.5623),
[instalação oficial](https://wiki.servarr.com/prowlarr/installation/linux),
[configuração da versão](https://github.com/Prowlarr/Prowlarr/blob/v2.6.5.5623/src/NzbDrone.Core/Configuration/ConfigFileProvider.cs).
Pins obtidos pela API GitHub oficial, conferidos no download x64:

| Arquitetura | Bytes | SHA-256 |
|---|---:|---|
| x86_64 / linux-core-x64 | 96.747.834 | `c0824e9f0e9c79e085882f6143041be5c2cde0519baa59e9477d9e4dcbe1b5e3` |
| aarch64 / linux-core-arm64 | 93.683.613 | `6552ac286d8a1213f99779daac685cc283d69fa6ad3144d811af0289ee5f5656` |

URL/tag/tamanho/hash fixos no manifesto; nenhum endpoint `latest/updatefile`
mutável ou `curl | sh`. TLS verificado, sem proxy de ambiente; redirects apenas
HTTPS GitHub/release-assets. Conteúdo integral conferido **antes de extrair ou
executar**; hash divergente impede instalação. Tar recusa caminhos absolutos,
traversal, links/dispositivos e expansão acima de 512 MiB. Sem artefato oficial
fixado para a arquitetura, o plano bloqueia. ARM não foi executado neste ciclo.

Dependências Fedora: libicu, sqlite-libs, openssl-libs, zlib-ng-compat,
libstdc++ e ca-certificates. Sem SDK/.NET global nem contêiner de produção.
Executáveis em `~/.local/opt/Prowlarr`; config/dados privados em
`~/.local/share/myct/prowlarr`. Unidade `myct-prowlarr.service` de usuário.

Config criada somente quando ausente: `BindAddress=127.0.0.1`, `Port=9696`,
SSL/browser automático desativados, chave aleatória gerada no Fedora e escrita
em config.xml 0600 sob diretório privado. Nunca retorna chave ao Android;
probe só usa header local e informa app/versão. Forms com autenticação
dispensada apenas para endereço local, conforme opção suportada; sem expor
serviço/reverse proxy. Atualização automática e analytics desativados nessa
instalação nova. Config existente não é reescrita, chave não é regenerada.

Nenhum indexador/fonte é criado. O helper `myct-search` original consulta a
mesma config privada/API e informa `no_indexers` até configuração humana.
Para configurar, o proprietário abre painel por túnel SSH confiável, por
exemplo em outro cliente com navegador:

```sh
ssh -N -L 19696:127.0.0.1:9696 USUARIO@FEDORA
# No navegador desse cliente: http://127.0.0.1:19696
```

Adicionar fontes manualmente no Prowlarr; não copiar API key para o APK/terminal.
Depois consultar **Busca** no My-CT. Não abrir firewall nem trocar bind para
`*` para acessar o painel. O APK desta etapa não cria um WebView administrativo.

## Boot, SELinux e supervisão

Ambos são unidades de usuário. Se linger estiver ausente, o plano informa e
solicita aprovação para `loginctl enable-linger <conta SSH>`: mantém também
as demais unidades já habilitadas da conta após logout/boot. Linger já ativo
não recebe novo comando. Não modifica coleção GNOME Keyring ou desbloqueio.
Sem desabilitar SELinux, mudar boolean/contexto ou criar política permissiva.

Os únicos comandos privilegiados previstos são DNF para pacotes ausentes e
linger se necessário. Operações de arquivos/serviços são do usuário SSH.
Fechar app/perder SSH pode interromper a execução; a volta exige diagnóstico
real. Não há repetição automática de pedido nem promessa de instalação em
background. Erros guardam etapa/razão sanitizada; sem stdout bruto do DNF,
XML/configuração privada, cabeçalhos ou chaves no diário.

## Validação e ambiente

Checkout único `~/Projetos/My-CT`. Ferramentas observadas: Fedora 44,
Python 3.14.7, Podman 5.8.7, JBR 21.0.10, SDK/build-tools 36.0.0 já existentes.
Nenhum pacote instalado no host. Contêineres rootless descartáveis, limite
1 CPU/1536 MiB e sem portas/volumes do host; rede privada própria de teste.
A imagem da SETUP-1 com systemd/PAM, bus, usuário tester e sudo NOPASSWD é
apenas fixture, não requisito de uso do aplicativo.

```sh
python3 -m unittest discover -s ops/setup/tests -v
python3 -m unittest discover -s ops/tests -p 'test_torrent.py' -v
python3 -m unittest discover -s ops/tests -p 'test_search.py' -v
python3 ops/setup/build_bundle.py artifacts/setup2/bundle
# Somente diagnose pode ser usado no Fedora pessoal:
python3 artifacts/setup2/bundle/diagnose.py diagnose --component transmission
python3 artifacts/setup2/bundle/diagnose.py diagnose --component prowlarr
# Apenas fixtures:
podman build --memory=1g -t localhost/myct-setup1-test -f ops/setup/tests/Containerfile .
python3 ops/setup/tests/validate_addons_container.py --output artifacts/setup2/fedora-validation.json
ANDROID_HOME="$HOME/Android/Sdk" ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
```

Primeira tentativa Prowlarr isolada falhou em publicação do diretório. Hipótese
registrada: `/tmp` tmpfs e home em filesystem diferente. Probe mínimo confirmou
`OSError errno 18` (EXDEV). Extração passou para temporário privado junto ao
destino, mantendo verificação/rename no mesmo filesystem. Retomada preservou
pacotes/helper já concluídos e confirmou API/unidade sem reiniciar Transmission.
Não se tentou contornar SELinux/rede nem alterar o host.

A primeira prova de boot após a fixture torrent com rede desligada não
confirmou prontidão dentro da janela de espera. Em consulta posterior, as
configurações continuaram válidas e ambos os serviços/API ficaram disponíveis;
a hipótese inicial de mudança de formato não se confirmou. O ensaio final
restabelece a rede antes do boot e aguarda a inicialização do Prowlarr. Boot
sem rede e queda SSH/TCP reais permanecem não validados.

## Aceite físico e retomada

No Moto, atualizar sobre code 44 sem desinstalar/limpar dados, mesmo pacote
`dev.myct` e certificado. Em **Opções → Configurar servidor**, alternar Base,
Transmission e Prowlarr: no Fedora pessoal, esperar **instalações anteriores
não gerenciadas**, versão, execução/autostart e botões de aplicação bloqueados.
Fechar/abrir app, reconectar e reler estado. Conferir Terminal/SFTP/Saúde/Cofre,
Downloads/Torrents/Busca existentes sem iniciar testes ou trabalhos novos.

Instalação física só em Fedora limpo autorizado: diagnosticar cada opcional,
revisar pacotes/arquivos/privilégios, primeiro cancelar e depois aprovar um
componente. Conferir Base não alterada, autostart/API e helpers; repetir o
outro componente separadamente. Reinicializar máquina apenas com autorização
específica e conferir estado persistente. Configurar indexadores humanamente
antes de exigir resultado de Busca.

Pendentes: novo APK no Moto; Fedora físico limpo/SELinux/boot; SSH/queda TCP
reais; sudo com senha/requiretty; ARM64 e Fedora 43; busca em indexador real e
novo download/organização no aparelho. Fixture sem indexadores prova contrato
de ausência de fontes, não pesquisa real. Rclone/Drive e SETUP-3/4 não foram
implementados. A operação futura de Rclone será sob demanda, sem daemon ou
agenda acrescentados por esta fase. Sem encerramento de issue, merge, adoção, deploy ou alteração
dos serviços/bancos atuais do proprietário.

## Resultado desta candidata

- **18/18 testes Python SETUP PASS**, incluindo os nove da Base, guardas de
  legado/instância/mascaramento, revalidação, não sobrescrita, tar e integridade.
- Helpers inalterados: **17 testes Transmission + 21 testes Busca PASS**.
- **308 testes JVM: 306 PASS, 2 ignorados**, zero falhas/erros. Dois novos testes
  SETUP-2 cobrem planos/legados e vinculação do alvo/diagnóstico comprimido.
  Build Android e lint PASS. Avisos/hints de Busca/IDE preexistentes não foram
  tratados nesta fase.
- [Fedora isolado](evidence/myct-setup2-fedora.json): Transmission 4.1.3 e
  Prowlarr 2.6.5.5623 realmente instalados. Unidades/API/linger confirmados,
  reexecução com PID/diário/unidade/config iguais, falha simulada antes da
  publicação Prowlarr e retomada. Sem Core/banco criados ou alterados.
  Sockets 19091/9696 inspecionados em `/proc/net/tcp{,6}`: somente 127.0.0.1.
  Reinício do contêiner com rede disponível confirmou retorno de ambos.
- Fixture sintética v1 sem rede: helper adicionou, pausou, consultou, retomou,
  pausou e removeu, **preservando arquivo**. Sem peers/download real; hook
  confirmado por configuração/RPC, sem nova prova física de organização.
- `myct-search status/search` contra Prowlarr real respondeu `no_indexers`,
  resultado esperado. Nenhuma fonte foi adicionada; não é prova de busca real.
  Chave gerada não aparece no diagnóstico, progresso ou diário verificados.
- [Fedora pessoal, somente leitura](evidence/myct-setup2-personal.json): APIs,
  versões, serviços active/enabled e linger reconhecidos; aplicação bloqueada
  como legado não gerenciado. **PIDs e hashes das configurações iguais** antes
  e depois. Core, PostgreSQL, Transmission, Prowlarr, Rclone, Jellyfin, Narys,
  seeding e arquivos existentes preservados. Nenhum deploy/update/restart.
- Memória observada das unidades na fixture (cgroup, não RSS/PSS/benchmark):
  Transmission **3.674.112 bytes (~3,5 MiB)**; Prowlarr **99.086.336 bytes
  (~94,5 MiB)**. Prowlarr: **218 MiB** executáveis, **5,1 MiB** dados iniciais.
  Sem novo serviço de teste permanente: contêineres/rede próprios removidos.

APK **0.11.0-setup2-rc1 / code 45**, pacote `dev.myct`, atualização sobre code 44.
Certificado SHA-256 conferido idêntico ao APK SETUP-1 preservado:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
SHA-256 APK: `90929ee0e23d1a4eb26222ba6d7127a6c9c25844fc514bbd27e5e1332695ebf5`.
Payload no APK idêntico ao ensaio Fedora final:
`49f754318e6df5dbca9b343f24d96de757fde0780545538c4ccc270da29ede32`.

[Release candidata e SHA256SUMS](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-setup2-v0.11.0-rc1) ·
[APK code 45](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup2-v0.11.0-rc1/myct-setup2-0.11.0-rc1.apk).
Instalação do APK, provisionamento em máquina física e reboot dependem de
consentimento do proprietário. PR permanece draft; sem fechamento administrativo.
