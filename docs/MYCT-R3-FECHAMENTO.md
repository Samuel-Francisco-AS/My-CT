# MYCT-R3 — fechamento aprovado

**Data:** 17/10/2026. **Estado:** **PASS no Moto G06**, versão 0.6.4/code 21, [PR #25 integrado à main](https://github.com/Samuel-Francisco-AS/My-CT/pull/25) por **merge commit `daae0d06af5adc91f310b4b7a2726a55b7212b95`**. Integração sem squash, preservando histórico.

## Escopo entregue

- **Barra auxiliar 2 × 7** no mesmo layout do Termux da captura: `ESC / - HOME ↑ END PGUP` e `TAB CTRL ALT ← ↓ → PGDN`. Comportamento das teclas especiais, CTRL/ALT one-shot e `Ctrl+C` implementados conforme o emulador Termux.
- **Feedback tátil nativo discreto** e resposta visual imediata/curta nos botões auxiliares; sem alterar efeitos visuais globais do app nem adicionar dependências.
- **Colagem direta** por `TerminalEmulator.paste()`, sem diálogo My-CT “Enviar texto ao terminal?”. Preservados limite de clipboard, validação da sessão correta, bracketed paste, não persistência de conteúdo colado e confirmações de segurança não relacionadas.
- **Ações alternativas:** teclado ao tocar no terminal; ações contextuais/seleção com Colar, Teclado e Ctrl+C. Interface R2 Industrial Futurista mantida.
- Fedora, serviços remotos, JSch, SFTP, torrents e cofre não foram alterados nesta etapa.

## Evidências

- **Aceite físico do proprietário:** captura do Moto G06 e relato: “Sim, ficou bom, o toque e a disposição das teclas, e testei suas funcionalidades e funcionaram bem. Acredito que já possamos aplicar o passe nessa etapa.” Captura mostra `^C` e retorno ao prompt. Usuário autorizou expressamente fechamento padrão remoto.
- **Validação automatizada:** build Android offline PASS; **149 testes JVM aprovados, 1 opt-in ignorado**; lint 0 erros/2 avisos preexistentes; 21 arquivos upstream e 4 patches verificados.
- **Candidata publicada:** [release 0.6.4-R3](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r3-v0.6.4-rc1) com APK atualizável sobre a 0.6.3, `dev.myct`, mesmo certificado. SHA-256 `32752516b8c715370922b219b39a14ccdb2abb7422e31886c0db93a094fb1f3b`.

**Alcance exato do teste físico:** o usuário aprovou toque, posição e funcionalidades exercitadas, mas não relatou testes isolados de colagem multilinha/bracketed, TalkBack, rotação, desativação de vibração ou ALT em cada programa TUI. Esses detalhes podem ser verificados oportunamente, sem bloquear o passe concedido. A remoção do popup é comprovada pela implementação e testes automatizados.

## Git

- Branch: `myct-r3-extra-keys-paste`
- PR #25: **MERGED**, `myct-r3-extra-keys-paste → main`
- Merge SHA: `daae0d06af5adc91f310b4b7a2726a55b7212b95`
- Relatório detalhado: [MYCT-R3-TECLAS-COLAGEM.md](MYCT-R3-TECLAS-COLAGEM.md)
- [Backlog](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) permanece aberto para dívidas independentes.
- Branch local/remota pode ser removida **após sincronização local e verificação de ancestralidade**; não apagar remotamente antes do procedimento local do proprietário.

## Dívidas posteriores

- **R-01:** lag intermitente de digitação/Backspace SSH continua **suspenso** por decisão do proprietário. Nenhuma tentativa nova autorizada.
- **R-04:** transferência SFTP em segundo plano ainda carece de teste físico de continuidade/notificação/cancelamento.
- **R-05:** casos adversos de transferência SFTP/SAF: conflito, cancelamento, falha de rede, parciais.
- **R-06:** autenticação por chave de usuário e Android Keystore, ideia futura opcional.
- **R-08:** validações complementares de Downloads HTTP (ver em Arquivos, cancelar, polling, reconexão, arquivo maior).
- **R-09:** apresentação de verificação/AV reestruturada na R2, teste físico com relatório de nome/caminho longo pendente.
- **R-07:** **resolvida pela R3**. O recurso de teclas auxiliares foi implementado e aprovado; não tratar como dívida aberta.

**Encaminhamento:** R3 encerrada. Próximas intervenções somente conforme decisão do proprietário, proporcionais ao uso real, sem reabrir performance SSH automaticamente.
