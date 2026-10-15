# My-CT — riscos, segurança e estratégia de validação
**Registro inicial:** 14/10/2026 · **Atualização esperada:** a cada checkpoint relevante

## Riscos que podem inviabilizar a primeira versão
| Risco | Consequência | Mitigação/experimento inicial |
| --- | --- | --- |
| Integração terminal Android complexa | APK compila, porém sem PTY/ANSI/teclado funcional | Spike real de htop/resize/host key no primeiro ciclo Android |
| Ambiente Android pesado no servidor | Falta de RAM/disco/CPU | Compilar por CLI, sem emulador, limitar jobs e cache |
| PostgreSQL com configuração inadequada | Consumo alto ou exposição indevida | Tuning conservador, bind local, role restrita e medição |
| Torrents enchem o SSD | Perda de espaço para sistema e projetos | Quotas, diretório dedicado, espaço livre mínimo, seeding controlado |
| Comando remoto indevido | Comprometimento do Fedora | SSH autenticado com host key, Core sem shell arbitrário por API |
| Exposição da API ou do RPC do torrent | Acesso de terceiros | Rede privada, bind restrito, firewall e teste de portas |
| Perda do celular ou credenciais | Acesso não autorizado | Chaves protegidas, bloqueio biométrico/PIN se viável, revogação documentada |
| Download interrompido / falha de energia | Dados incompletos/corrompidos | Arquivos temporários, estados claros, integridade, retomada conforme suporte |
| Único SSD | Perda de arquivos e banco | Backups fora do servidor e restauração testada |
| Prazo de Codex | Entrega espalhada e inoperante | Gates práticos, plano de corte e documento de retomada |

## Controles mínimos
- Sem credenciais, tokens ou chaves privadas no Git, banco em claro sem necessidade, screenshots ou logs.
- Verificar **host key SSH**; primeiro pareamento explícito e aviso diante de troca de identidade.
- SSH e SFTP operam como usuário sem privilégios elevados por padrão. Não iniciar Core como root.
- API Core não recebe comando shell arbitrário como entrada. Operações administrativas são allowlisted e requerem autorização.
- Consultas e mutations à API têm autenticação própria ou túnel autenticado comprovado; Tailscale sozinho não substitui análise de autorização.
- Escutar apenas no endereço necessário. Postgres e RPC de torrent locais, sem exposição em 0.0.0.0 por conveniência.
- Downloads HTTP(S) iniciados por URL externa exigem proteção contra SSRF: rejeitar destinos internos/locais e revalidar redirecionamentos/resolução antes de chamadas de rede; não usar o Core como proxy irrestrito.
- Operações de arquivo precisam normalização/canonização de caminho; evitar traversal, symlinks não confiáveis e escrita fora de diretórios autorizados nas tarefas de serviço.
- Exclusões, substituições e tarefas irreversíveis exigem confirmação clara. Nunca executar limpeza automática ampla sem plano de restauração.

## Regras para testes e evidências
- Distinguir: **compila**, **passa teste unitário**, **integra**, **funciona no Fedora**, **funciona no Moto G06**.
- Testar no dispositivo físico as rotas SSH/SFTP e autorização Android; emulador não é aceitação.
- Registrar comandos relevantes, hardware, versões, data, resultado e falhas sem vazar dados sensíveis.
- Avaliar consumo em repouso e durante upload/download/compilação; observar especialmente RAM de 8 GB e espaço restante do SSD.
- Validar estados adversos: reinício Fedora, falta de rede, arquivo duplicado, perda de permissão Android, disco cheio, senha/chave incorreta, download malformado, DB indisponível.
- O fato de existir pacote/daemon não prova compatibilidade com a versão do Fedora; medir e documentar.
- Não instalar, alterar firewall, migrar dados ou substituir apps do proprietário sem plano de reversão.

## Critérios de manutenção
O proprietário deve receber README, dependências, comandos para build/iniciar/parar/consultar logs, instruções de backup/restauração e status honesto dos itens pendentes. Um novo agente deve conseguir continuar o trabalho sem recorrer a uma conversa anterior.

## Métricas sugeridas
- RAM em repouso e durante tarefa, CPU, disco disponível, tempo de inicialização do Core.
- Tempo para conectar o terminal, latência de entrada interativa, reconexões.
- Integridade da transferência SFTP (hash quando adequado), vazão e política de conflitos.
- Taxa de conclusão/reinício dos downloads, erros e espaço alocado.
- Cobertura de jornadas aceitas com evidência e incidências reabertas.

Números alvo só serão definidos depois da primeira medição no hardware real.
