# Revisão de segurança e implantação inicial de CI — 31/10/2026

**Projeto:** My-CT · **Base examinada:** `ea4803823d511dfc8569bc16851eb180fee5b2ab` (`main` antes desta mudança).

## Escopo, procedimento e resultado

Esta revisão cobre **segredos no histórico conhecido**, proteções básicas do pipeline
de contribuição, rastreabilidade das dependências e a preservação das decisões
de segurança já registradas. **Não é um pentest, auditoria formal da implementação
inteira, exame de todas as releases APK nem homologação do SETUP em Fedora limpo.**

### Credenciais e histórico Git

A execução histórica Gitleaks informada pelo mantenedor analisou **438 commits
(~6,05 MB)** e produziu **2 achados da regra `generic-api-key`**. A triagem
classificou ambos como **falsos positivos de testes sintéticos**, não como
credenciais reais:

| Registro histórico | Arquivo / linha | Motivo da triagem |
| --- | --- | --- |
| `371c9255389724a1f0b9549c22a8f83122aa137d` | `ops/tests/test_search.py:20` | Chave artificial de teste do Prowlarr |
| `ad0261405bdc0f8eee8c8cebb3ad350de264ef0b` | `ops/tests/test_search.py:216` | Fixture com domínios `.invalid` e valor artificial para validar remoção de credenciais em metadados |

**Os commits e linhas foram conferidos no GitHub nesta revisão.** Não há
evidência de segredo real nesses dois achados; por isso não se justifica
rotacionar uma chave operacional baseada neles.

As exceções em [`../.gitleaksignore`](../.gitleaksignore) usam apenas os
**dois fingerprints específicos**, sem suprimir um arquivo, diretório ou
toda a regra. Outras ocorrências do mesmo padrão devem continuar
sendo detectadas e investigadas.

**Limite importante:** a varredura anterior não comprova, por si só, a
ausência de vazamentos em commits posteriores, tags, binários ou outros
repositórios. O GitHub Actions [`ci.yml`](../.github/workflows/ci.yml)
acrescenta a varredura automática; conferir o resultado da execução após
integrar o PR. Não publicar relatórios contendo segredos.

### Análise de risco e práticas relevantes

- **Acesso remoto:** preservar verificação da identidade SSH, autenticação
  do usuário, túneis e serviços internos protegidos; jamais publicar VNC,
  PostgreSQL, RPC de torrents ou Core sem revisão específica.
- **Credenciais de nuvem/cofre:** OAuth e desbloqueio do GNOME Keyring
  dependem de participação humana; não colocar senhas/tokens em logs ou
  variáveis de CI.
- **Mídia e exclusão:** [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65)
  registra risco de integridade conhecido e **aceito**, não solucionado.
  Não reabrir nem mudar comportamento automaticamente.
- **Licenças:** `LICENSE`, `NOTICE`, avisos de terceiros do APK e código
  Sora/Termux continuam com origem documentada. A comprovação anterior de
  integridade do arquivo-fonte Sora refere-se ao arquivo de release e checksum,
  não a equivalência binária exaustiva.
- **Distribuição Android:** APK de desenvolvimento assinado por chave debug
  não deve ser tratado como release de produção. Verificar assinatura,
  integridade e permissões antes de uma publicação comunitária.

### Segurança do GitHub Actions

O novo workflow:

- Usa `pull_request`, `push` para `main` e `workflow_dispatch`.
- Limita o `GITHUB_TOKEN` a `contents: read`; sem `pull_request_target`,
  escrita de repositório, deploy ou acesso aos segredos pessoais.
- Fixa actions por **SHA imutável** e desativa persistência da credencial do checkout.
- Executa Gitleaks com histórico Git disponível (`fetch-depth: 0`);
  comentários automáticos, sumários e upload de relatórios com achados
  foram desabilitados para reduzir risco de exposição.
- Executa testes Python, validação do upstream Termux, testes do Core C++
  e testes JVM/build do Android, em runners efêmeros. A instalação dos
  cabeçalhos de compilação acontece **apenas no runner**, jamais no Fedora do mantenedor.
- Tem limites de tempo e cancelamento de jobs antigos; não publica APK ou release.

### O que ainda exige confirmação externa

1. **Resultados reais dos jobs GitHub Actions**, incluindo falso positivo,
   falha ambiental ou dependência que falte. Nenhum job recebe status PASS
   até haver execução verificada.
2. Ativação de **Private vulnerability reporting** em Settings; a presença
   de `SECURITY.md` não ativa automaticamente a opção. A configuração de
   segurança administrativa não foi alterada.
3. Antes de distribuição comunitária: revisão específica das permissões
   finais do APK, assinatura/release e revalidação de licenças de terceiros.
4. Teste de instalação completa em Fedora físico limpo: **adiado
   expressamente para uma fase futura**, sem mudanças nesta rodada.

## Decisão desta rodada

**Aceito:** integrar controles preventivos de CI e registrar os dois falsos
positivos conhecidos com exceções mínimas. **Não declarado:** ausência
absoluta de vulnerabilidades/segredos, homologação de instalação ou
aprovação física de uma nova versão do Android. Nenhum comportamento de
produto, serviço Fedora ou política de risco aceita foi modificado.
