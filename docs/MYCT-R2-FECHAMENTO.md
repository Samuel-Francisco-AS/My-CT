# MYCT-R2 — fechamento do refinamento visual e funcional

**Data:** 17/10/2026. **Estado:** **PASS/APROVADO pelo proprietário no Moto G06** após MYCT-R2B-Fix **0.6.3/code 20**. **Histórico de Git:** completamente integrado à `main`, preservando o histórico por commits de merge.

## 1. Aceite e comprovação

O proprietário confirmou melhora visual e de navegação com MYCT-R2A.1 e R2B (particularmente na aba Arquivos); em 17/10 declarou que **as duas correções finais R2B-Fix deram certo** e autorizou passe e fechamento.

**Aprovado fisicamente por relato:** identidade Industrial Futurista preto/dourado e drawer sem cabeçalho global, reorganização das telas, rolagem de Arquivos melhorada, gesto do drawer deixando de interceptar rolagem normal, e integração de métricas acumuladas/ratio nos torrents. Esse aceite não significa que toda funcionalidade, cenário adverso ou recurso de acessibilidade foi ensaiado individualmente.

**Versão candidata aprovada:** [MYCT-R2B-Fix 0.6.3/code 20](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r2b-fix-v0.6.3-rc1), SHA-256 do APK `3a1ed031b7de8a540690744cf63d914a9536ab7d85056c51143542eb7ba1d76e`.

## 2. Entregas

- **R1/R1C:** SFTP preserva a posição por pasta ao retornar, evita salto inicial; filtro de dotfiles; melhorias parciais no resize/abertura do teclado. **Digitação ainda engasga**, dívida suspensa.
- **R2A/R2A.1:** identidade preta/dourada angular, geometria industrial, drawer lateral oculto, cinco seções, controles 'Manter conexão ativa' e 'Proteger capturas' no drawer, sem cabeçalho global.
- **R2B:** Arquivos com caminho, seta, ícones e linhas estruturadas; Saúde em cartões reais; Cofre e Opções organizados; downloads HTTP/Torrents em painéis; diálogos e relatórios com detalhes expansíveis.
- **R2B-Fix:** drawer fechado só abre após intenção horizontal iniciada nos primeiros 24 dp à esquerda (sem roubar scroll vertical/diagonal); fechamento Back/gesto/acessibilidade preservado.
- **Métricas Transmission:** interface Android exibe `downloaded_ever`, `uploaded_ever` e `upload_ratio` quando presentes. Adaptador `ops/myct-torrent` instalado de fato no Fedora em `~/.local/bin/myct-torrent` (modo `0700`), com backup `~/.local/bin/myct-torrent.bak-r2b-fix-20261017T044529Z`. Consulta real `list` apenas leitura retornou dois registros com os campos válidos, sem mudar o motor ou torrents; retorno físico subsequente do proprietário aprovou a Fix.

## 3. Evidências automatizadas finais

- APK atualizável sobre a versão 0.6.2, mantendo applicationId e certificado.
- Build Android offline: PASS.
- Testes JVM: 135 aprovados, 1 handshake opcional ignorado.
- Testes Python de adaptador torrent: 8 aprovados.
- Lint: zero erros e dois avisos preexistentes.
- Integridade: 21 arquivos upstream e quatro patches aprovados.

Não substituir testes físicos do Moto G06 por evidência de build; o passe é baseado também no relato de uso real.

## 4. Git — integrações confirmadas

| Fase | PR | Base/resultado | SHA do merge |
| --- | --- | --- | --- |
| MYCT-R1 | [#22](https://github.com/Samuel-Francisco-AS/My-CT/pull/22) | `myct-r1-terminal-files` → `main`, merged | `d239502cd214602782bf2e4b8fc7e8df2d7a7218` |
| MYCT-R2A | [#23](https://github.com/Samuel-Francisco-AS/My-CT/pull/23) | `myct-r2a-visual-shell` → `main`, merged | `116f779b541973aebc01f956c60deb4d74f34163` |
| MYCT-R2B | [#24](https://github.com/Samuel-Francisco-AS/My-CT/pull/24) | `myct-r2b-screens` → `main`, merged | `393eef946c15ccbddbfb48bf74a40ac7059d48f9` |

Nenhum squash nem force-push; as três cadeias de commits permaneceram no histórico. Branches podem ser apagadas **somente após a sincronização local e verificação de ancestralidade**. Esta documentação de fechamento é commit adicional na `main`.

## 5. Dívidas do refinamento — não bloquear o fechamento

| Identificador | Situação | Futuro |
| --- | --- | --- |
| R-01 — digitação/Backspace SSH | **Não resolvido, suspenso pelo proprietário** | Não reabrir diagnóstico ou otimização sem solicitação expressa |
| R-04 — SFTP em segundo plano | Implementado, **teste físico pendente** | Transferir arquivo de teste com app em background/tela bloqueada, validar continuidade/notificação/cancelamento |
| R-05 — falhas/cancelamento SFTP/SAF | Cenários adversos não totalmente validados no Moto | Conferir conflitos, parcial, rede e cancelamento sem perder SSH |
| R-06 — autenticação por chave do usuário | Ideia futura, não implementada | Avaliar Android Keystore e autenticação SSH com chave protegida, sem guardar senha |
| R-07 — teclas auxiliares | Ainda faltam Home/End/PgUp/PgDn | Adicionar e validar em tmux/nano, preservando Ctrl/Tab/setas |
| R-08 — Downloads HTTP | Aceite funcional parcial | Ver em Arquivos, cancelar/`.part`, histórico após reconexão, polling e download maior/lento |
| R-09 — relatório antivírus em tela pequena | Visual refeito na R2B, **prova física específica pendente** | Inspeção real com caminho e nome longos, SHA, ações acessíveis |
| Testes complementares | Não confirmados individualmente | Filtro dotfiles, permissões/notificações negadas, serviços de acessibilidade/TalkBack |

A **R-03/UX visual** e o **conflito de gesto lateral** estão encerrados pelo aceite físico. Item R-01 é dívida consciente, não requisito para retomar projeto.

Acompanhamento persistente: [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) (permanece aberta) e [trilha](09-TRILHA-REFINAMENTO-ANDROID.md).

## 6. Encaminhamento

Trilha MYCT-R2 finalizada. Nenhum novo desenvolvimento ou implantação é iniciado automaticamente. Próximos trabalhos são opcionais e sob escolha do proprietário, preferencialmente R-07 (ganho imediato de uso) e validações R-04/R-08. A base de código está consolidada na `main`.
