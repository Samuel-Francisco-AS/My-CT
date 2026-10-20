# MYCT-SETUP-1 — instalação supervisionada da base Fedora

20/10/2026 · **Candidata; aceite Moto G06 e Fedora físico limpo pendentes.**
Issue global [#53](https://github.com/Samuel-Francisco-AS/My-CT/issues/53) permanece aberta.
Implementação independente da `main` `fc69747`, worktree `My-CT-setup-1`, branch
`feat/myct-setup-1`. A [PR documental #54](https://github.com/Samuel-Francisco-AS/My-CT/pull/54)
continuava **OPEN/DRAFT, sem merge** na inspeção. Sua integração é dependência
para fechamento desta entrega. O [plano e ADR-019](https://github.com/Samuel-Francisco-AS/My-CT/blob/docs/myct-setup-roadmap/docs/MYCT-SETUP-PLANO.md)
foram lidos; seus arquivos/branch não foram duplicados nem modificados.

## Recorte e critérios definidos antes da implementação

Fluxo único: SSH verificado → **Opções → Configurar servidor** → diagnóstico
somente leitura → plano com pacotes/privilégios/efeitos → aprovação humana →
instalação → verificação real. Sem IA durante uso, daemon administrativo,
API pública, clone prévio, VM ou contêiner obrigatório no produto.

A: Fedora sem My-CT deve instalar Core/banco e reconhecer Saúde.
B: nova execução deve reconhecer o existente sem reaplicar migração, trocar
unidades ou apagar registros. C: erro/interrupção deve manter recibos/etapas,
permitir diagnóstico e retomada segura. Build/JVM/contêiner não constituem
aceite físico Android nem autorização de implantação no Fedora pessoal.

## Inventário e decisões de implementação

- `server/main.cpp` + `postgres.cpp/.hpp`: Core C++20, POSIX/pthread e **libpq**.
  Makefile existente; compilar somente `myct-core` com `-j1`, sem CMake/ORM.
  API original em `127.0.0.1:8765/v1/health`, independente do banco.
- Banco `myct`, usuário Unix efetivo, socket privado porta 5432 e peer continuam
  iguais ao contrato existente. Única migração funcional:
  `server/migrations/001_execution_events.sql`. O instalador envolve a migração
  e seu recibo/checksum na **mesma transação**; sem reescrever a migração original.
- Pacotes de runtime: `libpq`, `postgresql`, `postgresql-server`.
  Build: `gcc-c++`, `make`, `libpq-devel`. Contextos: `policycoreutils`/`restorecon`.
  DNF instala somente nomes ausentes, com GPG obrigatório; sua resolução pode
  atualizar dependências. Não é uma imagem de pacotes congelados: registrar as
  versões reais; fontes/entrada de build são reproduzíveis e verificadas.
- Bootstrap exige conta SSH normal, `python3`, DNF/RPM, sudo e systemd/bus de
  sistema funcionais. Suporta Fedora tradicional 43/44, x86_64/aarch64;
  **testado apenas Fedora 44 x86_64**. Atomic/ostree, outras versões/arquiteturas
  e home com espaços, caracteres especiais ou links exigem intervenção.
- A unidade histórica fixa `User=sam` e o procedimento manual depende de
  `~/Projetos/My-CT`; isso não é transportado para a instalação nova.
  Unidades são geradas com o usuário/home da sessão SSH.
- Core: `/usr/local/bin/myct-core` e `myct-core.service` de **sistema**,
  `User=<conta SSH>`, mesmo modelo de boot existente, com `restorecon`.
- PostgreSQL: `~/.local/share/myct/postgres/{data,run,setup.conf}` privado 0700,
  sem listener TCP, socket 0700, `shared_buffers=16MB`, `max_connections=10`.
  `~/.config/systemd/user/myct-postgres.service` de **usuário**, executando
  `/usr/bin/postgres` como a conta SSH. Linger é habilitado explicitamente no
  plano para manter o gerenciador de usuário após logout/boot; essa configuração
  afeta também outras unidades já habilitadas nessa conta. Não modifica essas
  unidades. Não depende da sessão Android.
  Sem ativar `postgresql.service`, `postgresql-setup` ou clusters de terceiros.
- Core e banco continuam independentes: Saúde pode funcionar com banco parado.
  Nenhum helper adicional é necessário a essa base. Cofre requer configuração
  humana própria; o pin do GNOME Keyring do Fedora pessoal não é distribuído
  como compatibilidade universal. Downloads, antivírus/inspeção, mídia,
  Transmission/Prowlarr, Rclone e recuperação são opcionais/fora desta entrega.
  Nenhum código desses módulos foi modificado.

A escolha de unidade de usuário para PostgreSQL preserva o domínio de execução
já utilizado pelo cluster no home. Uma unidade de sistema invocando diretamente
`postgresql_exec_t` pode transicionar para domínio SELinux sem acesso a esse
home. Não se adicionou política permissiva, `chcon`, boolean global ou contexto
SELinux forçado. O Core mantém a unidade de sistema original. A validação
SELinux física continua pendente. Referências: [política Fedora](https://github.com/fedora-selinux/selinux-policy/blob/rawhide/policy/modules/services/postgresql.te),
[initdb/peer](https://www.postgresql.org/docs/current/app-initdb.html) e
[systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html).

## Arquivos e distribuição

`ops/setup/manifest.json`: versões-alvo, pacotes, componentes, verificações e
privilégios. `setup.py`: diagnóstico/planejamento/execução específica.
`build_bundle.py`: pacote ZIP Python determinístico, com fontes e checksums;
sem baixar código. `ops/setup/tests/`: guardas e teste Fedora descartável.

Gradle gera assets `myct-setup/{diagnose.py,payload.pyz,integrity.json}`
exclusivamente do checkout. O APK verifica SHA-256. Diagnóstico executa o
código local codificado em hexadecimal **em memória** pelo exec SSH; não cria
arquivos no Fedora. Após aprovação, um receptor fixo recebe o pacote pelo
stdin SSH, confere SHA-256 e o coloca em diretório temporário privado.
Fontes são conferidos novamente antes de compilar. A assinatura do APK é a
origem de confiança; não há `curl | sh`, branch remota mutável ou script
administrativo genérico recebido da rede. DNF usa os repositórios do Fedora.

Android: `ServerSetup*.kt`, integração pequena em `SshConnection`,
`TerminalModel`, Opções e build Gradle. Mesma sessão autenticada/JSch, host key
obrigatória; canal separado da PTY e transferências. Pacote `dev.myct`,
**0.10.0-setup1-rc1 / code 44**, assinatura de atualização preservada.

## Consentimento, falhas e reexecução

JSON schema 1 informa SO/arquitetura/RAM/disco, caminhos/versões das ferramentas,
privilégios, unidades de sistema/usuário consultadas, banco, bloqueios e ações.
Estados apresentados: ausente, instalado, já existente, requer intervenção,
falha e incompatível. O plano lista todas as ações e seus privilégios; a
aprovação cobre o plano inteiro. Hash vincula manifesto e fatos relevantes;
o executor reconsulta antes de agir e novamente sob lock. Mudança de plano
exige nova revisão. Disponibilidade de RAM/disco é reavaliada sem usar números
voláteis como identidade do plano.

Sudo apenas em comandos fixos de pacotes/arquivos/unidade Core e
`loginctl enable-linger <conta SSH>`, sem shell. PostgreSQL usa systemd de usuário.
Senha digitada pelo proprietário no Android, temporária pelo stdin SSH/sudo,
fora de argv, preferências, logs e Core; `sudo -k` não deixa timestamp reutilizável.
Capturas ficam bloqueadas durante a tela de configuração, inclusive se a
preferência geral permitir. Strings do campo são descartadas ao fechar;
não há promessa de apagamento físico de todas as cópias da JVM/Python.
Política sudo/requiretty pode exigir intervenção, sem relaxá-la automaticamente.

Recibos privados em `~/.local/state/myct-setup/`: identidade do cluster,
hashes dos artefatos e `events.jsonl` com fsync por etapa. Não substitui arquivos
existentes divergentes; publicação de arquivo novo por hardlink sem sobrescrita.
Instalações legadas/estranhas, overrides e versões major de PostgreSQL
divergentes bloqueiam adoção automática. Banco/tabelas sem recibo compatível
são preservados. Um `initdb` interrompido com diretório incompleto exige revisão
manual; não remove dados nem `postmaster.pid` para tentar novamente.

Fechar Opções não repete ações. Fechar/matar o app ou perder SSH pode interromper
a execução; não existe promessa de conclusão em background. Após reconectar,
consulte o estado real e o diário. Lock bloqueia outra instalação enquanto o
executor está ativo. DNF conserva seus próprios locks/semântica de recuperação.
Reexecução sobre base completa só devolve `existing`/`complete`, sem escrever
novo diário nem chamar sudo. Sem rollback automático de recursos de terceiros.

## Validação e evidências

Evidência automatizada final: [JSON do Fedora isolado](evidence/myct-setup1-validation.json).
Resumo e SHA-256 do APK são registrados no fechamento abaixo.

Comandos sem instalar nada no host:

```sh
python3 -m unittest discover -s ops/setup/tests -v
python3 -m py_compile ops/setup/*.py
python3 ops/setup/build_bundle.py artifacts/setup-bundle
python3 artifacts/setup-bundle/diagnose.py diagnose
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
```

O valor `ANDROID_HOME` acima é apenas o SDK local observado, não caminho exigido
no servidor/app. Fedora observado: 44, Python 3.14.7, GCC 16.2.1, Make 4.4.1,
Podman 5.8.7, JBR 21.0.10, Android build-tools 36.0.0. Nenhuma dependência foi
instalada no host. Usou-se a chave debug já existente, sem copiá-la para o Git.

Teste real isolado, opcional para manutenção, sem portas/volumes do host e sem
`--privileged`; DNF e reinícios somente **dentro das fixtures descartáveis**:

```sh
podman build --memory=1g -t localhost/myct-setup1-test \
  -f ops/setup/tests/Containerfile .
python3 ops/setup/tests/validate_container.py --output artifacts/setup-validation.json
```

Imagem inicial sem My-CT, cluster ou pacotes de build/banco. A fixture fornece
systemd/PAM, bus, gerenciador de usuário (como um login SSH), usuário `tester` e sudo NOPASSWD para a prova, sem senha real.
Limites por contêiner: 1 CPU, 1536 MiB, 1024 PIDs. O script cria/remove apenas
contêineres próprios com label `io.myct.setup.test=1`.

Inclui rejeição de aprovação inválida, instalação real, SQL/migração, reexecução
sem mudança de diário/unidades/PID, serviço sentinela independente preservado,
reinício do contêiner e releitura do evento salvo. C injeta falha explícita em
um pacote **de teste** antes do banco, preservando Core/pacotes; depois retoma
com o pacote normal. Essa injeção não integra o APK. Queda TCP/SSH real não é
simulada por essa falha; exige validação própria.

Durante desenvolvimento, o teste identificou `PGSERVICE=''` rejeitado pela
libpq. Hipótese confirmada com `psql` isolado; correção: remover overrides
PGSERVICE/PGHOSTADDR/credenciais do ambiente e usar `-w`/timeouts. Regressão
unitária adicionada. A primeira fixture não incluía bus de sistema: root lia
systemd pelo socket privado, mas usuário não. Confirmado com `systemctl` como
tester; fixture corrigida com dbus-broker e diagnóstico passou a bloquear essa
base incompleta. Não se alterou D-Bus, PostgreSQL ou serviços do Fedora pessoal.

No Fedora pessoal executou-se **somente diagnóstico**: reconheceu Core existente
e diretório de cluster legado; bloqueou aplicação/adoção. Nenhum comando
install/enable/stop/restart, banco, SSH/firewall, Prowlarr, Transmission, Rclone,
Jellyfin, Narys ou tarefa persistente do host foi alterado pelo instalador.

## Validação no Moto G06 e autorizações pendentes

1. Atualizar o APK sobre code 43, sem desinstalar/limpar dados. Conectar ao
   Fedora usual e conferir Terminal, SFTP, Saúde, Cofre e Downloads.
2. **Opções → Configurar servidor**: conferir diagnóstico, usuário/versão,
   componentes existentes e bloqueio de adoção do Fedora pessoal. Fechar/abrir
   app, reconectar e consultar de novo. Não aprovar instalação nesse host.
3. Em **Fedora limpo descartável acessível por SSH**, com autorização do
   proprietário para aquele alvo: conferir fingerprint, diagnóstico e plano;
   primeiro cancelar a aprovação (sem alterações), depois aprovar e fornecer
   sudo se necessário. Acompanhar etapas e conferir Saúde depois.
4. Sair/retornar/reabrir app e diagnosticar: cinco componentes existentes;
   nenhuma nova instalação. Ensaiar falha/interrupção somente no alvo de teste;
   reconectar, consultar diário e aprovar novo plano quando seguro.
5. Validar reboot de máquina Fedora real apenas quando o proprietário autorizar;
   confirmar Core/banco e evento persistido. O reinício do contêiner não prova
   SELinux/política de boot do hardware real.

**Exigem autorização posterior:** instalar o APK no Moto, preparar/acessar um
Fedora físico limpo de teste, qualquer operação sudo/deploy/alteração de banco
ou serviço no Fedora pessoal e qualquer reboot físico. Este trabalho não
solicita nem realiza a implantação atual. PR permanece draft até revisão e
aceites; não fazer merge nem encerrar #53. SETUP-2/3/4 não implementadas.

Limitações: validação ARM/Fedora 43 e sudo com senha/requiretty não executada;
SELinux de um Fedora físico novo precisa ser conferido sem desativá-lo. Se
recusar serviço/diretório, relatar e revisar política em vez de contornar.
Não há atualização/migração de uma instalação legada nem atualização major
PostgreSQL. A base não provisiona cofre, SSH, firewall ou credenciais externas.

## Fechamento da candidata

- **9/9 testes Python PASS**, incluindo aprovação inválida/obsoleta, diagnóstico
  sem criar estado, não sobrescrita, migração transacional e ambiente libpq.
- **306 testes JVM: 304 PASS, 2 ignorados, zero falhas/erros**; quatro novos testes de SETUP (plano,
  execução em memória, integridade/receptor stdin e fragmentação/limite JSON).
  Build Android/ABIs e lint PASS; 1 warning `UseKtx` e 2 hints já em arquivos
  de Busca, não modificados nesta entrega.
- **Fedora real isolado A/B/C PASS**, usando o mesmo SHA-256 do payload do APK:
  pacotes ausentes instalados, Core/API/banco funcionais, migração única,
  reexecução sem mudança de diário/unidades/PID, sentinela preservada e
  retomada de falha. Linger confirmado `yes`; após reiniciar cada contêiner,
  Core/banco voltaram e o mesmo evento foi relido. Não equivale a reboot do PC.
- Cluster de teste: **42 MiB**. Memória das unidades registrada no JSON de
  evidências (cgroup, não RSS/PSS nem benchmark). Limite 1 CPU/1536 MiB por
  fixture. Cache de imagens de teste: aproximadamente 441 MB; build local
  Android aproximadamente 115 MiB. Contêineres de teste encerrados/removidos;
  nenhum daemon de teste permanente.
- A fixture com unidade de usuário também precisou de `systemd-pam`, que
  reproduz a criação do gerenciador em um login SSH normal. A ausência de
  `/etc/pam.d/systemd-user` foi confirmada antes de corrigir a fixture; isso
  não originou alteração de PAM/SSH no host.
- Diagnóstico final no Fedora pessoal voltou a bloquear adoção de Core/cluster
  legados, sem executar o instalador. A configuração atual de Prowlarr foi
  preservada, sem presumir seu estado a partir de documentação histórica.
- Certificado SHA-256 igual ao APK publicado code 43:
  `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.

Publicação candidata: [release MYCT-SETUP-1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-setup1-v0.10.0-rc1),
[APK code 44](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup1-v0.10.0-rc1/myct-setup1-0.10.0-rc1.apk).
SHA-256 APK: `62f1f2c29e5dee3fdf82f8a4108f8aefb26b3b699e037c2b35df923ea34ab7a7`.
Payload: `106b2cbbe3a6fe6d7dd23baf302321ed55a9b943170fcf7c113e675365c44b8e`.
`SHA256SUMS` acompanha a release. Payload do APK conferido byte a byte com o
pacote exercitado pelas duas instalações isoladas.

Para manutenção após instalação aprovada, consultar sem modificar:

```sh
systemctl status myct-core.service --no-pager
systemctl --user status myct-postgres.service --no-pager
loginctl show-user "$(id -un)" --property=Linger
journalctl -u myct-core.service -n 30 --no-pager
journalctl --user -u myct-postgres.service -n 30 --no-pager
```

Falhas DNF: conferir o log local `/var/log/dnf5.log`; não reenviar credenciais ou
logs brutos de outros serviços. Esses comandos não autorizam instalar ou
reiniciar serviços no Fedora pessoal.
