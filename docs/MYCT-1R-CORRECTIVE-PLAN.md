# MYCT-1R — correção de terminal Android após prova física

**Criado:** 14/10/2026 · **Base observada:** PR #6, commit `8a17bf9` · **Estado:** PLANEJADO, sem código corretivo executado neste registro.
**Natureza:** continuação corretiva da MYCT-1; revisável mediante discussão e evidências, sem marco de calendário rígido.

## Decisão de escopo

O proprietário instalou a MYCT-1 no **Moto G06** e autenticou uma sessão SSH real com o Fedora. `ls`, `cd` e `pwd` funcionaram. `tty` retornou `/dev/pts/3`; `stty size`, **com teclado aberto**, retornou **17 linhas × 51 colunas**.

**Resultado até aqui:** caminho básico de autenticação e shell SSH real comprovado no aparelho. Isso **não** comprova ainda resize com teclado fechado, programas TUI, Ctrl-C, robustez prolongada ou background. Não converter em MYCT-1 aceita antes do gate corrigido.

Relatos observacionais: terminal parece não se reajustar à abertura/fechamento do teclado; apresentação de caracteres prejudicada em tela estreita; pequena latência percebida comparada ao Termux; seleção com ação `Copy` não copia; screenshots resultam pretos; Android encerra o app em background rapidamente, exigindo senha e reconexão.

## Causas verificadas no código (distintas de hipóteses)

- `MainActivity.kt` ativa `FLAG_SECURE`, bloqueando screenshot deliberadamente.
- `TerminalSession.java` implementa `onCopyTextToClipboard` e `onPasteTextFromClipboard` como no-op. A seleção do Termux invoca esses métodos, então o clipboard acionado pelo usuário está efetivamente sem integração.
- `TerminalModel.kt` mantém conexão no `ViewModel` e a encerra quando este é destruído; **não há** foreground service Android e **não há** serviço/tarefa persistente no Fedora associado à sessão.
- `MainActivity.kt` usa fonte fixa em torno de 14sp, terminal embutido numa coluna com barras de ações; `TerminalSession` inicia em 80×24 e envia resize posteriormente. **A causa exata** de layout/latência permanece não demonstrada; medir no aparelho antes de otimizar.

## Metas e ordem sugerida — flexível

### R1 — Integridade da experiência de terminal (prioridade imediata)

1. Investigar propagação de insets da IME, recomposição Compose/AndroidView, dimensões medidas do TerminalView, cálculo de colunas/linhas e `setPtySize` de JSch. Verificar atualização **ao abrir e fechar** teclado e ao girar tela, com e sem barras visíveis.
2. Ajustar espaço disponível e comportamento de rolagem para que entrada, prompt e cursor permaneçam visíveis acima do teclado. Evitar duas barras de botões roubando o terminal quando IME está aberta; avaliar barra compacta/deslocável e tamanho de fonte configurável.
3. Garantir que o tamanho de fonte seja aplicado corretamente em pixels (sem conversão dupla), coerente com o renderer Termux. Validar caracteres largos, Unicode, linhas longas e aplicativos TUI reais.
4. Diagnosticar lag **antes** de otimizar: observar despacho de IO → thread principal, frequência de redraw, flush por tecla e custos de IME. Implementar coalescência/batching com limite de latência se demonstrado; não comprometer interatividade nem perder bytes.
5. Registrar valores de `stty size` com teclado aberto/fechado por terminal já conectado (por exemplo, testar `watch -n 1 'stty size'` se disponível, ou comando curto executado antes/depois), e confirmar `SIGWINCH`/redesenho em `top`/`htop`/`vi` se disponível.

**Gate R1:** input e prompt visíveis ao alternar teclado; colunas/linhas refletem efetivamente a área útil; TUI legível/operável; sem regressão de PTY, autenticação, teclas ou reconexão.

### R2 — Clipboard e captura com controle explícito (prioridade imediata)

1. Implementar copiar e colar **apenas por gesto/botão explícito do proprietário** via ClipboardManager/ClipData Android, com feedback de sucesso/erro, seleção correta, tratamento de grandes trechos e opção paste segura.
2. Continuar **bloqueando OSC 52 e quaisquer sequências remotas** que copiem ou leiam automaticamente o clipboard. Conteúdo do shell pode conter segredos: nunca mandar automaticamente ao log/analytics e advertir sobre o clipboard do SO.
3. Substituir a proibição rígida `FLAG_SECURE` por preferência local **Proteção de captura**, ativada por padrão; desativação explícita com indicação de risco. Aplicar/remover flag corretamente sem reiniciar SSH e validar que screenshots passam a funcionar apenas após opt-out.

**Gate R2:** Copy funciona em outro aplicativo Android; Paste funciona quando acionado pelo usuário; OSC remoto permanece inerte; screenshot protegida por padrão e habilitada quando permitido deliberadamente.

### R3 — Continuidade de conexão Android (prioridade alta e exigência de produto)

1. Investigar tipo de foreground service compatível com a operação de terminal remoto **e todos os pré-requisitos/permissões reais para targetSdk 36**; não declarar tipo indevido para escapar das restrições.
2. Implementar modo **Manter conexão ativa** iniciado por ação visível do usuário, com foreground service corretamente declarado, notificação persistente e ação `Desconectar`; estado da sessão SSH não deve depender da sobrevivência de `Activity`/`ViewModel` quando o serviço estiver ativo.
3. A UI reanexa ao estado/sessão do serviço ao retornar; handle de cancelar, desconectar e falha de rede sem duplicar a conexão nem pedir senha enquanto uma sessão autenticada permanece viva.
4. Permitir desativar o modo, liberar socket, finalizar serviço e respeitar o usuário. Não usar `WakeLock` ilimitado, polling agressivo ou serviço oculto.
5. Tratar Android 15/16: restrições de partida em segundo plano, permissões de notificação, políticas de bateria, tipos e limites de FGS. `dataSync` tem limite agregado de 6h/24h em Android 15+ para apps target 35+; não presumir execução indefinida. `connectedDevice` pode ser aplicável a conexão de rede externa, mas exige pré-requisitos específicos; validar escolha com documentação Android oficial, em vez de inventar categoria.
6. **Nunca prometer sobrevivência da conexão TCP/SSH a encerramento forçado do processo, force-stop, reboot ou perda de rede.** Notificação/FGS reduz risco de encerramento comum; não é garantia.

**Gate R3:** com opção ativa, navegar para outro app, apagar a tela, voltar e encontrar a mesma conexão por período observável (por exemplo 5 e 15 minutos, sujeito às condições de teste), sem religar manualmente; desativação interrompe serviço/conexão; tratamento de kill e rede caída é honesto e recuperável. Registrar bateria, versão Android e comportamento real.

### R4 — Persistência do *trabalho* no Fedora (prioridade alta, camada separada)

1. Consultar se `tmux` ou alternativa já existe no Fedora, **sem instalar/modificar sistema automaticamente**.
2. Se disponível e autorizado, oferecer sessão nomeada dedicada ao My-CT, com comando de attach/reconnect e verificação de usuário/permissões. Não sobrescrever uma sessão existente e não misturar shells de usuários.
3. Provar: iniciar `tmux`, executar uma tarefa **inofensiva e longa**, encerrar SSH/app, reconectar e reanexar; observar que a tarefa continuou **no Fedora**.
4. Considerar futura integração transparente com painel de sessões. Se `tmux` não estiver instalado ou integração automática for arriscada, documentar um fluxo manual funcional e necessidade de aprovação.
5. Reconexão sem redigitar senha é questão distinta: pode exigir autenticação por chave do usuário protegida no Android (Android Keystore) e UX de host key já fixada. **Não armazenar senha em SharedPreferences**, não automatizar password em log/script nem enfraquecer `sshd`.

**Gate R4:** tarefa permanece no Fedora após término do canal SSH; sessão pode ser recuperada com autenticação nova quando necessário. Distinguir claramente continuidade do processo remoto e continuidade da conexão Android.

## Verificações físicas mínimas

- [ ] Host key confiável, login SSH e shell reais continuam funcionando.
- [ ] `tty` é `/dev/pts/*`; PTY `stty size` varia de maneira adequada ao abrir/fechar IME e girar o Moto G06, sem valores inventados.
- [ ] `ls`, `cd`, `pwd`, `top`/`htop`/`vi` (se instalado), Ctrl-C, Tab, Esc, setas, Unicode e texto longo.
- [ ] Copy do texto selecionado visível no clipboard Android; Paste explícito no terminal.
- [ ] Screenshot bloqueada por padrão e permitida só após alterar preferência.
- [ ] Em background com modo ativo, retorno à mesma sessão enquanto processo vivo; notificação e ação de parada funcionais.
- [ ] Morte forçada do processo testada **separadamente**: não prometer reconexão automática com senha não armazenada; `tmux`/processo Fedora recuperável.
- [ ] Teste de perda de Wi-Fi e retorno, sem comandos remotos duplicados.
- [ ] Build CLI offline, testes JVM relevantes, lint, assinatura, instalação e resultados reais do aparelho; APK debug mantido fora do Git.

## Planejamento incremental e pontos de corte

- Priorizar R1 + R2 como hotfix de UX, depois R3 e R4 como continuidade. O proprietário pode reordenar segundo resultado físico.
- Se R3/R4 ultrapassarem a janela de desenvolvimento, publicar **resultado parcial explicitamente identificado**, preservando a prova SSH e documentação de pendências; não fechar MYCT-1 apenas por build.
- Evitar PostgreSQL, Core C++, SFTP, torrents e remodelagem visual geral nesta correção.
- Não atualizar serviços, firewall, `sshd`, autorizações de bateria do Android ou pacotes globais silenciosamente.
- Usar o **PR #6 e branch `myct-1-android-ssh`** como trilha corretiva; manter draft e sem merge até aceite real. Atualizar relatório MYCT-1 e diário a cada mudança.
- Não utilizar logs de terminal, fingerprints completos, usuários/endereços de rede pessoais ou segredos como evidência publicada. Fingerprint verificada permanece local.

## Fontes de regras de background
- [Tipos e pré-requisitos de Foreground Services — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Timeouts Android 15+ — Android Developers](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Restrições de início de FGS — Android Developers](https://developer.android.com/develop/background-work/services/fgs/changes)

Essas referências orientam revisão antes do código; a compatibilidade no Moto G06 ainda deve ser provada.
