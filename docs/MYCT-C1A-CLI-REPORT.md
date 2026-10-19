# MYCT-C1A — CLI de arquivamento de arquivos comuns

**19/10/2026 — candidata para revisão da Luna; aceite real pendente.**
Implementação da [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46)
na branch `myct-c1a-google-drive-archive`, conforme as
[instruções de execução](https://github.com/Samuel-Francisco-AS/My-CT/issues/46#issuecomment-6277599705).
Planejamento geral: [MYCT-C1](MYCT-C1-ARQUIVAMENTO-NUVEM.md).

## Entrega e aceite

`ops/myct-archive` é um helper Python 3, seguindo os adaptadores operacionais
`myct-media`/`myct-torrent`. Usa somente a biblioteca padrão e o Rclone externo;
não acrescenta backend, banco, daemon, timer ou instalação. O Core C++20 e o
Android não participam desta entrega. Apenas arquivos comuns, um por comando,
com origem estável durante toda a operação.

Critério automatizado definido antes da implementação: copiar uma fixture,
validar tamanho e MD5 remoto, listar o registro e restaurar bytes idênticos em
destino novo, mantendo o original; divergência, falha ou interrupção não podem
produzir sucesso. **Mocks aprovados não equivalem a aceite no Google Drive.**

## Contrato CLI / SSH

Executar a partir do checkout revisado. Comandos abaixo são um roteiro
**para o proprietário após configurar o acesso privado**, não evidência de
execução nesta entrega:

```bash
./ops/myct-archive status
./ops/myct-archive archive '/caminho/absoluto/fixture-do-proprietario.bin'
./ops/myct-archive list
./ops/myct-archive restore ID_RETORNADO_NO_ARCHIVE
```

Remote padrão: `myct-gdrive:`. Pode ser alterado por `MYCT_ARCHIVE_REMOTE` ou
por `--remote meu-drive:` **antes** da ação. Aceita somente nome de remote
configurado (letra seguida de letras, números, `_` ou `-`, terminado em `:`),
sem parâmetros de backend, credenciais ou caminho livre. Pasta remota fixa:
`My-CT-Archive/`. O helper nunca executa configuração ou autorização OAuth.

| Comando | Resultado |
| --- | --- |
| `status` | `rclone about REMOTE --json`: acesso e campos de cota efetivamente retornados, em bytes. Campo ausente não significa cota zero nem espaço ilimitado. Não verifica todos os objetos. |
| `list` | Catálogo local deste remote, incluindo cópias falhas/não verificadas e última restauração. Funciona sem rede/Rclone; `remote_checked: false` explicita que não é uma varredura atual do Drive. |
| `archive ARQUIVO` | Copia para `REMOTE:My-CT-Archive/ID/NOME`, confere origem e objeto remoto, registra resultado. Preserva conteúdo, nome e links do original. |
| `restore ID` | Reconfere o remoto, baixa para staging privado e publica em `~/Downloads/My-CT/Restored/ID/NOME` somente após comparar tamanho, MD5 e SHA-256 com o catálogo. |

Uma linha JSON no stdout por execução, código `0` (sucesso) ou `2` (erro).
Exemplo de erro:

```json
{"ok":false,"error":{"code":"VERIFICATION_UNAVAILABLE","message":"MD5 remoto indisponível; cópia não verificada, preserve o original."}}
```

`archive` retorna `entry` e `original_preserved: true`; `restore` retorna `id`,
`state: restored`, `path` e os algoritmos conferidos. Nomes são escapados pelo
JSON. Sem shell interpolado, progresso periódico ou logs brutos do Rclone.

## Integridade, catálogo e repetição

- MD5 e SHA-256 locais calculados em blocos de 1 MiB; compara inode/dispositivo,
  tamanho, mtime e ctime antes/depois da leitura e relê após copiar. Mudança
  detectada impede `verified`. Não há snapshot ou bloqueio de outros escritores:
  o proprietário precisa escolher um arquivo estável.
- `copyto --ignore-existing --checksum` copia um arquivo, com uma transferência,
  um checker e download multithread desabilitado. `copyto` bem-sucedido sozinho
  não confirma integridade. `lsjson --hash` precisa encontrar exatamente um
  objeto no diretório do ID, com nome, tamanho e MD5 esperados. Nomes duplicados
  ou ambíguos são recusados.
- Sem MD5 remoto: estado `unverified`, erro e original preservado. O MVP não
  implementa fallback por download para validar o upload. A restauração requer
  cópia previamente verificada e confere também SHA-256 dos bytes baixados.
  Nenhuma entrada, inclusive `verified`, autoriza eliminar o original.
- ID: SHA-256 de remote, caminho absoluto da origem e SHA-256 do conteúdo.
  Repetir o mesmo arquivo reutiliza registro e objeto; nomes iguais em origens
  diferentes não colidem. Nova versão ou origem renomeada recebe outro ID.
  Objeto parcial/divergente existente nunca é reparado por sobrescrita.
- Catálogo: `~/.local/share/myct/archive/catalog.json`, versão 1, diretório
  `0700`, registro/lock `0600`. Guarda ID, tipo `file`, origem/nome, tamanho,
  hashes, remote/caminho, datas, estado, código da falha e última restauração
  iniciada. Escrita por temporário + `fsync` + substituição atômica, sob
  `flock` exclusivo sem espera. Catálogo inválido bloqueia operações.
- Estados de cópia: `pending`, `verified`, `failed`, `unverified`. Restauração:
  `pending`, `restored`, `failed`. Uma restauração falha preserva o estado
  validado da cópia. `list` mostra o resultado da última tentativa registrada.
- Destino existente (mesmo com bytes iguais), symlinks e traversal são recusados.
  Publicação por hardlink atômico do staging, sem substituição. Esse hardlink é
  interno ao restore; não envolve biblioteca de mídia nem `myct-media`.
- Só temporários da própria operação são limpos automaticamente; originais e
  objetos remotos nunca são removidos. SIGINT/SIGTERM encerram o processo
  Rclone e registram falha quando possível. SIGKILL/queda de energia podem
  deixar `pending` e staging órfão, nunca considerados sucesso.

## Falhas e intervenção humana

| Código/condição | Próximo passo |
| --- | --- |
| `RCLONE_NOT_FOUND`, `AUTH` | Proprietário instala/configura ou reautoriza em contexto privado. O helper não solicita tokens pela CLI. |
| `QUOTA`, `NETWORK`, `RCLONE_ERROR` | Conferir cota/rede/configuração e repetir; classificação textual é de melhor esforço, sem publicar stderr bruto. |
| `SOURCE_CHANGED` | Estabilizar a origem e repetir; eventual cópia remota divergente exige revisão manual. |
| `VERIFICATION_UNAVAILABLE` | MD5 ausente: preservar original e revisar backend; não declarar backup validado. |
| `INTEGRITY`, `REMOTE_COLLISION` | Revisar cópia parcial, corrupção ou nomes duplicados. Helper não apaga/substitui objetos para reparar. |
| `CATALOG_CORRUPT`, `UNSAFE_PATH`, `LOCAL_IO` | Preservar registros; recuperar cópia privada válida/revisar permissões e disco. Nunca reinicializa silenciosamente o catálogo. |
| `DEST_EXISTS`, `LOCAL_SPACE` | Resolver destino/espaço manualmente antes de repetir; sem opção de sobrescrita. |
| `BUSY`, `INTERRUPTED`, `pending` após queda | Consultar catálogo/aguardar operação existente. Repetir archive estável reutiliza ID e verifica o objeto existente. Publicação ocorrida antes de falhar a gravação do catálogo requer conferência manual do destino. |

Configuração Rclone fica fora do Git. Pode herdar `RCLONE_CONFIG` (caminho
privado) e `RCLONE_CONFIG_PASS` (configuração criptografada já provisionada pelo
proprietário), sem imprimir valores. Outras variáveis `RCLONE_*` são removidas
do ambiente do subprocesso para evitar logs/dumps de credenciais e alterações
nas regras de transferência. Usar configuração existente, sem overrides de
backend por ambiente. O helper não leu configurações OAuth nesta entrega.

**Primeira intervenção humana necessária: antes do primeiro `status` real.**
Proprietário confere inventário, SSD, remote, client ID próprio, OAuth privado,
cota livre e vencimento efetivo do plano. Instala pacotes se necessário. Não
enviar `rclone.conf`, segredos, tokens, JSON ou URLs de autorização ao chat/PR.

**Após revisão da Luna**, executar os quatro comandos com uma fixture pequena
própria, comparar SHA-256 original/restaurado e confirmar original intacto.
Registrar somente evidência sanitizada. Até esse aceite, C1A é candidata;
C1B/torrents e C1C/Android não começam.

## Validação automatizada — 19/10/2026

Fedora 44, Python **3.14.7**, GitHub CLI **2.97.0**. Rclone **1.74.3** presente,
consultado apenas com `rclone version`; nenhum comando do helper usou esse
binário real. Sem instalações.

```bash
python3 -m unittest discover -s ops/tests -p 'test_archive.py' -v
python3 -m unittest discover -s ops/tests -p 'test_media*.py'
python3 -m unittest discover -s ops/tests -p 'test_torrent.py'
git diff --check
```

| Validação | Resultado |
| --- | --- |
| Novo helper | **29 testes PASS**, Rclone falso executável e fixtures temporárias |
| Regressão helpers de mídia | **25 testes PASS**, 0,079 s |
| Regressão adapter de torrent | **17 testes PASS**, 1,051 s |
| Whitespace do diff | **PASS** |

Suíte C1A medida com `/usr/bin/time`: **8,75 s** de parede, **6,94 s** de CPU
usuário, **1,46 s** sistema, pico RSS **25.696 KiB**. Esses números medem a
suíte com processos simulados, não transferências reais. O helper usa buffer
de leitura fixo; catálogo/listagem ocupam memória proporcional ao catálogo.

Cobertura: ida/volta, arquivo vazio, original preservado, permissões e JSON;
remote configurável/segregado; repetição/mesmo nome/versões; tamanho/MD5
positivos e negativos, MD5 ausente, objeto ausente/duplicado; quota/rede/OAuth
com logs sanitizados; Rclone ausente; origem alterada; cópias parciais; recusa
de sobrescrita inclusive em corrida; symlinks/diretório/FIFO; corrupção e
traversal no catálogo; falha de escrita atômica; concorrência/pouco disco;
SIGTERM do helper encerrando o filho e interrupção no upload/restore.

CLI testada com `HOME` temporário e `PATH` contendo **somente o fake**, sem
configuração Rclone do proprietário. O fake só aceita `about`, `copyto` e
`lsjson` e confere flags conservadoras. Regressões usam os simuladores
existentes, sem RPC contra Transmission/Jellyfin reais.

Primeira rodada: 26/27 PASS; diretório era recusado com erro genérico porque
`fdopen` precedia a conferência do tipo. Hipótese/teste mínimo: validar `fstat`
antes de abrir stream. Correção aplicada; suíte final acrescenta pouco disco
e restore interrompido: 29/29 PASS.

Limites: sem medição de transferência real, retomada por offset, execução
desacoplada do SSH, importação fora do catálogo, backup remoto do catálogo ou
preservação de ACL/xattrs. Faça backup privado do catálogo para restaurar por
ID após perda do SSD. MD5 confere integridade compatível com Drive; não é
assinatura de autenticidade. Torrents, seeding e liberação de espaço são C1B.

**Não realizados:** instalação/deploy, sudo, OAuth, consulta de cota real,
upload/download real, exclusão de originais/remotos, mudanças em serviços,
timers, Transmission/Jellyfin, Android ou PostgreSQL. Nenhum merge.

## Referências de contrato

- [Rclone copyto](https://rclone.org/commands/rclone_copyto/): cópia de arquivo
  único; pode sobrescrever destino sem a proteção explícita usada aqui.
- [Rclone lsjson](https://rclone.org/commands/rclone_lsjson/): nomes, tamanho e
  hashes; `--hash` não garante MD5 disponível no backend.
- [Rclone about](https://rclone.org/commands/rclone_about/): cota em bytes e
  campos que podem não ser retornados.
- [Rclone flags](https://rclone.org/flags/): `--ignore-existing`, concorrência,
  timeouts e logs.
- [Drive: client ID próprio](https://rclone.org/drive/#making-your-own-client-id):
  orientação para configuração privada pelo proprietário.

---

## Aceite real no Fedora / Google Drive — 19/10/2026

**Estado atualizado: C1A aprovada funcionalmente pelo proprietário no Fedora 44, com fluxo real de upload e restauração concluído.** Este aceite complementa a seção histórica acima sobre testes simulados; a PR de implementação permanece **draft, sem merge**, até autorização de integração.

**Evidência fornecida pelo proprietário (sem credenciais OAuth):**

- `rclone about myct-gdrive:`: conta autenticada e acessível, cota total 400 GiB, livre cerca de 399,3 GiB.
- `python3 -m unittest discover -s ops/tests -p 'test_archive.py' -v`: **29 testes PASS** em **8,279 s** no Fedora do proprietário.
- `./ops/myct-archive status`: `ok:true`, `remote_accessible:true`, `catalog_entries:0` antes do teste; cota reportada em bytes.
- Arquivo de teste temporário **37 bytes**, criado em `/home/sam/myct-c1a-teste.xq9m5Y`, enviado para o remote `myct-gdrive:`, pasta `My-CT-Archive/`, com retorno `state:verified`, tamanho **37**, MD5 remoto compatível **08e09e7276204d54935b61fd75dccc55**, `original_preserved:true`.
- `restore` retornou `ok:true`, `state:restored` com comparações `size`, `md5`, `sha256` realizadas; destino isolado em `~/Downloads/My-CT/Restored/`.
- `sha256sum` do original e do restaurado coincidiram exatamente:
  `548fe656fef4e83aeb4205b5916ab57b06153c8ff268c835678b14b8cfd3a7cb`.
- `cmp` concluiu sem divergência e o script do proprietário imprimiu **`PASS: arquivo restaurado identico ao original!`**.

**Resultado da auditoria:** fluxos básicos de cópia e recuperação foram comprovados usando Drive real, sem perda de bytes. A operação não incluiu apagar conteúdo local/remoto, modificar Transmission/Jellyfin nem retomar seeding. Regressões extras de mídia e torrent (42 testes) foram relatadas pelo executor Codex; não foram reexecutadas nessa etapa física. Limitações registradas (backup do catálogo, transporte no SSH, tratamento de cópias divergentes, ausência de gerenciamento de torrent) continuam como dívidas/etapas posteriores.

**Próximos passos:** integrar a PR #47 à `main` **após autorização do proprietário** e sincronizar o Fedora; C1B tratará torrent completo, metadados e hardlinks, em outra branch. Manter a fixture e os arquivos remotos até decisão posterior sobre limpeza manual.
