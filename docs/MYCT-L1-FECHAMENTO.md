# MYCT-L1 — fechamento do ciclo de ambiente local (17/10/2026)

**Status:** encerrado por decisão expressa do proprietário, com aceite **funcional do fluxo principal**, e testes regressivos/dívidas anotados sem PASS fictício. Próxima atividade: pequeno refinamento UI/UX, sem expandir execução/compilação dentro do APK.

## Integração no remoto

- **L1A:** [PR #28](https://github.com/Samuel-Francisco-AS/My-CT/pull/28), merge `4ae5fe73efb84c88628dbf62b10d769264c78a10`.
- **L1B:** [PR #29](https://github.com/Samuel-Francisco-AS/My-CT/pull/29), merge `b46c25ba295a2dd9118c1eeada020932b467a416`.
- **L1D:** [PR #31](https://github.com/Samuel-Francisco-AS/My-CT/pull/31), merge `e4d0a57ae130963a2ff087c5641f8bc709b98036`.
- **L1C:** [PR #30](https://github.com/Samuel-Francisco-AS/My-CT/pull/30) encerrado **sem merge**. WCPL/WAMR/worker, testes e outras alterações da branch cancelada **não foram integrados** à main. RC1/code 26 e RC2/code 27 são somente histórico; a versão vigente para instalar é **0.7.0-l1d-shared-storage/code 28**.

A L1D manteve package `dev.myct`, targetSdk 36 e certificado usado nas candidatas anteriores. Nenhum serviço Fedora foi alterado no fechamento.

## Retorno físico confirmado no Moto G06

| Etapa | Evidência observada | Estado |
| --- | --- | --- |
| L1A | Criar pasta e `teste.c` na workspace privada; editar Sora; salvar; fechar/reabrir; arquivo persistido; renomear pasta preservando arquivo | **PASS físico desses fluxos** |
| L1B | `/system/bin/sh` real com PTY; `pwd`, `ls`, `echo`, `cd`; acesso ao arquivo da IDE; `sleep 20` interrompido com Ctrl+C; digitação percebida mais fluida | **PASS físico desses fluxos** |
| L1D | Usuário selecionou/autorizou pasta Android `Documents/My-CT`, criou `Project-C/teste.c` pelo Sora, salvou documento original, viu o mesmo arquivo no Termux, compilou por `gcc` e obteve saída `My-CT e Termux funcionando juntos!` | **PASS físico do objetivo principal** |
| L1C | RC1/RC2 falharam com status 139 ao compilar no Moto; direção cancelada pelo proprietário | **CANCELADA, sem merge** |

### Compilação pelo Termux — comando correto

O arquivo-fonte pode estar em `~/storage/shared/Documents/My-CT/Project-C/teste.c`, porém executáveis colocados no armazenamento compartilhado são normalmente bloqueados por montagem sem permissão de execução. O usuário comprovou `Permission denied` ao usar `gcc teste.c -o teste && ./teste` na pasta compartilhada.

A solução **validada fisicamente** foi, a partir da pasta do projeto:

```sh
gcc teste.c -o "$HOME/teste" && "$HOME/teste"
```

O binário fica no HOME do Termux, enquanto o fonte continua compartilhado e editável pelo Sora. **Isso é uma limitação da plataforma, não defeito do My-CT.** Não tentar solucionar com `chmod +x`, root, instalação de compilador no APK ou execução de binários diretamente em Documents.

## Testes não confirmados individualmente e limites

- L1A/L1D: revogação SAF, conflitos por alteração externa, segunda edição seguida de recompilação, persistência da URI após nova inicialização, renomeações SAF e análise de cópias/IDs em todos os provedores **não receberam PASS físico específico**.
- L1B: resize/rotação, clipboard e alternância entre shell local e SSH autenticado **não receberam PASS físico específico** neste ciclo.
- Regresso IDE/terminal/SFTP remotos não foi revalidado completamente no Moto após L1D, apesar dos testes automatizados e aprovação prévia R4.
- L1D: build offline e lint PASS; **223 testes JVM aprovados, 1 handshake SSH opt-in ignorado**; instrumentação Android compilada, não executada. Histórico de um timeout intermitente L1B observado/retestado no relatório L1D.
- Persistem restrições do SAF (URI ≠ caminho POSIX dentro do shell local), workspace privada apagada ao desinstalar/limpar dados, buffers não salvos somente em memória e arquivos textuais até 1 MiB.
- Não há compilação embarcada, gerenciador de pacotes ou acesso `MANAGE_EXTERNAL_STORAGE` no APK. A compilação depende do Termux e do GCC/Clang instalado pelo usuário.

Esses itens são **dívidas e limites explicitamente aceitos para encerrar o MVP**, não testes aprovados. Não transformar automaticamente em bloqueios de nova fase nem iniciar trilhas de segurança/performance sem solicitação.

## Próximo ciclo autorizado — refinamento visual e usabilidade pequeno

Manter a implementação atual e restringir o próximo trabalho a pontos observados pelo usuário:

1. Tela inicial e drawer: deixar claro que o My-CT não é apenas um “Painel Remoto”, com acesso simples à IDE Local sem formulário SSH obrigatório.
2. IDE: facilitar descoberta de **Nova pasta** e **Novo arquivo** (atualmente escondidos no menu ⋮) e distinguir melhor origem privada/compartilhada/remota.
3. Terminal Local: abreviar o prompt padrão para uma indicação legível do contexto, sem caminho `/data/data/dev.myct/...` ocupando a linha, preservando `pwd` real.
4. Ajustar textos de ajuda para fluxo Sora → pasta compartilhada → Termux, incluindo dica de compilar para `$HOME`, sem compilação no My-CT.

**Sem** novo compilador, SDK downgrade, root, nova distro, reengenharia de shell/arquivos, mudanças no Fedora ou reabertura do lag SSH R-01. Refinamento deve ser limitado e entregue como incremento separado.

## Operação Git

Merge realizado usando método `merge`, preservando ancestry de branches empilhadas para limpeza segura com `git branch -d` depois de `git pull --ff-only` na main e verificação de ancestrais. A exclusão de branches remotas/locais será feita pelo proprietário por bloco Git explícito, não por limpeza implícita no repositório remoto.
