# Trilha futura — refinamento do Android (não bloquear Core)

**Abertura:** 15/10/2026. **Status:** backlog deliberadamente **adiado** por decisão do proprietário, após aceitação do fluxo básico Android/SSH/SFTP.  
[Issue de acompanhamento #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) · [Fechamento do ciclo](08-FECHAMENTO-CICLO-ANDROID.md)

## Princípio

**Não executar esta trilha automaticamente.** Quando o proprietário pedir refinamento, escolher uma queixa física e entregar correção mínima comprovada. Evitar meses de arquitetura, benchmark interminável, reescrever o emulador, criar frameworks de jobs ou redesenhar a aplicação sem necessidade. O próximo trabalho previsto após o fechamento é Core C++20/PostgreSQL, e esta trilha fica disponível para depois.

## Débitos abertos

| ID | Prioridade orientativa | Sintoma/evidência | Meta proporcional e teste futuro |
| --- | --- | --- | --- |
| UX-R01 | Alta | **Lag** perceptível ao digitar/apagar no SSH do Moto G06, mesmo após MYCT-1R. `Entrada por caracteres=false` reduziu a sensação de atraso, mas não eliminou. Diagnóstico MYCT-1P concluiu causa não identificada. | Em execução futura autorizada, comparar um cenário real e instrumentar **somente o ponto suspeito**; otimização mínima, sem atribuir causa a rede/JSch/renderer sem prova. Manter switch true/false reversível. |
| UX-R02 | Alta | **Scroll SFTP perdido**: ao abrir subpasta e retornar ao pai, lista salta para o topo; usuário precisa rolar tudo de novo. | Salvar/restaurar posição por diretório (índice + offset; preferir item identificável quando lista mudar). Verificar com pasta longa, navegação aninhada, refresh, rotação e nova conexão. Sem aumentar consumo de RAM sem necessidade. |
| UX-R03 | Média | **UI truncada, rígida, desconfortável**. Layout visual/organização de ações necessitam ergonomia para Moto G06. Não é só tamanho da fonte. | Simplificar cabeçalho/ações, espaçamentos/contraste, hierarquia, feedbacks e rolagem; validar no aparelho. Respeitar grade ANSI do terminal: não aplicar reflow arbitrário em `top/vim/nano`. Navegador de arquivos deve usar UI estruturada própria. |
| VAL-R04 | Alta para validação futura | Serviço SFTP `dataSync` MYCT-2R implementado, mas **não ensaiado em background no Moto G06**. | Transferência não sensível média/grande com notificação e tela bloqueada, depois retorno/resultado; cancelar pela notificação; documentar Doze/limite Android 15+ (dataSync 6h/24h) e não prometer execução após force-stop. |
| VAL-R05 | Média | **Conflitos/cancelamento/falhas** SFTP de ambas as direções não exaustivamente testados fisicamente; PDF e imagens passaram no fluxo feliz. | Com arquivos de teste, confirmar overwrite recusado, cancelamento sem falso sucesso e sem derrubar SSH, parcial SAF explícito, rede perdida; evitar lotar SSD. |
| FUT-R06 | Futuro | Reconexão depende de senha digitada de novo quando processo morre; SSH atual autentica por senha, pin da host key Ed25519. | Avaliar autenticação por chave SSH de usuário protegida no Android Keystore para reconexões voluntárias; nenhuma senha em claro, mudança de sshd ou auto-replay. |
| FUT-R07 | Futuro | Acessibilidade e fluidez geral ainda exigem refinamento. Dashboard Saúde e aba Downloads já foram implementados, mas isso não resolve o conforto da UI/terminal. | Tratar com base em reclamações físicas concretas, não presumir redesenho completo. |

| VAL-R08 | Validação futura | **MYCT-5B integrada com aceite parcial em 16/10/2026:** histórico/download real via Android, foco/IME e fim das piscadas dos botões aprovados no Moto G06. Não foram ensaiados fisicamente Ver em Arquivos abrindo diretório, cancelamento, reconexão de histórico na aba, clique durante polling ou progresso lento/maior. | Testar um caso por vez no Moto G06 quando o proprietário pedir; verificar pasta SFTP correta, `.part` após cancelamento, estado após reconexão sem replay e resposta dos cliques durante consulta. Não refazer MYCT-5A. Ver [fechamento 5B](MYCT-5B-ANDROID-DOWNLOADS-REPORT.md) e [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9). |

## Notas para quem retomar

- No código atual, `FilesScreen.kt` usa listagem Compose/LazyColumn. A mudança de pasta troca `listing`; investigar restauração de scroll e ciclo da `LazyListState` em vez de recriar listas ou forçar scroll ao topo sem motivo.
- `TerminalCallbacks.kt` consulta `TerminalModel.enforceCharBasedInput`; padrão true, preferência `char-based-input`; experiência do proprietário foi melhor com false. Preservar controle, inclusive restartInput seguro.
- A saída de `ls` no terminal forma coluna vertical quando nomes longos excedem a largura/grades disponíveis. Isso é semântica da TTY, não razão para converter toda saída em cartões responsivos. Visualizações estruturadas pertencem à aba Arquivos, não ao stream PTY.
- Serviço de SSH `SshForegroundService.kt` (`specialUse`) e de arquivos `SftpTransferService.kt` (`dataSync`) são separados; validar que ajustes de ciclo de vida não derrubem SSH ao falhar SFTP.
- A causa exata da primeira falha do PDF na versão MYCT-2 **não foi reproduzida**; em MYCT-2R o proprietário conseguiu baixar/abrir PDF e enviar outro. Não declarar bug causalmente identificado nem reabrir uma investigação sem novo sintoma.
- Sem logs de teclas, dados de arquivos, senhas, fingerprints, URIs ou conteúdos privados. Preferir métricas numéricas efêmeras.
- Não expandir para containers/emuladores/dispositivos simulados para compensar ausência de Moto; testes JVM não substituem gate de UX.

## Critério de conclusão da trilha (quando autorizada)

Não há uma data ou meta de refatoração geral. Uma melhoria é encerrada quando o proprietário percebe ganho no Moto G06, a funcionalidade anterior continua operando, e a dívida correspondente recebe evidência e estado atualizado nesta página e na issue #9. Se uma solução custar mais que o benefício, manter dívida consciente e prosseguir.

**Nenhum item aqui bloqueia o merge documental e a abertura da próxima fase Core, com os limites transparentes.**
