# Política de segurança do My-CT

Agradecemos relatos responsáveis de problemas que possam comprometer a
confidencialidade, a integridade ou a disponibilidade do My-CT ou de seus
usuários. O projeto é comunitário e evolui por revisão do mantenedor.

## Versões avaliadas

| Versão | Política |
| --- | --- |
| `main` e versão pública mais recente | Relatos avaliados caso a caso |
| Versões anteriores | Sem compromisso de manutenção ou backport |

A presença nesta tabela **não** constitui garantia de suporte, correção ou
prazo de resposta. Reproduções devem informar commit, versão do APK ou tag
afetada, quando possível.

## Como relatar uma vulnerabilidade

**Não publique detalhes exploráveis, credenciais, provas de conceito
sensíveis ou dados de terceiros em issues, pull requests ou discussões.**

1. Acesse a aba **Security / Security and quality** do
   [repositório](https://github.com/Samuel-Francisco-AS/My-CT).
2. Se aparecer **Report a vulnerability**, utilize essa opção para enviar
   o relato por meio do *private vulnerability reporting* do GitHub.
   Este recurso depende de ativação nas configurações do repositório;
   **não pressupomos que esteja habilitado**.
3. Se a opção não estiver disponível e não houver um contato privado
   divulgado pelo mantenedor, abra uma
   [issue pública](https://github.com/Samuel-Francisco-AS/My-CT/issues)
   **somente para solicitar um canal privado**, sem mencionar falhas,
   passos de exploração ou informações que possam revelar o problema.

Para permitir a reprodução, procure incluir de forma privada:

- Resumo e impacto possível, distinguindo hipótese de falha reproduzida.
- Versão, commit, configuração e ambiente afetados, sem dados pessoais.
- Passos mínimos usando **fixtures sintéticas** e resultados esperados/reais.
- Logs sanitizados e, se houver, proposta de mitigação com seus custos.
- Indicação de qualquer acesso, privilégio ou interação do usuário exigidos.

## Escopo e limites de testes

Relatos sobre o cliente Android, terminal/SSH, SFTP, Core C++,
ferramentas Python, instalador e integrações próprias do My-CT são pertinentes.
Vulnerabilidades em Jellyfin, Transmission, PostgreSQL, Prowlarr, Rclone,
ClamAV ou outras aplicações externas devem ser comunicadas também aos
respectivos projetos quando o problema estiver no código deles.

Teste apenas sistemas, redes, contas e arquivos que sejam seus ou para os
quais tenha autorização explícita. Evite exploração em serviços públicos,
exfiltração de dados, ataques de negação de serviço, alterações
destrutivas ou publicação antecipada de detalhes que exponham outras pessoas.

## Avaliação, decisões e divulgação

- Os relatos serão avaliados conforme evidência, impacto, modelo de ameaça,
  experiência de uso, desempenho, compatibilidade e custo de manutenção.
- **Nem todo risco identificado resultará em alteração.** O mantenedor pode
  aceitar, documentar, priorizar ou recusar uma mitigação; isso não significa
  que o risco tenha desaparecido.
- Não são prometidos SLA, recompensas, correção automática ou data de
  divulgação. Caso exista uma vulnerabilidade explorável, procuraremos
  coordenar a divulgação com o relator e evitar expor usuários antes
  de uma decisão.
- Um exemplo de limitação documentada e conscientemente aceita está na
  [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65).
  Esse registro é público e não deve ser confundido com uma correção.

Para problemas comuns, pedidos de funcionalidade ou falhas já
publicamente conhecidas, utilize as [issues](https://github.com/Samuel-Francisco-AS/My-CT/issues)
sem publicar dados sensíveis.

Este arquivo não altera a licença [Apache-2.0](LICENSE), as
[licenças de terceiros](docs/LICENSING.md) nem concede autorização
para testar sistemas de outras pessoas.
