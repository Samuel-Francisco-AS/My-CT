# My-CT R1 — Rodada 3/3: validação final e candidata code 52

Data: 22/10/2026. **Estado: APTO PARA ACEITE NO MOTO.**

Código validado e publicado: `ad181ad40aec15458b2ef9de90dd25d265d3abbe`,
[PR #62](https://github.com/Samuel-Francisco-AS/My-CT/pull/62), mantida em draft.
[Issue #61](https://github.com/Samuel-Francisco-AS/My-CT/issues/61) permanece aberta.
O aceite físico do proprietário ainda está pendente; não se declara a R1 encerrada.
Este registro acrescenta somente documentação ao código aprovado.

## Instalação Fedora

Instalados somente os três componentes autorizados em `~/.local/bin/`, a partir
do commit aprovado. Backup privado das versões anteriores, com hashes conferidos:

```text
~/.local/state/myct/helper-backups/r1-round3-20261022T182816Z-7bfcf827/
```

Diretório de backup 0700; manifestos privados 0600; cópias dos três originais
preservadas. Staging no mesmo filesystem, arquivos completos/fsync e substituição
por rename atômico, sob os locks existentes de delivery, catálogos, nomeação e Media.
O módulo de progresso foi ativado antes de seus consumidores.

| Componente instalado | Modo | SHA-256 igual ao código aprovado |
| --- | --- | --- |
| `myct-archive-torrent` | `0700` | `0c31ba3296070b64f82f25d3057862f18050fa77233c2bb981580d13d888eb3c` |
| `myct-cloud` | `0700` | `ca63feeba0515b35c4aa42b81e9cf00259d9de166dbf669aa665a94085422563` |
| `myct_cloud_progress.py` | `0600` | `a9e4088f7b6d9b773827cfb65907a16c0c463543619c1fb01bd00172565a805c` |

Sintaxe `compile()` em memória e importação dos componentes instalados: **PASS**.
UID 1000, arquivos regulares, nlink 1 e sem redirecionamento. Os seis complementos
inalterados foram apenas conferidos; não foram reinstalados. Os 16 recibos Cloud
estavam encerrados, sem trabalho ativo/incerto. Catálogos de arquivo/torrent,
índice Media e seleção remota permaneceram com os mesmos bytes antes/depois da
troca e da consulta. Não houve SETUP completo, alteração de serviços ou exclusão
real de mídia.

## Consulta real única

Executada **uma** chamada real da CLI instalada: `space` sobre o pacote de
**19.349.308.130 bytes** (aproximadamente 19,35 GB). Identidade do
pacote confrontada com o recibo histórico de 221,371415 s, sem publicar nomes,
caminhos pessoais ou token de confirmação.

| Medida | Segundos |
| --- | ---: |
| **Tempo total do comando instrumentado** | **0.218748** |
| Inventário local, incluindo biblioteca | 0.037155 |
| — metadados locais/manifesto | 0.012514 |
| — inventário da biblioteca | 0.024641 |
| Consulta Transmission | 0.053188 |
| Restante: início/importação, controles, instrumentação, serialização e demais fases | 0.128405 |

Método: processo Python `-I -B` carregando `~/.local/bin/myct-archive-torrent` e
chamando seu `main()` com `space`; RPC real do Transmission, telemetria de fases
capturada em memória e guardas que interromperiam qualquer tentativa de leitura
de payload, `digest_tree()`, `fingerprint()`, Rclone/Drive ou mutação RPC. O total
é a duração externa desse processo instrumentado; não mede SSH ou toque no Moto.
A compilação já havia terminado antes dessa consulta. Não foram feitas repetições,
médias ou leituras integrais do acervo.

Resultado: `ok:true`, contrato v2, inventário completo e **nenhum impedimento**;
**19 caminhos de arquivos do pacote, 13 vínculos Jellyfin gerenciados,
32 caminhos conhecidos, 19 inodes distintos e 2 diretórios**. Árvore original
presente e compatível por metadados; restauração ausente. Conteúdo e disponibilidade
remota explicitamente **não revalidados** nessa prévia.

Contadores observados: **0 tentativas de fingerprint, 0 de digest_tree, 0 de leitura
de payload, 0 de Drive e 0 mutações RPC**; apenas **2 consultas `torrent_get`** reais.
Aberturas de descritores para consulta de identidade por ioctl não são leitura de
conteúdo e foram permitidas pelos guardas.

A referência histórica foi **221,37 s**, com **214,95 s** de leitura/hashing.
É o mesmo pacote, mas são horários, caches, instrumentação e formas de execução
diferentes; o histórico era um trabalho do bridge. **Não se trata de comparação
controlada nem de tempo de resposta medido no Moto.**

## Verificações não destrutivas

Além da consulta, a função pura de projeção recebeu seleções de 0, 1 e 13 vínculos:
os conjuntos previstos tiveram respectivamente **19, 20 e 32 caminhos**, com
estimativas recalculadas e o mesmo token de inventário. **Nenhuma nova consulta
`space` ou RPC** foi feita para essas projeções; nenhum `release` foi executado.
Isso valida a projeção do servidor, não substitui observar a seleção no Android.
A economia continua sendo limite superior por inode, sem garantia de espaço
imediatamente recuperado no Btrfs.

## APK publicada

- [Única pre-release de teste — My-CT 0.13.5 RC1, code 52](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r1-space-v0.13.5-rc1).
- [APK](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r1-space-v0.13.5-rc1/myct-r1-space-0.13.5-rc1.apk) · [SHA-256](https://github.com/Samuel-Francisco-AS/My-CT/releases/download/myct-r1-space-v0.13.5-rc1/myct-r1-space-0.13.5-rc1.apk.sha256).
- Nome interno: `0.13.5-cloud-space-rc1`; pacote `dev.myct`;
  **18.320.249 bytes**; ABIs `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- SHA-256 APK: `3596ad73966f29c55c96055d7d88499db36865a294bc24d3aa0dfdcc11f4dd49`.
- Certificado SHA-256: `b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`,
  **idêntico ao da APK code 51**.

Build essencial: `:app:assembleDebug --offline`, **PASS, 30 s**. Sem repetição das
suítes completas aprovadas na implementação. `apksigner verify`, zipalign de
16 KiB, identidade/minSdk 26/targetSdk 36 e preservação das três ABIs: **PASS**.
As 17 fontes do bundle SETUP embutido e seu hash foram conferidos contra o commit
aprovado; nenhum SETUP foi executado. APK e checksum publicados foram baixados
novamente e conferidos byte/hash; os dois assets e a tag apontam à candidata
validada. Não houve instalação por ADB, desinstalação ou limpeza de dados no Moto.

## Aceite físico do proprietário

Instalar a code 52 **sobre a versão anterior**, sem desinstalar ou limpar dados:

1. Abrir Nuvem e um pacote arquivado; tocar Consultar espaço / Prévia e observar
   aproximadamente o tempo até aparecer o inventário.
2. Marcar/desmarcar episódios Jellyfin e conferir alteração imediata dos caminhos
   e estimativas, sem novo loading nem tarefa Fedora.
3. Abrir Liberar espaço, conferir os caminhos e consequências e **cancelar**.
4. Sair da tela, retornar dentro da validade de até 600 s e conferir reuso do
   inventário; qualquer confirmação anterior deve continuar revogada.

**Não confirmar liberação nem executar `release` no acervo pessoal.** Os resultados
físicos acima estão **PENDENTES**, não foram simulados como aceite. Nenhuma falha
concreta foi encontrada na instalação, consulta ou empacotamento desta rodada.
Logs e manifestos privados: `artifacts/r1-round3-validation/`, ignorados pelo Git.

Esta é a última rodada técnica. Sem nova investigação, implementação ou quarta
rodada. Após o aceite Moto, consolidação da PR e encerramento da R1 serão feitos
pelo GitHub, conforme determinado pelo proprietário.

## Aceite físico posterior e encerramento — 22/10/2026

**Aceite de usabilidade reportado pelo proprietário no Moto G06 (code 52):** consulta de espaço percebida como mais rápida e prática; mudanças de seleção funcionam sem precisar repetir a consulta. Capturas mostram o painel de espaço e liberação com 18,0 GiB para o pacote e o painel Transmission com aproximadamente 19,3 GB, sem indicação de divergência: para os **19.349.308.130 bytes** do pacote, a conversão dá aproximadamente **18,02 GiB** e **19,35 GB**. São unidades binárias e decimais diferentes, não um erro de estimativa. O painel mantém a ressalva de que a liberação é um limite superior; snapshots, reflinks, hardlinks e arquivos abertos podem reter blocos.

O aceite refere-se à melhora de desempenho, apresentação e ausência de uma segunda carga obrigatória. **Não houve execução de liberação destrutiva real no acervo pessoal nem medição de duração ponta a ponta com cronômetro no Android**. Os tempos instrumentados do Fedora não substituem uma medição controlada do toque no aparelho. Este aceite posterior supera a anotação anterior de “PENDENTE” para o fluxo de consulta e seleção.

**Fechamento funcional da R1:** três rodadas concluídas, sem quarta rodada. Integração final da PR #62 à `main` e encerramento da issue #61 são os atos de consolidação documental e de repositório, sem instalações adicionais de helpers nem nova APK. A exclusão local permanente continua independente de backup Cloud.
