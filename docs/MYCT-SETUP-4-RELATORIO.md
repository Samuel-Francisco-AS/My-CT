# MYCT-SETUP-4 — Backup e Recuperação

Candidata de 20/10/2026, empilhada sobre `feat/myct-setup-3`, base `a86a912`.
Dependências #54/#55/#56/#57; issue #53 permanece aberta. Nenhum merge, auditoria
global, backup real, consulta ao PostgreSQL pessoal, alteração de serviços pessoais,
acesso ao Google Drive ou reboot do Fedora pessoal foi realizado nesta missão.

O proprietário informou validações funcionais SETUP-1/2/3 no Moto G06. Elas não
comprovam provisionamento físico limpo, que continua **NÃO VALIDADO**. SETUP-4 no
Moto também permanece **NÃO VALIDADA**. Esta entrega permite congelar a candidata
para as duas auditorias futuras independentes; não é uma conclusão sobre a segurança
de toda a trilha e não substitui nenhuma delas.

## Escopo recuperável

| Conteúdo | Exportação | Recuperação nesta candidata |
| --- | --- | --- |
| Versão do executor/manifesto, origem/conta, versões disponíveis, serviços desejados | Básico sem segredos, privado 0600 | Inventário/receita em diretório privado; unidades nunca importadas |
| Rclone: nome do remote e caminho da configuração | Básico; nunca rclone.conf | Seleção não secreta remapeada ao novo HOME se ausente/igual; OAuth sempre humano |
| Transmission: RPC loopback/porta e caminho de downloads | Allowlist, sem senha/RPC/session | Receita histórica; aplicação automática **pendente** |
| Prowlarr: endereço loopback, porta, SSL | Allowlist, sem API key/senha/indexadores | Receita histórica; aplicação automática **pendente** |
| Banco exclusivo My-CT/migração 001 | Selecionado, `pg_dump` lógico, age obrigatório | Eventos e IDs/datas/tipos em banco Base gerenciado vazio; dados iguais são preservados |
| Catálogos de arquivo/torrent | Seleção explícita, age obrigatório | Bytes/hashes em pasta histórica; ativação/importação **pendente** |
| Bundles de torrents já arquivados | manifest.json/original.torrent pequenos selecionados com catálogos, age | Cópia histórica; não reimporta torrents nem inicia seeding |
| Mídia local/Drive, hardlinks, torrents ativos não arquivados | Excluídos | Não recuperáveis por este pacote; outro armazenamento continua não verificado |
| OAuth, SSH, Prowlarr/RPC keys, Keyring/Narys/tokens | Excluídos | Reconfiguração humana; não incluídos nem simulados |

Um catálogo pode registrar `verified` historicamente: isso **não confirma** que o
objeto ainda existe no Drive ou possa ser recuperado hoje. A restauração não o
coloca na pasta ativa do motor, não altera IDs/caminhos internos e não ativa jobs.
Mídia não copiada em outro lugar e não incluída no backup é irrecuperável pelo My-CT.
Preservar também fontes/metadados externos apropriados; este pacote não é imagem do
servidor nem cópia completa de todos os arquivos da conta.

## Contrato e proteção

`ops/setup/recovery.py` integra o bundle determinístico e o transporte SSH existente.
Novo alvo independente **Backup / Recuperação**, sem daemon, API pública, upload,
framework de tarefas ou executor genérico. Categorias: básico obrigatório, banco e
catálogos opcionais. Inventário `diagnose` permite exclusivamente operação passiva;
exportação/restauração usam ação `backup`, plano SHA-256 e aprovação própria.

Pacote ZIP **sem compressão**, com `manifest.json` schema1/release, origem/HOME/conta,
versões, escopo/migração, exclusões, lista exata, tamanho e SHA-256 de cada item.
Arquivos conhecidos: `basic.json`, `database.dump` quando selecionado e metadados
limitados sob `catalogs/`. Não aceita arquivos arbitrários do usuário: seleção é por
essas categorias conhecidas, para evitar inclusão acidental de segredos/mídias.
Core sem contrato offline de versão e Prowlarr sem probe seguro são registrados como
versão desconhecida; o artefato desejado permanece identificado no manifesto confiável.
Não inventar uma versão instalada a partir de recibo de presença.

- Até **32 MiB** no pacote claro, **16 MiB** por item, **256 itens**, **10.000 eventos**.
  Metadados de torrent individuais têm limite 4 MiB. Limite total também é aplicado
  durante coleta, antes de acumular todos os arquivos em memória.
- Recusar nomes desconhecidos, caminhos absolutos/traversal, duplicação, links,
  dispositivos, formatos compactados, CRC/SHA inválidos, schema/migração incompatível
  ou conteúdo privado sem envelope age. Não usa `extractall`.
- Arquivos de origem devem ser regulares, sem links/hardlinks e pertencer à conta.
  Destinos precisam estar no HOME atual, fora de **qualquer repositório Git**, sem
  links/traversal. Diretório novo privado 0700, arquivo final 0600. Diretórios/arquivos
  anteriores divergentes ou sem privacidade adequada exigem intervenção, sem chmod
  automático de recursos alheios.
- Escrita temporária 0600 no mesmo filesystem, fsync e publicação por hardlink
  exclusivo: nome final não aparece incompleto e nunca substitui arquivo existente.
  Interrupção pode deixar temporário privado oculto; ele não é backup verificado.
- ZIP claro privado fica em memória. Para banco/catálogos, `age --encrypt --recipient`
  recebe apenas destinatário **público** em argv; entrada ZIP pela stdin. Nenhum arquivo
  claro privado intermediário é publicado. Sem age/destinatário válido, recusar antes
  de ler/exportar as categorias privadas.
- Na inspeção, `age --decrypt --identity /dev/stdin` recebe somente a linha secreta
  pela stdin. Não salva identidade em arquivo/configuração, APK, Git, logs ou argv.
  Campos sensíveis Android são temporários, apagados ao enviar/sair/desconectar,
  com captura protegida na seção. A confirmação exige fornecer a identidade novamente.
- Cada subprocesso tem saída limitada, erro bruto descartado e prazo de 90 s;
  CPU de 60 s/core dump desabilitado. Clientes PostgreSQL têm espaço de endereços
  limitado a 384 MiB. O SSH Android limita a operação a 5 minutos e a resposta a 512 KiB.
- Catálogos selecionados são lidos com lock compartilhado não bloqueante existente;
  trabalho em andamento bloqueia a coleta, sem cancelar/interromper nada. Nenhum
  catálogo/trabalho privado é lido no inventário básico passivo.

**Checksums não são autenticação da origem.** Age protege confidencialidade e detecta
adulteração do ciphertext, mas qualquer pessoa com destinatário público pode criar
outro pacote. Usar origem confiável e conferir o SHA-256 por canal próprio. Por isso
nenhum comando, unidade ou SQL fornecido pelo pacote é executado mesmo após validar
hashes/decriptar.

## PostgreSQL real, com escopo explícito

Só usa `myct` no cluster privado `~/.local/share/myct/postgres`, socket local/peer,
conta atual, recibo SETUP-1 correspondente, caminhos sem links. Legados sem recibo
são bloqueados antes de qualquer SQL. Verifica diretório real do servidor, ausência
de TCP, usuário, migração001 com marcador único, duas tabelas específicas, colunas e ausência de regras/triggers/funções
customizadas/extensões adicionais. Não exporta outro cluster, roles ou PGDATA.
Falha de acesso/dump/validação impede publicação do pacote completo.

Exporta com `pg_dump -Fc --no-owner --no-privileges`, limitado às tabelas
`public.execution_events` e `public.myct_setup_migrations`; o dump é consistente sem
parar o banco. Dados privados selecionados permanecem criptografados. O código usa
pg_restore apenas para **renderizar texto**; valida exatamente COPY dos três campos
da migração001, datas com fuso, IDs únicos/positivos e tipos ASCII de evento. O SQL
original do dump nunca é enviado ao psql. A Base reconstrói o schema confiável.

Destino exige mesma versão principal PostgreSQL e contrato compatível. Reinspeção
sob lock global SETUP; tabela bloqueada transacionalmente, teste de vazio novamente
sob lock e inserção com SQL fixo/valores validados. Dados já idênticos são existentes;
qualquer conteúdo diferente bloqueia. Nenhum dropdb, `--clean`, adoção automática,
substituição de banco ou migração destrutiva. Sequência de IDs é reconstruída pelo
maior ID recuperado: não preserva lacunas de IDs anteriormente excluídos. Objetos,
migrações futuras e recuperação PostgreSQL genérica ficam **fora do escopo/pendentes**.

## Prévia, remapeamento e serviços

A prévia verifica todo o pacote em memória, compara nomes/versões, origem e conta
antigas, HOME/conta atuais, novo caminho de mídia informativo, serviços presentes e
estado de boot. Lista destinos ausentes/iguais/conflitantes, operações sem sudo e
dados ainda faltantes. Inspeção não cria diretório, arquivo ou linha SQL de dados.

Destino histórico: `~/.local/share/myct/recovery/<sha256-do-manifesto>/`, com básico,
`mapping.json` e catálogos selecionados. Não copia recibos de propriedade SETUP para
adotar a instalação anterior. Não move mídia, recria hardlinks, reescreve catálogos,
importa sessões/torrents ou restaura trabalhos em andamento. A repetição compara o
estado real e pode retomar itens ausentes sem duplicar/sobrescrever os concluídos.
Falha parcial exige nova inspeção/aprovação; não há rollback destrutivo automático.

A seleção Rclone só recebe remapeamento prefixado pelo HOME antigo; caminho externo
exige decisão humana. Arquivo preexistente divergente bloqueia; estado anterior de
nuvem sem seleção persistente faz **pular a aplicação** da seleção e informar pendência,
para não mudar o comportamento de jobs existentes. Não modifica rclone.conf ou OAuth.

Serviços não são criados/habilitados pela restauração: o proprietário reconstrói os
componentes ausentes pelos alvos SETUP-1/2/3, com seus próprios planos/aprovações,
antes de recuperar banco ou aplicar preferências. Dependências/units vêm desses
contratos confiáveis atuais, não das unidades do servidor antigo. Nenhum enable,
restart, stop, alteração de Prowlarr/Transmission/Jellyfin, firewall, SSHD ou SELinux
é parte da SETUP-4. Receitas e catálogos pendentes são mostrados novamente no resultado.

## Custódia humana da identidade

Em máquina confiável e pasta privada **fora do Git**, gerar identidade com
`age-keygen -o /caminho/privado/chave.txt`. Guardar esse arquivo separadamente do
backup, preferencialmente também uma cópia offline. O destinatário público age1
pode ser obtido com `age-keygen -y /caminho/privado/chave.txt`; só ele entra no plano
de exportação. Não colocar chave secreta em argumento, issue, screenshot ou conversa
com agentes. Se perder a identidade, o My-CT não consegue abrir o pacote privado.

Para recuperar, copiar o pacote por Arquivos/SFTP já existente. Na inspeção e novamente
na confirmação, colar privadamente somente a linha `AGE-SECRET-KEY-1…`, sem comentários.
Básico dispensa chave. Não há senha/chave universal ou custódia automática no APK.

Referências consultadas: [pg_dump consistente e riscos de SQL de origem](https://www.postgresql.org/docs/18/app-pgdump.html),
[pg_restore e renderização](https://www.postgresql.org/docs/18/app-pgrestore.html),
[age e custódia da identidade](https://github.com/FiloSottile/age).

## Testes e evidências

```sh
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py'
# Somente imagem/ambiente rootless de testes; não instalar pacotes no host:
podman build -f ops/setup/tests/Containerfile.recovery -t localhost/myct-setup4-test ops/setup/tests
python3 ops/setup/tests/validate_recovery_container.py artifacts/setup4/container-evidence.json
ANDROID_HOME=/home/sam/Android/Sdk ./android/gradlew -p android \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
git diff --check
```

Nesta sessão, a imagem de ferramentas foi preparada por `podman exec` com DNF num contêiner descartável e `podman commit`; o Containerfile acima é a reprodução equivalente, com contexto pequeno. Imagem base de teste SETUP-1 previamente disponível; somente nela foram adicionados
age/PostgreSQL. Testes rootless sem volumes/portas do host ou privileged, 1,5 GiB RAM,
1 CPU, 1024 PIDs. Dois HOME e dois clusters próprios, PostgreSQL **18.6**, `age` **1.3.1**,
credenciais/catálogos sintéticos e pequenos. A fixture não usa a configuração Rclone,
SQL, cofre ou arquivos privados do proprietário. Contêineres removidos após a prova.

[Evidência sanitizada](evidence/myct-setup4-fedora.json): exportação básica sem canário
de credenciais, dump real, criptografia, pacote com hashes válidos, recuperação dos
três campos dos eventos no outro cluster, remapeamento, repetição sem mudança,
inspeção sem extrair/inserir dados, conflito de banco bloqueado, versão principal
divergente simulada no manifesto recusada, identidade ausente/adulteração recusadas e unidade existente
preservada/inativa. Catálogos recuperados continuam fora da pasta ativa.

Primeira tentativa de integração recusou identidade porque o teste enviava também
comentários do age-keygen. Corrigida apenas a entrada da fixture para a linha secreta;
nenhuma validação foi relaxada. Não houve acesso a dados reais para essa correção.
Testes unitários adicionais cobrem diagnóstico incapaz de disparar restauração,
traversal, symlink/device/FIFO, compactação, hashes/schema adulterados, limite de
saída/coleta, Git como destino, bloqueio de lock, interrupção antes de publicação,
recuperação parcial e retomada, preservação de configuração divergente e estado de
nuvem anterior. Android testa comando fixo, chave exclusivamente em stdin, limites,
resposta versionada e limpeza da identidade mesmo com SSH indisponível.

## Roteiro Moto G06 sem dados pessoais

1. Atualizar sobre code46, **sem desinstalar**; confirmar `dev.myct`, code47 e dados
   existentes. Conferir o rótulo curto **Caminho do rclone.conf** e explicação separada
   **Deixe vazio para descobrir automaticamente**; a lógica Rclone não mudou.
2. Conectar SSH → Opções → Configurar servidor → Backup / Recuperação → Inventariar.
   Conferir categorias, exclusões, disponibilidade do age e proteção do PostgreSQL
   legado. Esse inventário não consulta SQL, não lê catálogos e não gera pacote.
3. Não selecionar dados privados nem aprovar exportação/restauração no Fedora pessoal.
   Para conferir falha segura, inspecionar somente um caminho de pacote inexistente.
   Sair/reabrir/reconectar e confirmar ausência de sucesso presumido ou repetição.
4. Conferir Saúde, Terminal, SFTP, Torrents, Busca, navegação/animações e seções anteriores,
   sem iniciar trabalhos de nuvem, alterar serviços ou transferir mídia.
5. Aceite de exportação/recuperação completa fica para ambiente descartável autorizado
   pelo proprietário, não para os dados pessoais. Não compartilhar chaves/capturas.

**Pendentes reais:** Moto SETUP-4; Fedora físico limpo; aarch64; teste de queda elétrica;
importação/ativação de catálogos e aplicação automática de receitas; restauração de
schemas/migrações além de001; mídia/torrents ativos e credenciais excluídos. As provas
de interrupção são exceções determinísticas e retomada por estado, não ensaio de queda
de energia. Nenhum upload ou teste Google Drive pertence a esta entrega.

Exigem autorização posterior: backup real (mesmo básico) do Fedora pessoal, dump,
instalação de age no host, recuperação, alteração de seleção/helpers/configurações,
serviços e qualquer acesso autenticado ao Drive. Auditorias globais Codex/Luna e merge
serão missões separadas, com relatórios independentes antes do confronto.

## Arquivos e referência para congelamento

Backend: `ops/setup/{setup.py,recovery.py,build_bundle.py,manifest.json}`.
Android: `BackupSetupModel.kt`, `BackupSetupScreen.kt`, `ServerSetup.kt`,
`ServerSetupModel.kt`, `ServerSetupScreen.kt`, `SshConnection.kt`, versão Gradle.
Testes: `BackupSetupTest.kt`, `test_recovery.py`, `validate_recovery_container.py`,
`validate_recovery_inside.py`, `Containerfile.recovery`. Documentos: este relatório,
evidência JSON, README/decisão e exclusão de formatos de backup no `.gitignore`.

O SHA exato do commit publicado será fornecido na entrega para congelamento. Nenhum
pacote de backup ou identidade privada integra a PR, release, APK ou este relatório.


## Resultado RC1 e artefatos

| Verificação | Resultado |
| --- | --- |
| Python SETUP-1/2/3/4 | 48 PASS, 0 falhas |
| JVM Android | 313 PASS, 2 SKIP, 0 falhas/erros |
| `assembleDebug` / `lintDebug` | PASS; lint 0 erros, 1 warning UseKtx e 2 hints anteriores |
| PostgreSQL18.6/age1.3.1 reais em fixture | Dump, criptografia, inspeção, recuperação em outro HOME/cluster e repetição PASS |
| Negativos | Conflitos, versão principal divergente simulada, corrupção, chave ausente, limites e caminhos perigosos recusados |
| Certificado contra APK code46 | Idêntico, apksigner |
| Moto SETUP-4 / Fedora físico limpo | NÃO VALIDADOS |
| Banco/Drive/serviços pessoais | Não acessados ou modificados pela recuperação; nenhum backup real |

SKIP existentes e condicionais à integração SSH real:
`FedoraHandshakeTest.realHostKeyMismatchAndTrustedNegotiationBeforeAuthentication`
e `IdeFedoraSftpTest.disposableFedoraFileThroughActualJschSftpRoundTripAndConflict`.
Não equivalem a testes físicos aprovados. A prova de incompatibilidade não executou
migração real PG17→18; divergiu a versão declarada e confirmou recusa antes de escrever.

Contêiner limitado a 1,5 GiB/1 CPU; instantâneo após prova **98,8 MB**, não pico.
Payload do APK **idêntico** ao testado na fixture:
`b149d01da57bd447f493300ae2939223a1ace80c03257284c137994b3bc263b5`.

APK **0.13.0-setup4-rc1**, pacote **dev.myct**, **versionCode 47**,
**19.371.276 bytes**. SHA-256:
`1c873289f6f5208160e19fab722c0bbaa52c10a1736c17a4122b91295be44d98`.
Certificado SHA-256 público preservado contra SETUP-3/code46:
`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`.
Atualização sem desinstalar; confirmação física de atualização/dados com o proprietário.

[Release candidata](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-setup4-v0.13.0-rc1) ·
[APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup4-v0.13.0-rc1/myct-setup4-0.13.0-rc1.apk) ·
[SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-setup4-v0.13.0-rc1/myct-setup4-0.13.0-rc1.apk.sha256).

Última execução de build/test/lint: **1 min 44,08 s**; RSS máximo relatado por `/usr/bin/time -v`: **2.168.028 KiB**. Não é medição de pico global do PC. Reservas/limites do Fedora pessoal não foram alterados. Logs locais ficam em `artifacts/setup4/`, fora do Git.

A prova final acrescentou marcador de migração002 na fixture e confirmou recusa de inspeção/recuperação, sem aplicar dados de um contrato futuro.
