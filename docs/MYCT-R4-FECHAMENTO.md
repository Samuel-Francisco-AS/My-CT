# MYCT-R4 — Fechamento aprovado da IDE remota e revisão SSH

**Data:** 17/10/2026 · **Estado:** **PASS físico no Moto G06** após 0.6.6-r4-ide-fix/code 23. **Git:** [PR #26](https://github.com/Samuel-Francisco-AS/My-CT/pull/26) **MERGED** à `main` por **merge commit `266e799a0fe3d5a4dd6d9a270095c3d2f3da6005`**. Histórico preservado, sem squash nem force-push.

## Resultado físico e aceite

O proprietário testou a IDE no Moto G06 em duas etapas. Na candidata 0.6.5, editou/salvou via Sora SFTP um arquivo C de teste e executou após compilação GCC no Fedora. Relatou digitação fluida na IDE, **sem implicar resolução da latência do terminal SSH**. Solicitou uma Fix para grade auxiliar, área útil de edição e navegador de projetos.

Na candidata **0.6.6/code 23**, apresentou capturas do Sora exibindo o programa com `puts("My-CT IDE funcionando! Pós-r4-fix");`, barra local 2×7 e topo compacto; a tela Terminal mostra `gcc teste.c -o teste`, `./teste` e saída **`My-CT IDE funcionando! Pós-r4-fix`**. O proprietário declarou expressamente **“fiz os testes e as correções passaram”**, autorizando passe, fechamento remoto e limpeza de branch posterior. Assim ficam **aprovadas funcionalmente no uso demonstrado** as três correções de ergonomia; o salto de navegação SFTP e a preservação de buffer, quando não individualmente registrados em vídeo/teste adverso, não devem ser generalizados para todas as condições.

## Entregas

- **IDE remota Sora Editor 0.23.6:** nova seção no drawer; navegar em arquivos Fedora por SFTP; C/C++, Rust, Python, Kotlin, Shell e JavaScript com gramáticas; linhas, busca/substituição, undo/redo, clipboard; salvar explicitamente com proteção contra mudança externa, temporário, backup e publicação atômica conforme contratos disponíveis; alternar ao Terminal SSH para executar GCC, Git e Cargo.
- **R4-Fix:** grade Termux **2×7 dirigida ao Sora local**, sem enviar teclas ao SSH; haptics e feedback breve; menu contextual compacto com Salvar/dirty/overflow e maior área útil de código; navegador IDE com visual R2 e âncora independente por diretório, alinhado à aba Arquivos sem alterar seu modelo.
- **Auditoria SSH:** examinou InputConnection/IME, filas, JSch, retorno, emulador e desenho, comparou upstreams Termux/ConnectBot/JSch. Não encontrou causa nova demonstrada; **nenhuma mudança especulativa** do caminho SSH foi incorporada, dívida de lag R-01 segue **suspensa** por decisão do proprietário.
- **Preservação:** SFTP/SAF, Core Fedora/PostgreSQL, torrents, cofre/FLAG_SECURE, FGS, UI R2 e teclas R3 permanecem em uso. Nenhum serviço Fedora foi instalado ou reiniciado nesta fase.

## Evidência técnica

- Android build offline: PASS.
- **172 testes JVM aprovados, 1 opt-in ignorado**; lint sem erros (2 avisos preexistentes).
- Integridade: 21 arquivos upstream Termux e quatro patches íntegros; teste SFTP descartável real no Fedora.
- APK aceito: [release MYCT-R4-Fix 0.6.6/code 23](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-r4-fix-v0.6.6-rc1), mesmo applicationId e certificado das versões anteriores, SHA-256 do APK: `63a2f6622fc0e92b166262f5d9c84eb55b7052f5dbd0b1ab4eebb71b85273c11`.
- Relatório técnico e histórico da Fix: [MYCT-R4-TERMINAL-IDE.md](MYCT-R4-TERMINAL-IDE.md).

**Limitações declaradas:** arquivo único até 1 MiB, buffer persistente somente enquanto processo Android existe; sem recuperação em disco após morte do processo, sem LSP/debugger. O salvamento tem restrições para ACLs/xattrs/hardlinks e edição concorrente; utilizar projetos pessoais conforme limites documentados. O aceite físico não equivale a testes específicos de TalkBack, arquivo gigante, escrita concorrente ou processamento de todas as gramáticas.

## Integração Git e encerramento

- Branch de implementação: `myct-r4-terminal-ide`.
- PR #26: **merged → main**, commit de merge `266e799a0fe3d5a4dd6d9a270095c3d2f3da6005`, sem squash.
- Antes do merge, resolveu-se a divergência em `docs/05-DECISOES-E-QUESTOES.md` para preservar **decisões R4 + ADR-015 a ADR-018 (MYCT-L1)**. A verificação GitHub pós-merge confirmou que o HEAD da branch R4 é ancestral de main.
- Após sincronização local do Fedora, a branch pode ser excluída local e remotamente **somente após verificar `git merge-base --is-ancestor`**, sem force-delete.
- Registro de acompanhamento: [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9) mantém R-01 e demais dívidas opcionais; não reabrir análise de lag SSH.

## Próxima etapa — MYCT-L1, antes de MYCT-6

**MYCT-L1 — Ambiente Local Independente**, [planejamento](MYCT-L1-AMBIENTE-LOCAL.md) e [issue #27](https://github.com/Samuel-Francisco-AS/My-CT/issues/27), foi escolhida pelo proprietário para começar **em uma nova conversa**, partindo da `main` já integrada. Sequência:

1. L1A: arquivos/IDE locais offline.
2. L1B: terminal local real/PTY com shell Android.
3. L1C: prova de programa C escrito, compilado e executado **sem rede, sem Fedora e sem Termux instalado** no Moto G06.

MYCT-6/T5 automações permanece planejada, **adiada até depois da L1**, não cancelada. Não criar branch de implementação L1 ou começar serviços antes da nova conversa.
