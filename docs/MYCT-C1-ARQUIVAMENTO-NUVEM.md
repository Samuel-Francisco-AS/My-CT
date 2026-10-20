# MYCT-C1 — Arquivamento em nuvem e restauração de seeding

**Decisão:** 19/10/2026  
**Estado atual (19/10/2026): MYCT-C1A aceita e integrada à `main` via [PR #47](https://github.com/Samuel-Francisco-AS/My-CT/pull/47), merge `bbf929886a1c66d2b0927b2ca3f8c46fa25cfe7b`. Rclone configurado no Fedora pelo proprietário, OAuth próprio com acesso Google Drive; 29 testes locais PASS e arquivo de 37 bytes arquivado/restaurado com bytes idênticos. C1B-1 foi aceita funcionalmente com upload/restore real (~615 MB), verificação integral das peças pelo Transmission (recuperação pausada) e PR #48 integrada à `main` no merge `fce805ea96bc108a85b466133c6724b4688a329c`; **não houve retomada efetiva do seeding**. C1B-2/liberação de SSD e C1C/Android seguem não implementadas. Nenhum serviço extra ou exclusão realizados.

**Acompanhamento:** [issue #46](https://github.com/Samuel-Francisco-AS/My-CT/issues/46).  
**Proprietário:** 400 GB de armazenamento Google por aproximadamente um ano; espaço disponível e data exata de término **a consultar no início da implementação**, nunca presumir 400 GB livres.

**Incremento C1A (19/10/2026):** helper `ops/myct-archive` para arquivos comuns,
sem exclusão de originais/remotos, com `status`, catálogo `list`, `archive` e
`restore`. [Contrato, testes e intervenção humana](MYCT-C1A-CLI-REPORT.md).
Instalação do Rclone, OAuth, consulta de cota e upload/restore reais foram
executados pelo proprietário e aprovados. C1B-1 teve aceite físico de ida/volta e recuperação pausada,
conforme incremento abaixo; C1B-2/liberação de SSD e Android C1C seguem pendentes.

**Incremento C1B-1 (19/10/2026, aceite funcional; PR #48 MERGED):** `ops/myct-archive-torrent` acrescenta
inspeção integral, archive/restore com `.torrent` e manifesto, hashes e fluxo de
recuperação pausada/verificação antes de seeding. Catálogo separado preserva C1A.
Somente testes simulados; nenhum torrent/Drive real alterado. [Relatório, comandos
e limites](MYCT-C1B-1-RELATORIO.md). Aguardar auditoria da Luna antes de autorizar
fixture física; liberação de SSD/remoção de links não implementadas (C1B-2).

## 1. Necessidade real

O Fedora 44 headless serve mídias pelo Jellyfin e compartilha torrents pelo Transmission. O My-CT já organiza downloads para a biblioteca usando **hardlinks**. O usuário quer preservar arquivos difíceis de encontrar, liberar espaço no SSD e, ao restaurar os originais, voltar a fazer seeding sem redownload dos peers.

O **Google Drive será primeiramente um arquivo de backup/restauração sob demanda**. Não há exigência de streaming diretamente do Drive nem de seeding remoto. Evitar acrescentar infraestrutura sem ganho claro.

## 2. Decisões funcionais

- **Arquivar** = copiar ao Drive, registrar o conteúdo e comprovar integridade. A primeira ação **não apaga o original**.
- **Liberar espaço** = operação opcional e separada, permitida **após** cópia validada, prévia da árvore inteira, revisão de hardlinks e confirmação explícita.
- **Restaurar** = copiar do Drive para o Fedora, comparar conteúdo e devolver os arquivos à estrutura necessária; arquivos comuns podem ser restaurados independentemente.
- **Retomar seeding** = somente após recriar ou recuperar a entrada do torrent, apontar para os dados locais corretos, executar verificação completa no Transmission e confirmar que os dados correspondem ao torrent original.
- **Jellyfin permanece local** na entrega inicial; montagem remota via Rclone fica opcional.
- Sem seeding do Drive no MVP. Sem cópia automática para a nuvem, exclusão programada, tarefa residente nem sincronização bidirecional que pudesse propagar remoções acidentais.
- Preservar o download/seeding existente e toda a configuração operacional do Fedora até autorização de implementação.

## 3. Arquitetura mínima

```text
My-CT Android (depois) -- SSH existente --> CLI Fedora myct-archive (proposta)
                                                  |
                                             Rclone / Drive
                                                  |
                                         Google Drive: My-CT-Archive/
                                                  |
                                    catálogo local de arquivos arquivados
                                                  |
                    Transmission RPC + myct-media / myct-media-cleanup
                                                  |
                                   Torrents originais e Media/Jellyfin
```

Primeiro entregar a **CLI no Fedora**, sem alterar o Core, PostgreSQL ou APK. Reutilizar padrões dos helpers `ops/myct-torrent`, `ops/myct-media` e `ops/myct-media-cleanup`; não escrever cliente OAuth, protocolo de nuvem ou BitTorrent próprio. O nome `myct-archive` é uma proposta, não interface já implementada.

**Catálogo mínimo por entrada:** identificador, tipo (arquivo comum/torrent), caminhos originais e relativos, tamanho, estado da cópia, hashes de verificação, referência remota, data, infohash e metadados recuperáveis do torrent, relação com hardlinks gerenciados e estado de restauração. Pode ser um registro privado simples; sem impor nova tabela ou serviço.

Não guardar tokens, OAuth, credenciais ou links secretos em Git, issues, logs ou APK. Configuração Rclone restrita ao usuário Fedora. No momento da implementação, conferir o fluxo OAuth/client ID **atual** da integração Google Drive/Rclone (o client ID compartilhado do Rclone está sendo descontinuado em 2026).

## 4. Fases e aceite

### C1A — Fedora: arquivos comuns + Drive

1. Inspecionar cota **efetivamente livre**, tamanho dos arquivos candidatos, rede, serviços ativos, pasta de downloads e diretório de Media. Confirmar vencimento real do plano com o usuário.
2. Configurar Rclone de forma privada e testar um arquivo pequeno de teste no Drive. Não modificar serviços Jellyfin/Transmission nem abrir portas.
3. Criar helper com ações mínimas `status`, `list`, `archive`, `restore` (nomes finais revisáveis). Transferir por **cópia** (`rclone copy`, não `move` ou `sync` destrutivo).
4. Verificar tamanho e **hash compatível** (ex.: MD5) do arquivo local e remoto quando disponíveis; em caso de hashes remotos ausentes, usar verificação por leitura/download antes de autorizar eliminação. Não interpretar mera existência do arquivo como prova de integridade.
5. Falha de rede, quota ou autenticação deve deixar os originais locais intocados; reiniciar operação sem duplicar dados descontroladamente.

**Aceite C1A:** upload e download reais de um arquivo pequeno, conteúdo idêntico e listagem/erros legíveis pelo SSH. Nenhuma exclusão.

### C1B — Torrent completo, hardlinks e restauração de seeding

**Unidade mínima para arquivamento com retomada de seeding: o torrent inteiro**. Não presumir que só os vídeos vinculados ao Jellyfin representam todos os arquivos; podem existir legendas, amostras e extras.

**Arquivamento:**
1. Inventariar `infohash`, nome, caminho de dados, arquivos selecionados, estrutura relativa, `.torrent` original ou metadados efetivamente reutilizáveis para reimportação, estado no Transmission e hardlinks (inclusive links externos detectáveis). Para torrent originalmente adicionado por magnet, **confirmar antes** que os metadados necessários foram persistidos.
2. Parar/pausar o torrent pelo RPC para uma fotografia estável, com prévia do impacto no seeding. Se a cópia falhar, manter os dados e permitir retomada local.
3. Copiar conteúdo **completo e íntegro** e preservar catálogo/metadados de recuperação de modo não dependente de uma entrada volátil no daemon.
4. Somente depois de validação e **segunda confirmação**, tratar os links da biblioteca e liberar os dados locais, reutilizando as proteções de `myct-media-cleanup`. Não apagar seletivamente um arquivo de torrent ativo. Se houver links extras, dados incompletos, metadados insuficientes ou estado divergente, **bloquear liberação e explicar**.
5. Registrar estado **Arquivado / seeding interrompido**; nunca exibir que seeding continua enquanto só houver cópia remota.

**Restauração:**
1. Conferir quota/rede e espaço SSD suficiente antes de baixar; usar caminho de staging ou operação que não sobrescreva uma mídia existente sem confirmação.
2. Restaurar a árvore completa mantendo nomes/bytes/estrutura, validar hashes e recolocar no caminho que o Transmission espera.
3. Se a entrada foi removida, reimportar o `.torrent` preservado com download inicialmente pausado e localização correta. Não presumir que magnet isolado recupera seleções/caminhos personalizados.
4. Executar **`torrent_verify` pelo RPC**, identificar o torrent pelo **infohash** (não por ID inteiro, que pode mudar) e **somente retomar se a integridade chegar a 100%**. Se faltarem dados, sinalizar claramente e não disparar redownload de peers por acidente.
5. Recriar os hardlinks da biblioteca via mecanismo existente, de forma idempotente e sem substituir mídia preexistente. Confirmar acesso do Jellyfin caso o usuário deseje voltar a vê-la.

**Aceite C1B:** torrent pequeno e autorizado, cópia integral no Drive, remoção local somente após confirmação, recuperação integral, verificação 100% pelo Transmission e estado apto a seeding sem baixar novamente os dados. Verificar o tratamento dos hardlinks e o espaço realmente recuperado. Não usar mídias raras como primeiras cobaias.

### C1C — Android: interação confortável

- Aproveitar **Downloads → Mídia / Armazenamento** e canal SSH já existentes. Evitar uma nova aba inteira se uma seção pequena resolver.
- Mostrar **Local**, **Arquivando**, **Na nuvem**, **Restaurando**, **Disponível para seeding** ou **Erro**, com tamanhos/progresso e motivo da falha.
- Botões explícitos **Arquivar**, **Restaurar** e, separadamente, **Liberar espaço** com prévia dos torrents/links atingidos.
- Trabalho no Fedora independente do Android conectado; ao reabrir, consultar estado real da operação.
- Manter package ID, assinatura, fluxos SFTP, Downloads, Busca, Cofre, animações e demais recursos sem regressões.

**Aceite C1C:** arquivo de teste arquivado e restaurado pelo Moto G06, com mensagens coerentes; confirmação extra para liberar espaço. Testar primeiro sem excluir nada real.

### Opcional — Jellyfin lendo a nuvem

Investigar **Rclone mount + cache local** apenas se houver benefício. Bibliotecas atuais permanecem como estão; não transferir dependência de reprodução nem seeding para montagem remota durante C1. Se ativado depois, tratar indisponibilidade de rede para evitar varredura que interprete arquivos ausentes como exclusões.

## 5. Cuidados e limites

- **Hardlinks:** apagar um nome não libera espaço enquanto houver outro link para o inode. `myct-media-cleanup` já diferencia retirar links de apagar torrent completo; esta fase **não deve contornar** as verificações existentes.
- **Proteção dos dados:** primeiro `copy` + conferência; liberar disco em operação independente. Se não houver prova de cópia íntegra ou metadados para ressuscitar torrent, conservar originais.
- **Retomada da transferência:** torrent armazenado apenas na nuvem fica parado; depois da recuperação local e da verificação pode fazer seeding, sem garantia de peers conectados ou tráfego de upload.
- **Cota/assinatura:** 400 GB é o plano relatado, **não espaço útil garantido**. Manter listagem do que está arquivado e cálculo de quanto tempo/armazenamento exigiria baixar tudo antes do fim da assinatura. Arquivos muito importantes idealmente terão segunda cópia independente.
- **Escopo da automação:** nenhuma sincronização contínua, limpeza periódica, cron, daemon ou alteração no sistema sem autorização posterior.
- **Limites legais:** usar arquivos e torrents sobre os quais o proprietário tenha direitos ou autorização para preservar/compartilhar.

## 6. Critérios de conclusão e sequência

1. **C1A primeiro:** transferência manual íntegra e restore de arquivo comum pelo SSH.
2. **C1B depois:** recuperar seeding confiável de um torrent de teste e lidar corretamente com hardlinks.
3. **C1C por último:** controles práticos no Android, sem reescrever os motores.
4. Considerar Jellyfin remoto **somente após** as três entregas e nova decisão do proprietário.

**Situação ao criar este registro:** nenhuma branch de implementação, serviço Rclone, autorização OAuth, arquivo transferido, APK ou configuração Fedora alterada. Planejamento e issue não equivalem a implementação/aceite.

## Referências

- [Rclone — Google Drive](https://rclone.org/drive/)
- [Rclone — comparação por checksum](https://rclone.org/commands/rclone_check/)
- [Transmission — especificação RPC](https://github.com/transmission/transmission/blob/main/docs/rpc-spec.md)
- [MYCT-5C — motor BitTorrent](MYCT-5C-BITTORRENT-REPORT.md)
- [My-CT Media — hardlinks](MYCT-MEDIA-MVP.md)
- [My-CT Media — gerenciamento de armazenamento](MYCT-MEDIA-STORAGE-CLEANUP.md)

**Fechamento C1B-1 (19/10/2026):** Aprovados `inspect`, `archive`, `restore` e `recover` pausado no Fedora/Google Drive/Transmission, com verificação de peças e `seeding_ready:true`, `rpc_status:0`. Sem teste de `--resume`, peers/upload ou liberação de SSD. A biblioteca Jellyfin permaneceu intacta e o torrent terminou apontando para a cópia restaurada, pausado. [Detalhes e evidências](MYCT-C1B-1-RELATORIO.md). C1B-2 e C1C pendentes.
