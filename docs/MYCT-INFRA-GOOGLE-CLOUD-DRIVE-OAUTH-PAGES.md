# My-CT — Infraestrutura Google Cloud, OAuth, Google Drive e GitHub Pages

**Registro operacional:** 19/10/2026  
**Escopo:** infraestrutura externa criada para o `My-CT Archive` (MYCT-C1).  
**Estado na data do registro:** conexão Rclone/Google Drive testada no Fedora; C1A aceita e integrada à `main` pela [PR #47](https://github.com/Samuel-Francisco-AS/My-CT/pull/47); C1B em desenvolvimento pelo Codex, ainda sem aceite; C1C planejada.  
**Referência de acompanhamento:** [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46).

> **Guia de manutenção, não arquivo de credenciais.** O documento descreve nomes, decisões e procedimentos, mas não contém Client ID, Client Secret, refresh/access token, conteúdo do `rclone.conf`, código OAuth de uso único ou endereço de autorização com `state`. A situação da conta Google Cloud e da assinatura foi informada/observada durante a configuração pelo proprietário; não existe integração de consulta automática ao painel Google Cloud neste repositório.

## 1. Visão geral e finalidade

A funcionalidade **My-CT Archive** permite que o Fedora copie arquivos selecionados do armazenamento local para **Google Drive**, verifique integridade, registre a operação e restaure o conteúdo posteriormente. O projeto visa preservar mídias e outros arquivos importantes sem exigir um servidor pago. Na evolução C1B, um torrent poderá ser preservado como **unidade completa**, com metadados suficientes para verificar e eventualmente retomar seeding no Transmission depois de recuperar os dados localmente.

Componentes e responsabilidades:

| Componente | Nome/local | Responsabilidade |
| --- | --- | --- |
| Google Cloud | Projeto `My-CT-Drive` (ID `my-ct-drive`) | Configuração de API, consentimento e cliente OAuth |
| API habilitada | **Google Drive API** | Operações autorizadas de arquivo/pasta na conta Google |
| Plataforma OAuth | **Google Auth Platform** | Identidade do app, audiência, escopos e clientes |
| Cliente OAuth | **My-CT**, tipo **Desktop app** | Autenticação do Rclone em ambiente de aplicação instalada |
| Permissão solicitada | `https://www.googleapis.com/auth/drive.file` | Acesso por arquivo criado/aberto pela integração, sem solicitar todo o Drive |
| Site informativo | [Página do My-CT](https://samuel-francisco-as.github.io/My-CT/) | Página inicial indicada no cadastro do app |
| Política de privacidade | [privacy.html](https://samuel-francisco-as.github.io/My-CT/privacy.html) | Explica finalidade, tratamento de dados e revogação |
| Hospedagem | GitHub Pages, branch **`gh-pages`** | Publicar somente o site estático e a política |
| Fedora | Fedora Workstation 44, operando headless | Executa Rclone, CLI de arquivamento e Transmission |
| Rclone | Remote **`myct-gdrive:`** | Gerencia OAuth e as transferências para o Drive |
| Arquivo remoto | `myct-gdrive:My-CT-Archive/` | Raiz lógica usada pela CLI para objetos arquivados |
| CLI C1A | `ops/myct-archive` | `status`, `list`, `archive`, `restore`; transferência manual e não destrutiva |
| Catálogo local | `~/.local/share/myct/archive/catalog.json` | Relaciona ID, origem, hashes e objeto no Drive |
| Restauração local C1A | `~/Downloads/My-CT/Restored/` | Recebe arquivos restaurados sem sobrescrever originais |
| Transmission | RPC local do My-CT | Torrent e seeding; integração C1B ainda depende de aceite |
| Android | My-CT no Moto G06 | Terminal SSH/Termux usados na autenticação; UI de arquivamento será C1C |

### Fluxo da arquitetura

```text
Moto G06 (navegador / terminal durante autorização)
       |   SSH com encaminhamento LOCAL TEMPORÁRIO (porta 53682)
       v
Fedora headless
  +-- Rclone: remote myct-gdrive: <---- OAuth --> Google Auth Platform
  |          |
  |          +-------------------------------> Google Drive API
  |                                                |
  |                                      My-CT-Archive/
  +-- ops/myct-archive
  |     +-- catálogo privado local
  |     +-- copiar / validar / restaurar
  |
  +-- Transmission e Jellyfin (separados durante C1A)

GitHub Pages (site público + política)
       |
       +-- usado como referência informativa no cadastro OAuth
           NÃO recebe tokens, arquivos nem callback do Rclone
```

**Importante:** GitHub Pages não é backend de autenticação e não recebe dados de usuários pela integração. A autorização OAuth do Rclone ocorre via servidor local no Fedora, acessado temporariamente pelo navegador do celular através do túnel SSH.

## 2. Google Cloud: o que foi criado e como localizar

**Console:** https://console.cloud.google.com/  
**Projeto:** `My-CT-Drive`. **ID do projeto:** `my-ct-drive`.

Passos executados em 19/10/2026:

1. Criado projeto próprio para a integração My-CT, **sem ativar período de teste gratuito ou vincular faturamento** durante a configuração relatada pelo proprietário.
2. Localizada e **habilitada a Google Drive API** em **APIs e serviços / Biblioteca** para esse projeto.
3. Na **Google Auth Platform / Branding**, configurada a identidade exibida como **My-CT** e os dados básicos solicitados pelo console.
4. Em **Audience / Público-alvo**, a aplicação é **Externa (External)**. Inicialmente estava em **Testing**, com a conta proprietária adicionada como **usuária de teste**.
5. Em **Clients / Clientes**, criado **cliente OAuth 2.0 do tipo Desktop app**, com nome **My-CT**. **Não** é um cliente Android e **não** envolve publicação de APK ou Google Play Store.
6. Em **Data access / Acesso aos dados**, adicionado o escopo `https://www.googleapis.com/auth/drive.file`. No Rclone, esse escopo foi escolhido pela opção **`3`**.
7. Após disponibilizar site/política no GitHub Pages, inseridas na configuração de marca as URLs públicas e o domínio autorizado **`samuel-francisco-as.github.io`**, conforme as opções exibidas no console.
8. Por fim, publicada a aplicação OAuth. Na tela do proprietário, o **status de publicação passou a “Em produção”**.

**“Em produção” não significa que o Google verificou ou aprovou publicamente a marca, que a API é ilimitada, nem que existe um app na Play Store.** É o status do fluxo de consentimento OAuth. Contas externas, escopos e eventuais exigências de verificação dependem das regras do Google; revisar se o console apresentar alertas.

**Faturamento:** não foi associado no procedimento de criação relatado. Isso **não** significa que toda API/serviço futuro no projeto seja gratuito. Não habilitar faturamento, serviços adicionais ou cotas pagas sem decisão do proprietário. A assinatura pessoal **Google One (400 GB)** é separada do projeto Google Cloud e já existia.

**NÃO constam aqui:** identificador e segredo reais do cliente OAuth, e-mail pessoal, tokens e conteúdo de telas confidenciais. O ID de projeto, URL do site e nome do remote não são credenciais.

## 3. GitHub Pages: página e política de privacidade

O site público foi publicado usando uma branch **`gh-pages`**, isolada do código principal da aplicação.

- Repositório: [Samuel-Francisco-AS/My-CT](https://github.com/Samuel-Francisco-AS/My-CT).
- Branch de publicação: [`gh-pages`](https://github.com/Samuel-Francisco-AS/My-CT/tree/gh-pages).
- Arquivo [`index.html`](https://github.com/Samuel-Francisco-AS/My-CT/blob/gh-pages/index.html): apresentação do projeto, integração com o Drive e link para a política.
- Arquivo [`privacy.html`](https://github.com/Samuel-Francisco-AS/My-CT/blob/gh-pages/privacy.html): dados acessados, finalidade, armazenamento local de credenciais, não compartilhamento automático, controle, exclusão, limitações e contato.
- Página inicial usada no OAuth: https://samuel-francisco-as.github.io/My-CT/
- Política de privacidade usada no OAuth: https://samuel-francisco-as.github.io/My-CT/privacy.html
- Domínio autorizado na plataforma Google: `samuel-francisco-as.github.io`.

O proprietário confirmou que as páginas foram publicadas e ficaram acessíveis. O conteúdo foi conferido no repositório remoto em 19/10/2026.

**Manutenção:**

- **Não excluir ou mesclar `gh-pages` na `main`**: é uma branch de hospedagem, não uma branch de desenvolvimento a ser limpa após PR.
- Preservar os arquivos e URLs existentes enquanto forem referenciados pelo consentimento OAuth.
- Se o site for renomeado, transferido de repositório ou receber domínio próprio, atualizar as URLs no console Google, links internos e a política; conferir a página ao vivo.
- Se mudar a forma de coleta, transmissão ou uso de dados, revisar a política antes de afirmar que ela continua correta.
- O site estático não precisa receber `rclone.conf`, arquivos do Drive, código de autorização, client secret, APK ou logs.

## 4. Rclone no Fedora: configuração efetivamente validada

**Instalação executada pelo proprietário:**

```bash
sudo dnf install rclone
```

No assistente `rclone config`, foi criado o remote **`myct-gdrive`** com provedor **`drive`**, Client ID e Client Secret próprios (digitados privadamente) e escopo **`drive.file`**, opção **`3`**.

Configurações importantes observadas:

| Campo / escolha | Valor adotado |
| --- | --- |
| Remote | `myct-gdrive` |
| Storage/backend | `drive` (Google Drive) |
| Client ID / Client Secret | Credenciais do cliente **Desktop app**, sem publicá-las |
| Scope | `drive.file` (opção `3` no assistente utilizado) |
| `root_folder_id` | Vazio: raiz do Drive autorizado |
| `service_account_file` | Vazio; usa OAuth do proprietário |
| `client_credentials` | `false` |
| `auth_owner_only` | `false` |
| `use_trash` | `true` (padrão; não autoriza exclusões pela CLI) |
| `shared_with_me`, `trashed_only` | `false` |
| Shared Drive / Team Drive | **Não** (`n`) |
| Pasta do My-CT | `My-CT-Archive/`, gerenciada pela CLI |

As demais opções avançadas apresentadas foram mantidas em seus padrões. Em nova configuração, escolher **`n` em “Edit advanced config?”**, salvo se houver um motivo específico para alterar algo. Não recriar/remover o remote existente apenas para refazer o assistente.

### OAuth no Fedora sem navegador: como foi feito

O Rclone abriu um listener temporário em **`127.0.0.1:53682`**, exibiu um endereço de autorização semelhante a `http://127.0.0.1:53682/auth?state=...` e aguardou em `Waiting for code..`.

Como o Fedora estava headless, o proprietário manteve o Rclone aguardando e abriu outra sessão **Termux no Moto G06**, criando um túnel SSH:

```bash
ssh -N -L 53682:127.0.0.1:53682 sam@IP_DO_FEDORA
```

Com o túnel ativo, abriu no navegador do Moto **o endereço completo, temporário, emitido pelo Rclone naquela execução**. Após consentir na tela Google, o Fedora exibiu **`Got code`**. Foi escolhido **`n`** para Shared Drive e a configuração do remote foi salva.

**O redirecionamento local observado foi `http://127.0.0.1:53682/`.** O número da porta corresponde à sessão local do Rclone; não é um endpoint público nem requer port forwarding no roteador. O listener do Fedora recebe o callback pelo túnel SSH. O hostname/IP real do Fedora deve ser obtido na rede atual, não presumido eterno.

**Notas de recuperação:**

- O código `state`/URL de autorização é efêmero e **nunca** deve ser registrado em documentação, issue ou conversa.
- Se a autorização não terminar, não copiar tokens para o GitHub; verificar se o Rclone ainda está em `Waiting for code..` e se o túnel está ativo.
- Se a porta local já estiver ocupada no Moto, encerrar o túnel antigo antes de tentar novamente; não matar serviços sem identificar o processo.
- Fechar o túnel SSH depois da autorização. Ele não é necessário para transfers normais.
- No console OAuth o cliente é **Desktop app**; não trocar por Android ou Web application por causa do uso de navegador no celular.
- Se o OAuth deixar de funcionar, conferir status do consentimento Google, acesso revogado, alterações do cliente, escopo, cota, conectividade e mensagens de erro sanitizadas.

### Credenciais no disco (não versionar)

A localização efetiva do arquivo privado pode ser consultada com:

```bash
rclone config file
```

Na configuração padrão de Linux, costuma ser `~/.config/rclone/rclone.conf`, salvo mudanças por flags/ambiente. **Esse arquivo contém material sensível** (inclusive tokens OAuth e possivelmente segredo do cliente). Não incluir em Git, PRs, capturas de tela, logs, backups públicos, scripts ou APK. Manter permissões restritas e planejar cópia **criptografada, privada e recuperável**, quando necessário.

A CLI `ops/myct-archive` respeita a configuração existente do Rclone e não cria nem renova manualmente clientes OAuth. Eventuais credenciais não são armazenadas na pasta do site GitHub Pages.

## 5. Validação real que aprovou a C1A

Comandos utilizados no Fedora:

```bash
rclone about myct-gdrive:
cd ~/Projetos/My-CT
python3 -m unittest discover -s ops/tests -p 'test_archive.py' -v
./ops/myct-archive status
```

Evidências reportadas:

- `rclone about` respondeu: **Total 400 GiB**, **Used 400.314 MiB**, **Free 399.322 GiB**, **Trashed 0 B**. Essa foi uma fotografia do momento, não uma capacidade livre garantida para sempre.
- 29 testes automatizados da C1A: **PASS** no Fedora do proprietário, em **8,279 s**.
- `myct-archive status`: `ok: true`, `remote_accessible: true`, remote `myct-gdrive:`, `catalog_entries: 0` antes da fixture.
- Teste real de ida/volta: arquivo de **37 bytes** enviado à pasta `My-CT-Archive/` e retornado em `state: verified` com MD5 verificado.
- Restauração no Fedora: `state: restored`, com validação de tamanho, MD5 e SHA-256, preservando o original.
- SHA-256 idêntico dos dois arquivos; `cmp` sem divergência. Detalhes no [relatório C1A](MYCT-C1A-CLI-REPORT.md).
- PR [#47](https://github.com/Samuel-Francisco-AS/My-CT/pull/47) integrada à `main` em **`bbf929886a1c66d2b0927b2ca3f8c46fa25cfe7b`**; documentação de fechamento posterior também registrada.

**Não foi testado ainda como parte da C1A:** arquivar torrent completo, remover original após backup, recuperar seeding, restaurar hardlinks da biblioteca ou configurar streaming Jellyfin direto do Drive.

## 6. Operações rotineiras e diagnóstico

Consultas não destrutivas:

```bash
# Conferir se o remote existe (não mostra tokens)
rclone listremotes

# Conferir API, OAuth e armazenamento acessível
rclone about myct-gdrive:

# A partir da raiz do projeto
cd ~/Projetos/My-CT
./ops/myct-archive status

# Mostrar o catálogo LOCAL, não uma listagem online de todo o Drive
./ops/myct-archive list

# Opcional: listar diretórios criados/visíveis no arquivo remoto
rclone lsd myct-gdrive:My-CT-Archive/
```

O comando `list` do My-CT **não confirma a existência atual de cada objeto no Google Drive**; serve para consultar registros locais e precisa ser interpretado com esse limite.

**Escopo `drive.file`:** o app pode operar nos arquivos criados/abertos sob sua autorização. Isso **não** significa que o Rclone poderá listar/manipular arbitrariamente todos os documentos preexistentes do Drive, nem que arquivos arquivados ficam públicos.

### Problemas comuns

| Sintoma | Conferir / ação inicial |
| --- | --- |
| `AUTH`, `invalid_grant`, solicitação de nova autorização | Consentimento/cliente OAuth, eventual revogação de acesso, estado `Em produção`, necessidade de reautorizar privadamente |
| `NETWORK` / timeout | Internet no Fedora, DNS, conectividade HTTPS e serviço Google |
| `QUOTA` / sem espaço | `rclone about`, armazenamento utilizado, situação da assinatura Google One |
| `rclone listremotes` sem `myct-gdrive:` | Arquivo de configuração correto, usuário Fedora e ambiente `RCLONE_CONFIG` |
| Há arquivos no Drive, mas faltam no `myct-archive list` | Catálogo local ausente/desatualizado; não reconstruir IDs por adivinhação |
| `REMOTE_COLLISION` / `INTEGRITY` | Objeto duplicado, alterado ou hash divergente; conservar origem e diagnosticar antes de repetir |
| Arquivo remoto inacessível apesar da cota responder | Escopo/permissões por objeto e caminho, não apenas validade do token |
| GitHub Pages fora do ar | Configuração Pages, branch `gh-pages`, URLs e arquivos HTML; isso **não** é o motor de transferência |

### Preservação para recuperação de desastre

Há **dois ativos privados distintos** para preservar:

1. **Credenciais do Rclone** (configuração/token): permitem autenticar a conta. Devem ficar em backup privado e criptografado, nunca no repositório.
2. **Catálogo do My-CT** (geralmente `~/.local/share/myct/archive/catalog.json`): permite reencontrar IDs, origens e hashes das operações C1A. Guardar cópia **privada** para não perder o vínculo com objetos no Drive após uma falha de SSD.

Também é necessário manter acesso à conta Google e ao projeto OAuth. **A existência de uma pasta `My-CT-Archive/` no Drive não substitui o catálogo.** A CLI C1A não implementa, por si só, recuperação automática do catálogo perdido. Não presumir que mover apenas arquivos físicos de um servidor para outro preserva automaticamente o funcionamento da CLI.

**Não apagar mídia local apenas porque `rclone about` ou `status` funcionou.** O aceite exige arquivamento e verificação por objeto; para torrents, também exige manifesto, metadados e restauração validada em fase posterior.

## 7. Custo, cota, disponibilidade e renovação

- O armazenamento informado é uma **assinatura Google One de 400 GB**, válida por aproximadamente um ano a partir da época relatada. **A data exata de vencimento/renovação ainda não foi confirmada**: consultar a assinatura na Conta Google e registrar separadamente em controle privado.
- Cota e disponibilidade variam: executar `rclone about myct-gdrive:` antes de arquivar volumes grandes.
- O console Google Cloud foi inicialmente configurado **sem adesão a trial e sem vinculação de billing**, segundo o proprietário. Qualquer mudança posterior nessa situação deve ser registrada.
- A API do Drive tem políticas, limites e eventual verificação de consentimento; status `Em produção` não garante acesso eterno nem ausência de alertas futuros.
- Planejar restauração dos arquivos importantes **antes** de qualquer redução de cota ou encerramento do plano. Para dados irrecuperáveis, considerar também um backup independente do Google Drive.
- Transferências de grandes arquivos consomem rede, tempo e espaço de staging/restauração no SSD; testar sobre arquivos pequenos antes de lotes reais.
- Publicação do site em GitHub Pages não hospeda os arquivos arquivados e não substitui a assinatura de armazenamento.

## 8. Situação do projeto e coordenação com o Codex

**C1A — concluída:** CLI de arquivos comuns, upload/restore íntegros, OAuth/Rclone configurados e PR #47 integrada.

**C1B-1 — Codex em trabalho:** inventário de torrents inteiros, manifesto, metadados, arquivamento e restauração não destrutivos. **Não presumir PR publicado ou merge** até conferir o GitHub. A branch planejada no prompt é **`myct-c1b-torrent-roundtrip`**, com PR **draft**. Primeiro teste em torrent real depende de revisão e autorização do proprietário.

**C1B-2 — posterior:** retomada real de seeding após verificação pelo Transmission e eventual liberação explícita de espaço, respeitando hardlinks e conteúdo inteiro do torrent. **Não implementar exclusão automática nesta rodada**.

**C1C — posterior:** interface Android que aciona operações via SSH existente.

### Regra de integração entre trabalhos paralelos

Este documento foi criado como **arquivo novo, somente documental, diretamente na `main`**, enquanto o Codex desenvolve a C1B em sua própria branch. A mudança **não altera** `ops/myct-archive`, `ops/myct-torrent`, o planejamento C1, o README ou os arquivos da branch `gh-pages`.

Ao finalizar a C1B, o Codex deve:

1. Atualizar referências de `origin/main` e **integrar a base atual** na branch de trabalho (merge ou rebase escolhido conscientemente), sem sobrescrever seus commits.
2. Conferir que o documento de infraestrutura continua acessível após o merge e que o PR altera somente arquivos pretendidos.
3. Entregar testes, relatório e PR draft para auditoria. **Não realizar merge automaticamente.**
4. Aguardar o proprietário aprovar o teste físico de um torrent pequeno e o fechamento padrão pela Luna.

**Manter a issue #46 aberta** enquanto C1B/C1C estiverem pendentes e **preservar a branch `gh-pages`** independentemente de limpezas de branches de desenvolvimento.

## 9. Referências rápidas

### Repositório e documentação

- [Issue #46 — MYCT-C1](https://github.com/Samuel-Francisco-AS/My-CT/issues/46)
- [PR #47 — MYCT-C1A](https://github.com/Samuel-Francisco-AS/My-CT/pull/47)
- [Planejamento de arquivamento](MYCT-C1-ARQUIVAMENTO-NUVEM.md)
- [Relatório e aceite da C1A](MYCT-C1A-CLI-REPORT.md)
- [Relatório BitTorrent MYCT-5C](MYCT-5C-BITTORRENT-REPORT.md)
- [Guia de gerenciamento de armazenamento e hardlinks](MYCT-MEDIA-STORAGE-CLEANUP.md)
- [Página pública do My-CT](https://samuel-francisco-as.github.io/My-CT/)
- [Política de Privacidade](https://samuel-francisco-as.github.io/My-CT/privacy.html)

### Painéis externos e documentação de fornecedor

- [Google Cloud Console](https://console.cloud.google.com/)
- [Google Cloud — Google Auth Platform](https://console.cloud.google.com/auth/overview)
- [Google Drive API](https://developers.google.com/workspace/drive/api/guides/about-sdk)
- [Rclone — Google Drive](https://rclone.org/drive/)
- [Rclone — uso em computador headless](https://rclone.org/remote_setup/)
- [GitHub Docs — GitHub Pages](https://docs.github.com/en/pages)

---

**Nota de manutenção:** atualizar este registro **somente quando a infraestrutura de fato mudar**: novo projeto/cliente OAuth, revisão do escopo, alteração de URLs/branch Pages, troca do remote, perda/recuperação das credenciais, backup do catálogo, data confirmada da assinatura ou mudança no fluxo operacional. Evitar duplicar aqui o histórico completo do código; usar os relatórios C1A/C1B/C1C para implementação e testes.
