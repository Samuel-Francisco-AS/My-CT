# MYCT-C1B-1 — torrent integral: arquivar, restaurar e preparar seeding

**19/10/2026 — Fechamento: C1B-1 aceita funcionalmente para inspect/archive/restore/recover pausado no Fedora, PR #48 incorporada à `main` no merge `fce805ea96bc108a85b466133c6724b4688a329c`. Retomada de seeding efetivo NÃO testada.**
Branch `myct-c1b-torrent-roundtrip`, criada da `main` sincronizada em `a831b87`,
árvore inicialmente limpa. A C1A/PR #47 permanece aceita e intacta.
Planejamento e continuidade: [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46)
e [MYCT-C1](MYCT-C1-ARQUIVAMENTO-NUVEM.md).

## Escopo e aceite desta entrega

CLI complementar `ops/myct-archive-torrent`, Python 3/biblioteca padrão. Sem
alterações Android, Core, PostgreSQL, executáveis instalados, OAuth, rclone.conf,
serviços, configuração Transmission ou biblioteca Jellyfin.

Antes de codificar, os critérios foram: inventariar o pacote completo; manter
extras, `.torrent`, seleção e manifesto; não sobrescrever originais/remotos;
validar tamanho/hashes; restaurar em árvore isolada; só permitir retomada após
verificação integral observada. Testes simulados não substituem teste físico.
> **Histórico anterior ao gate real:** quando o Codex publicou a candidata nenhum torrent real havia sido transferido. Esse estado foi superado pelo aceite físico documentado no fechamento abaixo.

## Arquitetura e persistência

- Reutiliza o RPC JSON-RPC 2.0 de `myct-torrent`, no endpoint loopback existente.
  A identidade é sempre infohash hexadecimal v1 completo, nunca ID numérico/all.
- Reutiliza de `myct-archive` o runner Rclone, neutralização de overrides de
  debug/backend, classificação sanitizada de erros, cópia, fingerprints,
  lock de catálogo, escrita atômica e fsync. O código C1A não foi alterado.
- Reutiliza `myct-media-cleanup.snapshot`/`myct-media` para reconhecer hardlinks
  por inode e biblioteca. Não usa a seleção de vídeos como lista do torrent:
  a lista original vem do RPC/.torrent e a árvore local é percorrida inteira.
- Catálogo separado: `~/.local/share/myct/archive-torrents/catalog.json`, versão
  1 própria, diretório `0700`, arquivos `0600`. **Não migra nem mistura entradas
  com o catálogo v1 da C1A.** Uma operação por catálogo; também usa os locks de
  nomeação por hash e de mídia existentes, na mesma ordem do delete-data.
- Metadados privados em `archive-torrents/bundles/ID/{original.torrent,manifest.json}`.
  Manifesto versionado contém caminhos, tamanhos, MD5/SHA-256, seleção/prioridades,
  estado/diretório originais e referências de hardlinks. Não contém credenciais
  OAuth/configurações ou listas de trackers. O `.torrent` original é binário
  privado e pode conter dados privados de tracker: não o anexar a Git/PR/logs.
- Drive: `myct-gdrive:My-CT-Archive/torrents/ID/`, contendo `data/`,
  `original.torrent` e `manifest.json`. O ID deriva do remote, infohash, hashes
  dos arquivos e do `.torrent`. Não depende da entrada atual no Transmission.
  A primeira fotografia de manifesto permanece imutável na repetição.

O helper é síncrono, manual, sem timer/daemon, banco ou dependência nova. Rclone
executa uma transferência/checker por vez, sem download multithread. Não há
subcomando de exclusão, liberação de SSD, rclone move/sync/purge ou reparo remoto.

## Comandos e contrato

Executar no checkout revisado; não é necessário instalar para o teste futuro:

```bash
cd /home/sam/Projetos/My-CT
./ops/myct-archive-torrent status
./ops/myct-archive-torrent list
./ops/myct-archive-torrent inspect INFOHASH_V1_COMPLETO
./ops/myct-archive-torrent archive INFOHASH_V1_COMPLETO
./ops/myct-archive-torrent restore ID_DO_ARQUIVAMENTO
./ops/myct-archive-torrent recover ID_DO_ARQUIVAMENTO
./ops/myct-archive-torrent recover ID_DO_ARQUIVAMENTO --resume
```

`--remote NOME:` antes da ação, ou `MYCT_ARCHIVE_REMOTE`, segue a mesma validação
da C1A; padrão `myct-gdrive:`. Não aceita URLs/configuração OAuth como argumento.
Uma linha JSON por execução, exit 0 em sucesso e 2 em erro. Erros incluem
`ok:false`, `state:blocked|failed`, `error:{code,message}` sanitizado. Falhas de
arquivamento também informam a tentativa de recuperar o estado anterior; conferir
o estado real com `inspect` antes de repetir. `list` é catálogo local, com
`remote_checked:false`; não prova que o Drive continua íntegro naquele momento.

### inspect — somente leitura

Retorna nome/hash, código e rótulo de estado, diretório, conclusão, arquivos do
torrent com tamanho/conclusão/seleção/prioridade, **todos** os arquivos da árvore
com tamanhos, extras não listados no RPC, diretórios, metadados recuperáveis,
hardlinks conhecidos, possíveis vínculos externos, impedimentos e `can_archive`.

`total_size` soma a árvore de dados completa, inclusive extras; `storage_bytes_minimum`
acrescenta o `.torrent` recuperável. O manifesto e overhead são adicionais;
`archive.storage_bytes` informa o total exato dos objetos arquivados. Falta de
SSD para essa segunda árvore aparece em `restore_blockers`; `restore` faz a
checagem completa, com manifesto e margem de diretórios, antes de baixar.

Não cria catálogo, locks, diretórios nem pausa o torrent. Usa conclusão RPC e
tamanhos locais como prévia; o conteúdo das peças é conferido no `archive`.
Vínculos externos são uma contagem por nlink descontando nomes locais/biblioteca
conhecidos; não é uma varredura de todos os caminhos do computador. Hardlinks
extras não impedem uma **cópia não destrutiva** e não autorizam liberar espaço.

### archive — preserva o pacote e termina pausado

Exige metadados completos, `percent_complete` 100% (não apenas percent_done dos
selecionados), have_valid total, nada unchecked, ausência de erro, arquivos
presentes/tamanhos corretos e estrutura correspondente ao `.torrent` original.
Arquivos desmarcados são aceitos somente se também integralmente presentes.
Symlinks/arquivos especiais, renomeações internas e estruturas ambíguas bloqueiam.

O leitor limitado de bencode confirma o SHA-1 dos bytes exatos de `info` e
confere localmente as peças, inclusive cruzando fronteiras entre arquivos.
Assim, um cache RPC ainda marcando 100% não permite arquivar corrupção de mesmo
tamanho. Não há implementação de peers, trackers ou cliente BitTorrent.

Registra intenção, consulta cota efetiva quando informada, recusa ID remoto sem
proveniência local, pausa pelo RPC e espera status 0 **antes de copiar**.
Preserva arquivos, extras, diretórios vazios via manifesto e metadados originais.
Só usa `copyto --ignore-existing --checksum`; objetos presentes precisam ter
tamanho/MD5 esperado. IDs/objetos duplicados ou divergentes bloqueiam, sem reparo
por sobrescrita. Após a cópia, relê a árvore e compara hashes e snapshots
inode/dev/tamanho/mtime/ctime, confere os objetos remotos e o estado pausado.

Sucesso: `state:verified`, `original_preserved:true`, `hardlinks_preserved:true`,
`paused:true`, verificações remotas de tamanho/MD5 e SHA-256 registrado. **Não
se afirma SHA-256 remoto pelo Google Drive.** MD5 ausente impede confirmação.
Para retomar o original, a resposta fornece `myct-torrent resume INFOHASH`;
conferir primeiro se origem/estado continuam os mesmos.

Em falha/interrupção, preserva dados e registra erro; tenta recuperar execução
anterior somente se era seeder completo, na mesma localização e com toda a
fotografia local inalterada. Dados alterados, RPC incerto ou estado divergente
podem deixar pausado e exigem revisão. Não repete mutações ambíguas.

Repetir conteúdo/metadados idênticos reutiliza ID/objetos, sem upload duplicado;
cota é exigida apenas para objetos faltantes. Parcial divergente permanece
intocado e bloqueia a repetição até revisão manual. A chamada explícita de
archive pode pausar novamente um original que o proprietário retomou.

### restore — integridade local, sem tocar no Transmission

Exige catálogo verificado e revalida todos os objetos remotos. Reserva uma pasta
exclusiva em `~/Downloads/My-CT/Torrents/archive-ID-HASH8/`; copia com staging no
mesmo filesystem e publicação por hardlink sem substituição. A árvore mantém
nomes relativos e diretórios vazios. Confere tamanho, MD5, SHA-256, manifesto e
metadados/infohash. O `.torrent` continua em bundle privado, fora do payload.

Retorna `state:verified`, algoritmos e `seeding_ready:false`: bytes restaurados
não equivalem a torrent recuperado. Uma árvore existente só é reutilizada após
reconferência integral; conteúdo divergente nunca é substituído. Falhas deixam
árvore parcial marcada failed/blocked; não existe reparo por overwrite nem
limpeza automática dos dados parciais. Só scratch desta execução é removido.

### recover — fluxo implementado, ainda não executado realmente

Reconfere hashes locais e peças antes de mutações. Reutiliza entrada com o
mesmo infohash/estrutura e diretório original ou restaurado; outro diretório
exige revisão. Ausência de entrada permite reimportação usando **metainfo
original em base64 no corpo RPC**, inicialmente pausada, sem magnet/URL.

Confirma pausa, recupera seleção/prioridades, aplica `torrent_set_location`
com **move:false**, espera localização e registra a pasta restaurada pelo
mecanismo de nomes existente. O lock impede o hook de conclusão de renomear
durante a operação; o registro done conserva compatibilidade com myct-torrent
e descoberta de fontes do myct-media.

Solicita `torrent_verify` e exige observar fila/verificação (status 1/2), depois
status 0 e 100% **de todos os arquivos**, have_valid total, nada unchecked/erro
e fotografia local inalterada. Timeout máximo de verificação: 600 s; pausa e
localização têm 15 s. **Se a verificação for tão rápida que a transição não seja
observada, bloqueia após 5 s (`VERIFY_UNOBSERVED`).** Não há override inseguro;
esse caso e o comportamento real da versão instalada precisam do teste controlado.

Sem `--resume`, termina pausado com `seeding_ready:true` apenas após essas provas.
`--resume` repete toda a validação e só então solicita start; retorna estado RPC
real observado. Não promete peers ou tráfego de upload. Em falha após mutação,
tenta parar e nunca recupera execução anterior automaticamente. Uma entrada
incompatível é rejeitada **sem pausá-la**. Em RPC incerto, verificar no cliente
Transmission local antes de repetir ou corrigir registros.

### Hardlinks/Jellyfin e continuidade C1B-2

Nada é removido/recriado na biblioteca nesta entrega. Os hardlinks existentes
continuam apontando para os inodes originais; restauração usa novos inodes e
não substitui mídia preexistente. O manifesto preserva source relativo e destino
Media para reconstrução posterior.

Após auditoria e autorização dessa etapa, a reconstrução deve chamar o mecanismo
**`myct-media.add_one`**, sob `state.lock`, para fonte restaurada reconhecida e
destino previsto/validado por `safe_relative`. O mecanismo verifica inode,
recusa destino ocupado com outro conteúdo e aplica o acesso Jellyfin já existente.
Respeitar ignored/decisões anteriores. Um simples sync não conserta referências
antigas já registradas; não limpar índice nem remover/substituir links para
forçar reimportação. Prévia/reconstrução guiada e qualquer liberação efetiva de
SSD ficam na C1B-2, com confirmação distinta e proteções de myct-media-cleanup.

## Testes e custo observado

**96 testes PASS**: 25 novos C1B-1, 29 C1A, 17 torrent, 25 mídia (8 media,
8 cleanup, 5 batch e 4 library). RPC e Rclone C1B-1 são fakes explícitos; C1A
usa executável Rclone falso em PATH isolado. Sem credenciais remotas nos fakes.
Execução agregada: **12,292 s** nos testes, 12,44 s de parede medidos pelo
`/usr/bin/time`, CPU usuário/sistema 7,51/1,59 s, pico RSS **31.032 KiB (~30,3 MiB)**.
São fixtures e fakes, não consumo/throughput de um upload real ou de torrent
grande. Logs locais em `artifacts/myct-c1b-regression.log` e
`artifacts/myct-c1b-regression-time.txt`, ignorados pelo Git.

```bash
python3 -m unittest discover -s ops/tests -p 'test_archive*.py' -v
python3 -m unittest discover -s ops/tests -p 'test_torrent.py' -v
python3 -m unittest discover -s ops/tests -p 'test_media*.py' -v
python3 -m py_compile ops/myct-archive-torrent
git diff --check
```

Cobertura: multifile/extras/legenda/zero bytes/pasta vazia; single-file; pacote
e metadados idênticos na volta; ausência da entrada/cache original; seleção e
prioridades; hardlinks preservados; pausa não confirmada; rede/autenticação;
parcial, corrupção, arquivo ausente/alterado, MD5 indisponível, destino concorrente,
ID sem proveniência, repetição sem overwrite, pouco SSD, locks, metadados/JSON
malformados, companions ausentes, RPC sanitizado e verificação incompleta,
pendente/não observada sem start. Nenhum teste chama torrent_remove ou exclusão
remota. Catálogo C1A sentinela foi preservado byte a byte.

Ambiente: Python 3.14.7, Rclone 1.74.3 já instalado, Git 2.55.0, Fedora 44.
Não foi executado Rclone real além de `version`; cota/OAuth/throughput real não
foram retestados. APK/build Android não executados por não fazerem parte do escopo.

## Limitações e roteiro histórico de teste (pré-aceite)

- Máximo 1000 arquivos e 1000 diretórios; cache `.torrent` até 4 MiB; RPC herdado
  limita respostas a 256 KiB. Só torrents gerenciados My-CT, metadados v1 e
  caminhos originais; híbridos/v2, padding/atributos especiais e renomeações
  internas são recusados nesta etapa.
- Não é snapshot de filesystem nem trava clientes RPC externos. Não editar os
  dados/seleção/localização por outro cliente durante as operações. Locks cobrem
  operações My-CT conhecidas; importações/limpeza de mídia podem aguardar a cópia.
- Restauração requer o catálogo local preservado. Manifesto e `.torrent` no Drive
  permitem recuperação independente do **registro Transmission**, mas importação
  automática de catálogo perdido não foi implementada. Proteger também esse estado.
- O teste físico de Drive/Transmission, observação de verificação rápida, arquivo
  grande, custo real e recuperação de seeding permanecem pendentes. Não liberar SSD.

**Texto histórico do roteiro antes do aceite real; não repetir automaticamente no acervo atual.** O proprietário escolhe e conclui um torrent **pequeno,
público, permitido e descartável**, dedicado ao teste, sem usar filmes/séries ou
pacotes existentes como cobaias. Não remover entradas/dados para preparar o teste.
Após revisão da Luna e autorização explícita, no checkout revisado:

```bash
cd /home/sam/Projetos/My-CT
HASH='SUBSTITUIR_PELO_INFOHASH_DE_TESTE_COM_40_HEXADECIMAIS'
./ops/myct-archive-torrent status
./ops/myct-archive-torrent inspect "$HASH"
# Conferir blockers, todos os arquivos e espaço. A próxima ação pausa a fixture.
./ops/myct-archive-torrent archive "$HASH"
./ops/myct-archive-torrent list
ID='SUBSTITUIR_PELO_ID_RETORNADO'
./ops/myct-archive-torrent restore "$ID"
# Conferir state:verified, hashes/árvore e preservação dos originais/hardlinks.
./ops/myct-archive-torrent recover "$ID"
# Confirmar seeding_ready:true e paused:true. Só com autorização para retomar:
./ops/myct-archive-torrent recover "$ID" --resume
```

Não instalar nada para rodar diretamente do checkout. Se o proprietário optar
por instalar após auditoria, manter os companions atuais `myct-archive`,
`myct-torrent`, `myct-media`, `myct-media-cleanup` ao lado do novo helper e executar
`install -Dm0700 ops/myct-archive-torrent "$HOME/.local/bin/myct-archive-torrent"`.
Git não atualiza cópias instaladas. Nenhuma dessas ações foi executada pelo agente.

Referências técnicas consultadas: [RPC Transmission 4.1.3](https://github.com/transmission/transmission/blob/4.1.3/docs/rpc-spec.md),
[implementação verify](https://github.com/transmission/transmission/blob/4.1.3/libtransmission/torrent.cc),
[Rclone lsjson](https://rclone.org/commands/rclone_lsjson/),
[copyto](https://rclone.org/commands/rclone_copyto/).


## Fechamento funcional real no Fedora — 19/10/2026

**Decisão do proprietário:** encerrar e integrar C1B-1. Aceite funcional de inventário, upload, restauração e recuperação pausada; **não houve execução de `recover --resume`, início comprovado do seeding, peers ou tráfego de upload**. C1B-2 e C1C não implementadas.

### Evidência física fornecida no diálogo

- Codex informou **96 testes simulados PASS** (25 C1B, 29 C1A, 17 Torrent, 25 Media). O proprietário compartilhou apenas o trecho final da execução local (`Ran 25 tests in 0.078s / OK`), não os logs completos dos outros 71 testes. A revisão estática da Luna não substitui execução independente da suíte.
- `status` respondeu do Drive real: `remote:myct-gdrive:`, quota total 429496729600 bytes, livre 428768951945 bytes no instante da consulta. `list` retornou catálogo local C1B inicialmente vazio (`entries:[]`), com `remote_checked:false`.
- `inspect`: torrent v1 gerenciado, com três arquivos (vídeo principal e dois arquivos auxiliares), 614775873 bytes totais, completo e em status RPC 6 (seeding). Metainfo `.torrent` recuperável de 47512 bytes, um hardlink gerenciado do vídeo para biblioteca Jellyfin, nenhum link externo apontado; `blockers:[]` e `can_archive:true`.
- `archive`: `ok:true`, `state:verified`; 614825177 bytes no Google Drive contando dados, metainfo e manifesto; verificação remota de tamanho/MD5, SHA-256 registrado localmente; `original_preserved:true`, `hardlinks_preserved:true`, `paused:true`. Nenhuma exclusão local ou remota.
- `restore`: espaço livre reportado antes da operação: 27 GB no filesystem `/home`. Restauração isolada retornou `state:verified`, `reused:false`, com comparação de tamanho/MD5/SHA-256 e metainfo/infohash. `seeding_ready:false` nessa etapa era esperado.
- `recover` **sem `--resume`**: `ok:true`, `state:verified`, `seeding_ready:true`, `resume_requested:false`, `paused:true`, `rpc_status:0` e `library_links_modified:false`. A CLI observou a verificação de peças pelo Transmission sobre a árvore restaurada e indicou que os bytes são válidos sem download dos peers. **Não comprova seeding ativo.**

### Estado pós-teste e limitações do aceite

- Entrada Transmission fica **pausada** e passou a referenciar a **árvore restaurada** com `move:false`. O original e seu hardlink Jellyfin permanecem intactos. As duas árvores ocupam SSD; não apagar nenhuma por dedução de economia.
- Cópia Drive permanece arquivada. A restauração depende do catálogo **e bundle locais privados** `~/.local/share/myct/archive-torrents/`; salvar backup privado, sem publicar `.torrent` ou credenciais Google/Rclone no Git.
- Não foi autorizado remover arquivo original, destruir torrent, alterar hardlinks, limpar nuvem, habilitar cron ou modificar serviço. Nenhuma dessas ações faz parte do merge.
- A retomada real (`--resume` e tráfego/peers) permanece como **gate pendente**, preferencialmente com torrent livremente redistribuível. Não prometer que já voltou a compartilhar.
- Torrent v1 gerenciado, estrutura de caminhos original, limite de 1000 arquivos/dirs e metainfo até 4 MiB continuam sendo as restrições deste incremento.

### Próximo trabalho: C1B-2

Implementar primeiro **prévia operacional de ocupação real do SSD** (inodes/hardlinks, originais, biblioteca, cópia restaurada e Transmission), sem exclusões. Depois avaliar liberação guiada de pacotes já verificados com autorização separada, sem danificar o Jellyfin e sem confundir unlink com espaço recuperado. A reconstrução de hardlinks deve reutilizar o mecanismo Media existente, sem sobrescrever links. Validar a retomada efetiva usando conteúdo autorizado. Android C1C segue etapa posterior.

**Fechamento remoto:** PR #48 integrada à `main` no merge `fce805ea96bc108a85b466133c6724b4688a329c` após registrar estas evidências. A issue #46 deve permanecer aberta. Preservar a branch `gh-pages`, a infraestrutura Google OAuth e a [documentação correspondente](MYCT-INFRA-GOOGLE-CLOUD-DRIVE-OAUTH-PAGES.md).
