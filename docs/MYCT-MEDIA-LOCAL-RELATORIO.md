# My-CT — identidade estável e exclusão local independente

## Contrato atual

Esta correção na PR #60 sucede a reconciliação real já executada: os 264 registros
reparados, os backups e o recibo anterior permanecem preservados. Desenvolvimento
somente no checkout; nenhum helper pessoal foi substituído nesta entrega e nenhuma
mídia pessoal foi usada em teste de exclusão. Não houve merge nem novo aceite Moto.

`st_dev` identifica o dispositivo no contexto da montagem atual; não é usado como
identidade persistente de Btrfs. `record_matches()` recebe a origem esperada do
consumidor, compara origem/destino atuais por device/inode, mantém ID
`hash:arquivo`, inode registrado, metadados e UUID/subvolume quando disponíveis.
Quando o device muda, exige identidade Btrfs atual coerente nos dois caminhos;
um UUID/subvolume registrado diferente continua divergente. Não escreve o cadastro
nem lê conteúdo de vídeo. Com origem já ausente no fluxo de arquivamento, a
identidade persistente registrada e os metadados permitem reconhecer o arquivo;
registros legados sem origem e sem identidade persistente continuam sem prova
suficiente para aceitar uma alteração de dispositivo.

Uma renumeração normal não exige nova reconciliação para os hardlinks cuja origem
atual é comprovada. O aplicador anterior continua disponível para diagnóstico e
enriquecimento explícito de cadastros históricos; suas categorias de reancoramento
não são um requisito de seleção/exclusão local. A limitação histórica dos registros
antigos não se transforma em uma alegação de integridade de conteúdo.

## Duas ações, uma confirmação

- **Retirar da biblioteca:** remove apenas os caminhos selecionados em Media.
  Outros caminhos, inclusive originais, permanecem. Se o caminho removido era a
  última ligação existente, seus dados deixam de existir; o diálogo informa isso.
- **Excluir permanentemente:** mostra e remove os caminhos selecionados da
  biblioteca e os originais atuais comprovadamente correspondentes. Sem uma origem
  única comprovada, remove somente os caminhos locais exibidos. Nenhum outro
  episódio de um torrent multifile é incluído implicitamente.

Arquivos regulares no escopo podem ser selecionados independentemente de registro
Media, backup, torrent completo, erro, seeding e quantidade de hardlinks. O rótulo
«gerenciado» continua separado da permissão do proprietário para remover um alvo.
Não há consulta ao Google Drive, SHA-256 de vídeos ou gate de catálogo `verified`.

Uma prévia técnica sem consentimento adicional prepara automaticamente **um único
diálogo** com todos os caminhos e as consequências. `version=2`, seleção, alvos,
metadados e torrents afetados compõem o fingerprint. Android confere seleção e
resultado, não aceita o contrato antigo que apagava torrents inteiros, consome a
prévia uma vez e descarta a confirmação em navegação, segundo plano e reconexão.
A correção A01 e a política de autorização da Nuvem permanecem separadas.

Para remover originais de episódios selecionados, o Transmission recebe
`torrent_remove(delete_local_data=false)`: encerra download/seeding do torrent,
retira a entrada e **preserva todos os dados**. Só então o helper remove os alvos
exibidos. Os arquivos não selecionados continuam no disco, mas a transferência
correspondente deixa de ser gerenciada pelo Transmission. Falha RPC ambígua aborta
antes dos unlinks e exige nova consulta, sem repetição automática.

A ação existente de excluir **um torrent inteiro** continua distinguida de remover
somente sua entrada. Sua confirmação mostra o diretório, inclui explicitamente os
arquivos completos/parciais desse torrent e avisa sobre seeding e hardlinks
preservados. O pedido vincula hash e diretório exibido; alteração de diretório
recusa a execução. Não exige retirar os vínculos Jellyfin nem backup. A exclusão
parcial de episódios usa Armazenamento, nunca `delete_local_data=true`.

## Proteções e limites

Locks existentes na ordem delivery → catálogos → naming ordenado → Media protegem
os unlinks locais contra operações concorrentes do My-CT. Não se exige presença,
validade ou conteúdo de catálogos Cloud; apenas se respeita exclusão mútua. Caminhos
precisam estar nas raízes conhecidas, ser arquivos regulares do proprietário e não
conter symlinks. Diretórios são abertos componente por componente com
`O_DIRECTORY|O_NOFOLLOW`, mantidos por descritor e conferidos novamente antes de
cada unlink. Tamanho, inode, device, uid, mtime/ctime/nlink vinculam a prévia à
execução. Após um unlink próprio, ctime/nlink mudam legitimamente nos aliases;
as demais evidências continuam sendo revalidadas.

O catálogo Media é atualizado pelos mecanismos existentes após cada retirada
concluída; `ignored` impede recriação automática. Uma falha de disco depois de um
unlink não desfaz a exclusão nem garante atualização do catálogo: atualizar o
inventário é obrigatório antes de qualquer nova tentativa. Não há promessa de
transação atômica para um lote de exclusões. Alterações simultâneas externas ao
My-CT e acesso privilegiado não são eliminados por locks consultivos. Não se
promete liberação física quando outros hardlinks ou snapshots retêm extents.

As verificações de integridade, tokens e consentimentos do arquivamento,
restauração, `space` e `release` não foram removidos. Esses fluxos não são
pré-requisitos de exclusão local.

## Instalação e aceite

APK candidata `dev.myct`, `0.13.4-media-local-rc1`, code 51, três ABIs existentes,
mesmo certificado público anterior. Instalar sobre a versão atual sem desinstalar,
limpar dados ou Cofre. A candidata contém o bundle SETUP com hashes calculados a
partir destas fontes; a política de preservar helpers legados divergentes continua.
Instalar APK **não atualiza automaticamente** os helpers pessoais.

Antes do teste funcional completo, uma atualização supervisionada deve substituir
somente `myct-media`, `myct-media-cleanup`, `myct-archive-torrent` e `myct-torrent`
com backup privado, verificação de tarefas/locks e hashes. O módulo
`myct_media_reconcile.py` instalado permanece necessário e não muda nesta entrega.
Não executar SETUP completo ou migrar novamente os registros reconciliados. Esta
entrega não realizou essa instalação no Fedora pessoal.

No Moto: conferir seleção individual/múltipla, abertura de um único diálogo,
alvos completos e cancelamento. Para teste físico de exclusão usar posteriormente
uma fixture descartável aprovada pelo proprietário, com dois episódios e um extra:
apagar só um, conferir preservação do outro e do extra, e conferir retirada da
transferência. Não usar o acervo pessoal como condição de aceite. Após um reboot
normal futuro, conferir reconhecimento sem nova reconciliação. Testes físicos
Android e reboot não foram executados nesta entrega.

## Validação e publicação

Suíte operacional em container Fedora descartável, sem rede nem mounts pessoais:
**283 testes: 281 PASS, 0 FAIL, 2 SKIP**. Skips: systemd/linger do usuário ausente
no container nos dois testes de unidades transitórias. Inclui arquivamento,
restauração, limpeza, `space`/`release`, aplicador e 264 vínculos simulados sem
leitura de conteúdo, casos de UUID/subvolume divergente, exclusão individual em
multifile sem backup, preservação de extras/hardlinks, dados alterados durante
RPC, falha ambígua, locks e retirada parcial. As fontes dos quatro helpers testados
foram comparadas por SHA-256 com o checkout final.

SETUP: **58 PASS, 0 FAIL, 0 SKIP**. JVM: **336 testes, 334 PASS, 0 FAIL,
2 SKIP** (SSH/SFTP reais opcionais). Inclui seis testes de contrato Cleanup e três
de modelo: seleção exata, invalidação de resposta tardia, reconexão e ausência de
replay. Build Android e lint executados; sem erro, um aviso UseKtx e duas hints
AutoboxingStateCreation preexistentes. Dois avisos de condição sempre verdadeira
em IdeScreen preexistentes. Ajuste final dos botões em coluna para telas estreitas
validado por recompilação e lint, sem declarar teste visual em aparelho.

Comandos:

```sh
python3 -m unittest discover -s ops/tests -p 'test_*.py' -v
python3 -m unittest discover -s ops/setup/tests -p 'test_*.py' -v
./android/gradlew -p android :app:testDebugUnitTest --rerun :app:buildSetupBundle :app:assembleDebug :app:lintDebug --offline
./android/gradlew -p android :app:assembleDebug :app:lintDebug --offline --max-workers=2
```

Uma primeira rodada encontrou um mock com a assinatura antiga de record_matches;
o teste foi adaptado para a origem explícita e passou na suíte final. Não foi
relaxada a asserção de inventário completo da Nuvem. Logs locais estão em
`artifacts/media-local-rc1/` (ignorados pelo Git). O catálogo pessoal mantém o
SHA-256 final da reconciliação anterior; os hashes dos componentes instalados
continuam os do commit e65a8db. Nenhum teste destrutivo no acervo pessoal.


### Identidade da candidata

| Campo | Valor |
| --- | --- |
| Pacote | `dev.myct` |
| VersionName / code | `0.13.4-media-local-rc1` / **51** |
| ABIs conferidas no APK | arm64-v8a, armeabi-v7a, x86_64 |
| SHA-256 APK | `da3f4595159234421d80c48c6313b16e6204ba5248aec82f22cf1819d3653e1c` |
| Certificado SHA-256 | `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3` |
| SHA-256 payload SETUP | `9e07c04f49ba39ca6473dae206a05ee96090c8f1a54edc2c338202f4fd41ccc7` |
| SHA-256 diagnóstico SETUP | `e79d8456812e89d2b98e050fb2b194b7532b2a73b61f8f4750d062029966c3c3` |

`apksigner verify --verbose --print-certs` aprovou a assinatura; a fingerprint
coincide com a APK code 49 baixada da release anterior (cujo hash também confere
com o asset publicado). `aapt dump badging` confirmou pacote, versões e SDK
26–36; as três ABIs e os três assets SETUP foram comparados no ZIP final.
Regeneração independente do bundle produziu os mesmos três arquivos; 17/17
fontes e seus hashes correspondem ao checkout. Nenhum catálogo ou credencial
pessoal participa do empacotamento. É APK debug assinada com o certificado já
utilizado pelo projeto, compatível com atualização sobre a candidata anterior.

A remoção parcial usa a semântica documentada de preservação de dados do
[RPC Transmission](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md#36-removing-a-torrent).
A identidade de inode deve considerar o subvolume, como descreve a
[documentação Btrfs](https://btrfs.readthedocs.io/en/latest/btrfs-subvolume.html#inode-numbers).
O comando de exclusão do torrent inteiro agora exige `delete-data HASH DIRETORIO`;
clientes antigos sem o diretório não podem emitir a nova exclusão implicitamente.

- `myct-media`: SHA-256 `6adec176d0158e5793c1a8c6bdd54f7b0666908777e123a16a916a5cc9456694`.
- `myct-media-cleanup`: SHA-256 `572ce87f53b9983867e598f102666e2bf9c150ed4ad25a4f8cc1d049e82cfeda`.
- `myct-archive-torrent`: SHA-256 `a3af7e040f558dae2f1a3f7404c29ed396fe1a393391e6ba56e63081b02b11e2`.
- `myct-torrent`: SHA-256 `3a4b820a0bc5e6ae7bf796bd39180dbe729b94a6e8413bde7117ce83f4f84581`.


### Publicação verificada

[Candidata code 51 publicada como pre-release](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-media-local-v0.13.4-rc1),
tag `myct-media-local-v0.13.4-rc1`, commit
`96e10261c14c80b1c8edf6f5ac1757308508088c`. O digest reportado pelo asset GitHub
coincide com o SHA-256 local registrado acima. Publicados somente APK e checksum;
nenhum helper pessoal, catálogo, backup ou material de assinatura privado.

Commits: `ddfb9c8` preserva o fechamento operacional anterior; `65b9aa1` corrige
identidade; `96e1026` entrega exclusão por alvos, Android, testes e documentação.
Registro final da publicação é um commit separado somente de documentação.
PR #60 confirmada OPEN/draft no HEAD da candidata; sem merge ou Ready for Review.
A pre-release anterior code 49 e sua tag não foram alteradas.

Instalação pessoal dos novos helpers e aceite físico permanecem pendentes,
conforme o procedimento acima. Nenhuma reconciliação adicional é necessária.


## Fechamento de entrega — 22/10/2026 (aceite Moto G06 e instalação Fedora)

**Aceite funcional visual reportado pelo proprietário:** APK candidata 0.13.4/code 51 instalada no Moto G06 sobre a versão anterior; biblioteca consultada com 18 títulos, armazenamento listou 279 arquivos; seleção individual e diálogo único de **Excluir permanentemente** exibiram o caminho original do Transmission e o hardlink Jellyfin, com efeitos sobre seeding; a confirmação foi cancelada, **sem exclusão real**; seção Nuvem abriu, exibiu os itens arquivados e ofereceu restauração. Não há aceite de exclusão destrutiva real nem de comportamento após reboot.

**Servidor pessoal:** os quatro helpers `myct-media`, `myct-media-cleanup`, `myct-archive-torrent` e `myct-torrent` foram instalados a partir de `267c5649bba582d9060bdb0a46bcc045ae39baa3` em `~/.local/bin/` (permissões 0700), com hashes iguais aos registrados na seção de identidade desta candidata. Manifesto `installation.json`: `succeeded`. Backup privado `~/.local/state/myct/helper-backups/media-local-20261022T022310Z-1708fd2e/`. Consultas não destrutivas: 279 arquivos selecionáveis, 278 gerenciados, 32 torrents reconhecidos; 16 tarefas Cloud encerradas. Nenhuma mídia pessoal foi excluída, nenhuma reconciliação repetida; dados, catálogos e serviços preservados. Os parágrafos históricos acima sobre instalação/aceite ainda pendentes descrevem o estado **antes** deste registro e estão superados neste ponto.

**Entrega integrada nesta fase:** correção da identidade de hardlinks/Btrfs, reconciliação histórica 264/264, exclusão local opcional sem dependência de Google Drive, seleção e diálogo único Android, manutenção do arquivamento/restauração e observabilidade parcial de progresso em `space`. Suíte reportada na candidata: 673 PASS / 0 FAIL / 4 SKIP somando operacional, SETUP e JVM; teste de exclusão destrutiva no Fedora pessoal não foi executado.

**Pendência explícita da MESMA R1 — NÃO RESOLVIDA:** o botão **Nuvem → Gerenciar → Consultar espaço / Prévia** continua com demora excessiva. Rastrear e corrigir em [issue #61](https://github.com/Samuel-Francisco-AS/My-CT/issues/61). A consolidação do trabalho funcional na `main` não equivale a aprovar a performance de `space`. Encerrada esta entrega funcional, **R1 ainda tem a pendência #61 antes do fechamento integral da fase**.
