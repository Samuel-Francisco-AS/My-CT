# Trilha futura — refinamento do Android (não bloquear Core)

**Abertura:** 15/10/2026. **Atualização (16/10):** R-01/lag **suspenso como dívida** após teste físico negativo da 0.5.9; R-02/rolagem **melhorou e foi confirmado no Moto**; próxima trilha: **refinamento visual e ergonomia (UX-R03 + R-09)**.  
[Issue de acompanhamento #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) · [Fechamento do ciclo](08-FECHAMENTO-CICLO-ANDROID.md)

## Checkpoint de fechamento R2 — 17/10/2026

**MYCT-R2A/R2A.1/R2B/R2B-Fix aprovadas pelo proprietário no Moto G06** (último APK 0.6.3/code 20). Identidade preta/dourada industrial, navegação lateral e telas internas com melhora visual; scroll SFTP melhorado; correção do gesto do drawer e integração dos dados acumulados/ratio dos torrents confirmadas no retorno do proprietário. O adaptador `myct-torrent` foi efetivamente instalado no Fedora com backup e consulta somente leitura; a diferença entre taxas instantâneas e acumulados permanece preservada. [Detalhes do aceite](MYCT-R2B-REFINAMENTO-TELAS.md). A integração Git foi **executada com sucesso** em 17/10/2026 na ordem #22 → #23 → #24, com SHAs de merge `d239502`, `116f779` e `393eef9`. Relatório final: [MYCT-R2-FECHAMENTO.md](MYCT-R2-FECHAMENTO.md).

**Débitos remanescentes (não bloqueantes):** R-01 lag SSH/Backspace **suspenso**; R-04 transferência SFTP em segundo plano **não testada fisicamente**; R-05 adversidades SFTP/SAF; R-06 chave de usuário/Keystore opcional; R-07 teclas Home/End/PgUp/PgDn; R-08 verificações restantes de Downloads HTTP; R-09 interface AV **implementada visualmente**, mas teste físico do diálogo com caminho longo não demonstrado. Testes específicos de dotfiles/acessibilidade/notificação podem ser realizados oportunamente. Nenhuma dessas pendências implica rejeição do redesign R2 ou nova rodada automática de diagnósticos.

Abaixo permanece o histórico de decisões, requisitos e dívidas em seu estado original; este checkpoint prevalece para status atualizado.

## Princípio

**O proprietário autorizou continuar a trilha de refinamento, exceto o lag R-01.** Escolher uma frente visual/ergonômica por vez e entregar mudança útil no Moto G06. Não reiniciar diagnósticos/benchmarks de digitação sem autorização expressa. Evitar reescrever emulador, expandir infraestrutura ou redesenhar por redesenhar.

## Débitos abertos

| ID | Prioridade orientativa | Sintoma/evidência | Meta proporcional e teste futuro |
| --- | --- | --- | --- |
| UX-R01 | **Dívida suspensa** | **Lag ainda perceptível na 0.5.9/code 16 (MYCT-R1C)**, especialmente Backspace; três candidatas de otimização/instrumentação não proporcionaram melhora notada na digitação. Abre/fecha do teclado melhorou. Termux via SSH segue mais fluido. | **Não retomar por iniciativa do agente, não criar R1D nem bloquear outras fases.** Preservar implementação estável, histórico e preferência IME. Retomar apenas se o proprietário solicitar expressamente. |
| UX-R02 | **Melhoria confirmada** | Restauração de scroll por pasta implementada na MYCT-R1; o proprietário confirmou que voltou à posição sem o salto visual anterior. Filtro de dotfiles também implementado; seu aceite físico específico não foi relatado. | Preservar a melhoria; não reabrir a navegação por padrão. Validar o filtro apenas oportunamente durante o refinamento visual. |
| UX-R03 | Média | **UI truncada, rígida, desconfortável**. Layout visual/organização de ações necessitam ergonomia para Moto G06. Não é só tamanho da fonte. | Simplificar cabeçalho/ações, espaçamentos/contraste, hierarquia, feedbacks e rolagem; validar no aparelho. Respeitar grade ANSI do terminal: não aplicar reflow arbitrário em `top/vim/nano`. Navegador de arquivos deve usar UI estruturada própria. |
| VAL-R04 | Alta para validação futura | Serviço SFTP `dataSync` MYCT-2R implementado, mas **não ensaiado em background no Moto G06**. | Transferência não sensível média/grande com notificação e tela bloqueada, depois retorno/resultado; cancelar pela notificação; documentar Doze/limite Android 15+ (dataSync 6h/24h) e não prometer execução após force-stop. |
| VAL-R05 | Média | **Conflitos/cancelamento/falhas** SFTP de ambas as direções não exaustivamente testados fisicamente; PDF e imagens passaram no fluxo feliz. | Com arquivos de teste, confirmar overwrite recusado, cancelamento sem falso sucesso e sem derrubar SSH, parcial SAF explícito, rede perdida; evitar lotar SSD. |
| FUT-R06 | Futuro | Reconexão depende de senha digitada de novo quando processo morre; SSH atual autentica por senha, pin da host key Ed25519. | Avaliar autenticação por chave SSH de usuário protegida no Android Keystore para reconexões voluntárias; nenhuma senha em claro, mudança de sshd ou auto-replay. |
| FUT-R07 | Futuro | Acessibilidade e fluidez geral ainda exigem refinamento. Dashboard Saúde e aba Downloads já foram implementados, mas isso não resolve o conforto da UI/terminal. | Tratar com base em reclamações físicas concretas, não presumir redesenho completo. |

| VAL-R08 | Validação futura | **MYCT-5B integrada com aceite parcial em 16/10/2026:** histórico/download real via Android, foco/IME e fim das piscadas dos botões aprovados no Moto G06. Não foram ensaiados fisicamente Ver em Arquivos abrindo diretório, cancelamento, reconexão de histórico na aba, clique durante polling ou progresso lento/maior. | Testar um caso por vez no Moto G06 quando o proprietário pedir; verificar pasta SFTP correta, `.part` após cancelamento, estado após reconexão sem replay e resposta dos cliques durante consulta. Não refazer MYCT-5A. Ver [fechamento 5B](MYCT-5B-ANDROID-DOWNLOADS-REPORT.md) e [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). |

## Autorização MYCT-R2 — visual e fluidez (16/10/2026)

**Escopo explicitamente acordado e autorizado:** [identidade visual e planejamento MYCT-R2](MYCT-R2-IDENTIDADE-VISUAL-E-PLANEJAMENTO.md).

- **R2A:** tema preto profundo/sombrio + destaques dourado/âmbar, tokens reutilizáveis e **drawer lateral oculto por gesto** contendo *toda* a navegação e funções globais. **Nenhum cabeçalho global ou barra de abas permanece** em qualquer tela; não manter hambúrguer/status/título fixo no topo. Preservar elementos úteis locais (caminho em Arquivos, barra de teclas do Terminal).
- **R2B (após R2A):** remodelagem visual de todas as abas, inclusive itens ainda não citados, tornando Arquivos/SFTP, Saúde, Cofre, Downloads HTTP/Torrents, Opções e relatórios mais legíveis e estruturados. Ajuda contextual por botão `i` pequeno, sem ocultar avisos críticos. Corrigir movimento do item ao tocar na listagem SFTP. Expor métricas acumuladas reais do Transmission apenas se disponíveis no RPC/adaptador.
- **Princípio obrigatório: FLUIDEZ.** Movimento suave, transições curtas, feedback imediato, ausência de saltos/recentralização/piscadas e preservação de estado. Não criar animações excessivas nem reabrir lag/SSH.
- Branch de documentação e futura implementação: `myct-r2a-visual-shell`, baseada em `myct-r1-terminal-files` enquanto PR #22 permanece draft/sem merge. Não misturar R2B, não fazer merge automático.

## Decisão após o teste físico da 0.5.9 — 16/10/2026

- **Lag R-01 suspenso:** a tentativa R1C (workers dedicados, recepção limitada, resize coalescido e window-change sem ACK) melhorou somente a abertura/fechamento do teclado, **não a digitação percebida** no Moto. Não declarar a R1 concluída quanto ao lag; não iniciar mais diagnósticos, correções ou versão R1D por iniciativa própria. Evidências e código permanecem no [relatório MYCT-R1](MYCT-R1-TERMINAL-FILES.md) e no [PR #22](https://github.com/Samuel-Francisco-AS/My-CT/pull/22).
- **R-02 confirmado:** navegação SFTP conserva a posição e não volta visualmente ao topo antes de restaurar. Dotfiles toggle implementado, sem relato específico de teste físico.
- **Nova ordem de refinamento, autorizada:** começar com **UX-R03 (layout, navegação, conforto/legibilidade das abas)**, incorporando **R-09 (diálogo de verificação/antivírus muito longo)** quando fizer sentido na mesma intervenção visual. Depois, de forma separada, avaliar R-07 (teclas auxiliares), VAL-R04 (SFTP em background), VAL-R05 e VAL-R08 (testes funcionais pendentes), conforme preferência do proprietário.
- Não bloquear a integração das melhorias aceitas pela pendência do lag. Ainda é necessário decidir fechamento do PR #22; **não fazer merge automático** nem declarar todos os recursos aceitos.

## Notas para quem retomar

- Na branch MYCT-R1, `FilesScreen.kt` já restaura `LazyListState` por diretório com âncora e filtro visual de dotfiles. O proprietário confirmou a melhoria de scroll sem salto. Evitar regressão no trabalho visual.
- `TerminalCallbacks.kt` consulta `TerminalModel.enforceCharBasedInput`; padrão true, preferência `char-based-input`; experiência do proprietário foi melhor com false. Preservar controle, inclusive restartInput seguro.
- A saída de `ls` no terminal forma coluna vertical quando nomes longos excedem a largura/grades disponíveis. Isso é semântica da TTY, não razão para converter toda saída em cartões responsivos. Visualizações estruturadas pertencem à aba Arquivos, não ao stream PTY.
- Serviço de SSH `SshForegroundService.kt` (`specialUse`) e de arquivos `SftpTransferService.kt` (`dataSync`) são separados; validar que ajustes de ciclo de vida não derrubem SSH ao falhar SFTP.
- A causa exata da primeira falha do PDF na versão MYCT-2 **não foi reproduzida**; em MYCT-2R o proprietário conseguiu baixar/abrir PDF e enviar outro. Não declarar bug causalmente identificado nem reabrir uma investigação sem novo sintoma.
- Sem logs de teclas, dados de arquivos, senhas, fingerprints, URIs ou conteúdos privados. Preferir métricas numéricas efêmeras.
- Não expandir para containers/emuladores/dispositivos simulados para compensar ausência de Moto; testes JVM não substituem gate de UX.

## Critério de conclusão da trilha (quando autorizada)

Não há uma data ou meta de refatoração geral. Uma melhoria é encerrada quando o proprietário percebe ganho no Moto G06, a funcionalidade anterior continua operando, e a dívida correspondente recebe evidência e estado atualizado nesta página e na issue #9. Se uma solução custar mais que o benefício, manter dívida consciente e prosseguir.

**Nenhum item aqui bloqueia o merge documental e a abertura da próxima fase Core, com os limites transparentes.**
