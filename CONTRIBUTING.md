# Contribuindo com o My-CT

Obrigado por querer colaborar! O My-CT é um projeto open source com cliente Android
(Kotlin/Jetpack Compose), Core C++20/PostgreSQL e ferramentas Python para um
servidor Fedora. Melhorias pequenas, úteis e bem explicadas são bem-vindas.

## Filosofia do projeto

- **Autonomia do usuário:** a aplicação deve permitir que o proprietário administre
  seus próprios arquivos e serviços; não introduza bloqueios ou confirmações
  extras apenas porque uma operação poderia ter riscos.
- **Simplicidade e desempenho:** prefira uma correção localizada a novas camadas,
  abstrações, serviços residentes ou dependências pesadas.
- **Escolhas proporcionais:** explique benefícios, custo de CPU/RAM/latência,
  compatibilidade e possíveis regressões. Segurança é importante, mas não
  substitui a avaliação de produto do mantenedor.
- **Compatibilidade:** não quebre SSH, SFTP, terminal, torrents, mídia, cofre,
  funcionalidades offline ou serviços existentes sem justificativa e aceite.
- **Decisão humana:** propostas e auditorias são avaliadas caso a caso; um
  achado não autoriza automaticamente alterações de comportamento.

Leia também [`AGENTS.md`](AGENTS.md), os
[princípios no README](README.md#princípios-do-projeto) e a
[documentação do projeto](docs/).

## Antes de contribuir

1. Para bugs, consulte as [issues](https://github.com/Samuel-Francisco-AS/My-CT/issues)
   e procure duplicatas. Relate o ambiente, a versão, os passos e os resultados
   esperado/observado, omitindo dados pessoais e segredos.
2. Para mudanças relevantes de arquitetura, permissões, operações de exclusão
   ou UX, discuta a proposta antes de implementar. **Pequenas correções e
   documentação podem chegar diretamente por pull request.**
3. **Vulnerabilidades ainda não divulgadas não devem ser relatadas em issues
   públicas:** siga [`SECURITY.md`](SECURITY.md).

## Fluxo de contribuição

1. Crie um fork (ou uma branch, se você tiver acesso de escrita) a partir da
   `main` atualizada.
2. Faça uma mudança focalizada, em commits com mensagens descritivas. Evite
   reformatar arquivos não relacionados.
3. Atualize testes e documentação pertinentes, sem ampliar desnecessariamente
   o escopo. Preserve APIs, formatos de dados e comportamento existente,
   exceto quando a alteração for aprovada.
4. Abra um pull request direcionado à `main` explicando **problema, solução,
   arquivos afetados, testes executados, riscos/limitações e impacto
   operacional**. Anexe capturas apenas quando agregarem valor e nunca com
   informações privadas.
5. Aguarde revisão antes de merge ou publicação. O mantenedor decide aceitar,
   pedir ajustes, adiar ou recusar a proposta.

Não há obrigação de implementar uma funcionalidade antes de discuti-la, nem
garantia de que toda contribuição será integrada.

## Como validar

Execute **somente os testes proporcionais ao que foi alterado** e informe quais
foram realmente executados. Exemplos a partir da raiz do repositório:

```bash
# Validação dos arquivos Termux incorporados
python3 android/terminal/upstream/verify.py

# Testes unitários do aplicativo Android (requer JDK/SDK/Gradle)
cd android
./gradlew :app:testDebugUnitTest

# Compilar APK quando a mudança afetar o Android
./gradlew :app:assembleDebug
```

Para o Core C++, consulte [`server/Makefile`](server/Makefile) e seus
pré-requisitos; em `server/`, `make test` compila os binários e executa testes
Python. Há testes adicionais em `ops/tests/` e `ops/setup/tests/`.

Uma compilação bem-sucedida **não equivale** a teste físico no Fedora ou em
um aparelho Android. Declare claramente o que ficou sem validação.

## Ambiente, dados e segurança

- Use dados sintéticos para testes de exclusão, torrents, armazenamento e
  recuperação. Não rode testes destrutivos em arquivos de terceiros.
- **Nunca** envie senhas, tokens, chaves SSH, credenciais, `.env` reais,
  backups privados, endereços pessoais ou logs sem sanitização.
- Não peça nem execute instalação com `sudo`, alterações de firewall/SELinux,
  migrações de banco, reinícios, atualizações de serviços, operações
  destrutivas ou implantação no computador de outra pessoa sem autorização
  explícita.
- Abrir um PR **não** autoriza mexer nas instalações existentes ou publicar
  releases. Não faça `force push` em branches compartilhadas.
- Não adicione código de terceiros sem verificar a licença e preservar os
  avisos de copyright. Consulte [`LICENSE`](LICENSE),
  [`NOTICE`](NOTICE) e [`docs/LICENSING.md`](docs/LICENSING.md).

## Riscos conhecidos

O repositório pode documentar limitações **aceitas conscientemente** pelo
mantenedor, sem uma correção programada. Por exemplo,
[issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65).
Esses registros não devem ser ignorados nem tratados como autorização para
introduzir bloqueios. Se houver uma alternativa com vantagens reais, apresente
os custos e benefícios antes de propor alterações.

Obrigado por ajudar o My-CT a crescer sem perder o que o torna útil.
