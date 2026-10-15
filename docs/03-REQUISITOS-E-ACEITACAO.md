# My-CT — requisitos e critérios verificáveis
**Versão:** 0.1 · **Estado:** backlog inicial, sujeito a negociação por entregas

## Vocabulário de prioridade
- **P0 / essencial:** capacidade central para o primeiro produto útil.
- **P1 / importante:** buscar no ciclo inicial se P0 estiver estável.
- **P2 / evolução:** não bloquear a versão inicial.
- **Aceito:** só quando testado no hardware real e com evidência no diário, não com mocks isolados.

| ID | Prioridade | Requisito | Evidência mínima |
| --- | --- | --- | --- |
| INF-01 | P0 | Fedora headless com serviços autoiniciados | Reinício, reconexão SSH e verificação systemd |
| INF-02 | P0 | Core C++20 compilável/reproduzível | Build limpo e execução de binário no Fedora |
| INF-03 | P0 | PostgreSQL utilizado pelo Core | Escrita/leitura real, serviço local e acesso restrito |
| AND-01 | P0 | APK Kotlin/Compose instalável no Moto G06 | Instalação e abertura em aparelho físico |
| SSH-01 | P0 | Terminal Fedora interativo via SSH | Executar pwd, git status e htop ou equivalente PTY |
| SSH-02 | P0 | Teclas especiais e resize | Ctrl, Tab, setas, interrupção e redimensionamento reais |
| SSH-03 | P0 | Verificação de identidade do host | Chave SSH conhecida, mudança rejeitada até confirmação explícita |
| FILE-01 | P0 | Listagem remota SFTP | Navegar diretórios permitidos do usuário |
| FILE-02 | P0 | Upload Android → Fedora | Enviar arquivo real escolhido via SAF e conferir integridade |
| FILE-03 | P0 | Download Fedora → Android | Receber arquivo real em destino escolhido e conferir integridade |
| FILE-04 | P1 | Progresso/cancelamento/conflitos | Transferência grande cancelável; nomes repetidos tratados |
| DASH-01 | P0 | Saúde real do servidor | CPU, RAM, disco e estado dos serviços em atualização sob demanda |
| DL-01 | P1 | Download HTTP/HTTPS no Fedora | URL válida, status e arquivo íntegro após app desconectar |
| DL-02 | P1 | Download BitTorrent/magnet | Torrent legal de teste, status, pausa/retomada e conclusão |
| DL-03 | P1 | Ações no APK | Adicionar, pausar, retomar, cancelar e localizar destino |
| AUTO-01 | P1 | Rotina autônoma persistente | Job pré-definido executa sem app aberto e gera histórico |
| OPS-01 | P0 | Operação sem dependência de Codex | Guia de build, start/stop, logs, rollback e recuperação |
| SEC-01 | P0 | Superfícies administrativas privadas | Sem binding público de API, SSH com autenticação segura |
| SEC-02 | P0 | Privilégios mínimos e segredos protegidos | Sem root obrigatório para Core; sem senhas no repositório/log |
| UX-01 | P1 | Interface responsiva e estados explícitos | Fluxos de conexão, offline, permissão negada e falha recuperável |

## Jornadas de aceitação que prevalecem sobre testes de unidade

### A. Terminal remoto real
No Moto G06, conectar ao Fedora com host key verificada; abrir shell interativo com PTY; executar comando, abrir htop, usar Ctrl-C/setas, alternar de tela e reconectar. Não contar saída textual falsa como êxito.

### B. Arquivos em ambos os sentidos
Selecionar foto/documento do Android; transferir via SFTP; verificar arquivo no Fedora; baixar outro arquivo para local autorizado pelo Android; abrir/inspecionar o resultado. Testar nome existente e perda de conectividade.

### C. Download independente do app
Adicionar URL HTTPS ou magnet de conteúdo legitimamente distribuído; iniciar; fechar o app; retornar e consultar o estado; conferir o arquivo final no explorador. Distinguir download de cópia SFTP.

### D. Core C++ + banco reais
Inicializar PostgreSQL, executar migração mínima e Core como usuário não privilegiado, obter métrica real e registrar operação no banco. Reiniciar serviço e preservar dados.

### E. Reboot e recuperação
Reiniciar Fedora; serviço necessário volta, SSH continua acessível; verificar logs, consumo de memória e integridade das transferências/tarefas segundo política definida.

## Matriz mínima de testes
- **Unitários:** parsing de entradas, estados de tarefa/download, validação de caminhos e respostas HTTP.
- **Integração:** libpqxx/Postgres, API↔daemon, SSH/SFTP com Fedora.
- **Android instrumental/físico:** permissões SAF, host key, PTY, teclado, vida útil da sessão, compartilhamento de links.
- **Operação:** serviços systemd, baixa memória, disco quase cheio, desconexão e restart.
- **Segurança:** origem não autorizada, tokens/credenciais ausentes, caminho malicioso, endpoints expostos por engano, exclusão com confirmação.

## Sem falsa conclusão
Cada requisito terá status: **não iniciado → em andamento → validado no hardware → aceito** ou **adiado/descartado com justificativa**. Registro de teste e commit acompanhando o status. Nenhum requisito deste documento está inicialmente marcado como implementado.
