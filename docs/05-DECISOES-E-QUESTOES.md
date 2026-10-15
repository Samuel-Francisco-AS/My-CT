# My-CT — registro de decisões e questões abertas
**Base:** conversas inaugurais até 14/10/2026. **Não confundir preferência técnica com componente testado.**

## Convenções
- **DECIDIDO:** direção explicitamente escolhida pelo proprietário; pode ser revista por nova conversa.
- **PROPOSTO:** recomendação técnica, pendente de prova ou validação.
- **ABERTO:** há alternativas legítimas e ainda não ocorreu decisão.
- **SUBSTITUÍDO:** decisão antiga trocada, mantendo a justificativa histórica.

## Decisões de direção
| ID | Assunto | Estado | Decisão / motivo |
| --- | --- | --- | --- |
| ADR-001 | Produto | DECIDIDO | Servidor pessoal + computador remoto no mesmo projeto |
| ADR-002 | Nome | DECIDIDO | My-CT; manter grafia sem presumir significado da sigla |
| ADR-003 | Servidor | DECIDIDO | C++20 como stack principal, não Node.js como motor do sistema |
| ADR-004 | Banco | DECIDIDO | PostgreSQL em vez de SQLite |
| ADR-005 | App | DECIDIDO | Android nativo com Kotlin/Jetpack Compose |
| ADR-006 | Terminal | DECIDIDO | Shell Fedora real via SSH/PTTY dentro do APK |
| ADR-007 | Arquivos | DECIDIDO | Transferência entre Android ↔ Fedora e explorador acessível no APK |
| ADR-008 | Downloads | DECIDIDO | Download e gerenciamento pelo próprio servidor, inclusive torrent/magnet, sem dependência de µTorrent |
| ADR-009 | Operação | DECIDIDO | Headless, econômico, independente do app e sem dependência de IA |
| ADR-010 | Plano | DECIDIDO | Planejamento inaugural flexível e sujeito a discussão/alteração |

## Propostas técnicas (não congelar sem prova)
| ID | Tema | Proposta | Como decidir |
| --- | --- | --- | --- |
| ADR-P01 | HTTP Core | cpp-httplib | Prototipar API mínima, validar manutenção/segurança |
| ADR-P02 | Banco C++ | libpqxx | Testar empacotamento e conexão no Fedora |
| ADR-P03 | HTTP downloads | libcurl | Demonstrar download grande, cancelamento e retomada |
| ADR-P04 | BitTorrent | Transmission-daemon em serviço isolado | Testar API, consumo, status e permissões |
| ADR-P05 | SSH Android | mwiede/JSch ou alternativa mantida | Testar PTY/host key/conexão/reconexão no Moto G06 |
| ADR-P06 | Terminal renderer | Biblioteca madura apropriada ao Android | Testar ANSI, resize, teclado e licenças |
| ADR-P07 | Rede externa | Tailscale | Confirmar viabilidade e segurança, sem exposição direta |
| ADR-P08 | Orquestração | systemd + funções C++ específicas | Comparar simplicidade e requisitos reais |

## Questões abertas para conversas futuras
1. Qual nível de autonomia para automações e quais ações merecem confirmação humana?
2. Política de autenticação do aplicativo: reutilizar identidade SSH ou emitir credencial separada para API? Como bloquear dispositivo perdido?
3. Como comunicar com a API de fora da LAN (VPN, túnel, TLS), sem comprometer o uso local?
4. Políticas de paths, quotas, arquivos muito grandes, velocidade, seeding, espaço livre e retenção de downloads?
5. É desejável um gerenciador de arquivos com operações destrutivas desde a V0.1, ou inicialmente só navegar/enviar/baixar?
6. Melhor forma de notificações Android sem bateria/servidor consumindo recursos em excesso?
7. Quando adicionar tarefas automatizadas de busca de vagas, repositórios e backups sem criar um executor genérico perigoso?
8. Backup externo: destino, periodicidade, retenção e restauração testada?
9. Nome para o aplicativo exibido ao usuário, ícone/identidade visual e convenções de UX?
10. Como medir consumo elétrico e decidir entre 24/7 ou janelas de operação?

## Como registrar novas decisões
Acrescentar **ADR-NNN** com data, contexto, opções, escolha, trade-offs e consequências. Uma decisão pode ser revisada explicitamente; não apagar histórico nem reclassificar um protótipo como produto pronto. Manter links para issues/commits quando existirem.
