# My-CT — visão, contexto e escopo
**Versão:** 0.1-documental · **Registro:** 14/10/2026 · **Estado:** proposta evolutiva

## Enunciado
My-CT é uma plataforma pessoal para transformar um computador Fedora sem monitor em **servidor doméstico útil** e **computador remoto**, controlado por um aplicativo Android nativo. O servidor deve operar sozinho, sem exigir que o celular ou uma IA permaneça conectado.

O nome My-CT é mantido como definido pelo proprietário; nenhuma expansão da sigla foi acordada.

## Contexto real e restrições
- Servidor alvo: Intel Core i7-3770, aproximadamente 8 GB de RAM, SSD de 240 GB, Fedora Workstation 44 operando preferencialmente em modo headless. Confirmar configuração atual antes de tomar decisões irreversíveis.
- Cliente principal: Motorola Moto G06, Android; interação primordial por tela pequena.
- Acesso atual: SSH pelo celular já foi usado; o estado exato do ambiente deve ser reinspecionado no começo da implementação.
- Recursos escassos: aproximadamente três dias restantes de acesso mais amplo ao Codex na data inaugural; não tratar essa janela como prazo fixo para todo o produto.
- Premissa: custos recorrentes próximos de zero, operação simples, documentação legível e retomada do desenvolvimento sem Codex.

## Resultados que importam
1. No telefone, abrir uma **sessão Linux real** no Fedora, com programas interativos.
2. Enviar e receber arquivos com uma interface simples, acompanhando progresso e falhas.
3. Adicionar download HTTPS, magnet ou .torrent pelo telefone e deixar o servidor trabalhar sem o app aberto.
4. Consultar saúde do servidor (CPU, RAM, disco, serviços), com dados reais.
5. Configurar pelo menos uma automação útil que execute independentemente do telefone e mantenha histórico.
6. Reiniciar o Fedora e recuperar os serviços sem depender de desktop gráfico.

## Personas e casos de uso
**Administrador principal:** um único proprietário, normalmente usando o Moto G06; desenvolve projetos em C/C++ e outras linguagens; precisa de acesso remoto, automação e simplicidade.

- **CU-01 — Terminal:** conectar, executar comandos reais via SSH/PTTY, sair e reconectar.
- **CU-02 — Arquivos:** selecionar arquivo Android, enviar ao Fedora, encontrar no explorador, baixar de volta e abrir no aparelho.
- **CU-03 — Downloads:** compartilhar link no Android, iniciar tarefa, pausar/retomar, consultar destino e acompanhar conclusão.
- **CU-04 — Observabilidade:** visualizar métricas e reconhecer se um serviço falhou.
- **CU-05 — Tarefa independente:** agendar uma verificação/relatório e consultar seu resultado mais tarde.

## Limites da V0.1
Inclui: servidor C++20, PostgreSQL, aplicativo Kotlin/Compose, sessões SSH, transferência SFTP, gerenciador básico de downloads HTTP(S)/BitTorrent, painel de saúde e uma rotina autônoma útil, **sujeitos a recorte** mediante checkpoints.

Não inclui por padrão: navegador autônomo de candidaturas, substituição do sistema operacional, agente de IA obrigatório, LLM local residente, nuvem multiusuário, hospedagem pública, sistema completo de sincronização bidirecional contínua, emulador Android no servidor ou substituição do OpenSSH/BitTorrent por implementações próprias.

## Princípios de produto
- **Provas práticas:** uma feature não é "pronta" apenas por compilar ou passar em testes simulados.
- **Autonomia sem opacidade:** tarefas automáticas devem ser visíveis, canceláveis quando seguro e auditáveis.
- **Separação de responsabilidades:** Android apresenta e aciona; serviços Fedora fazem o trabalho; banco registra estado persistente.
- **Segurança proporcional:** rede privada, privilégios mínimos e confirmação de operações destrutivas.
- **Mudança controlada, não engessada:** prioridades e escolhas revisáveis sem apagar o histórico das decisões.

## Critério de sucesso inaugural
No aparelho físico, o proprietário consegue conectar ao Fedora, usar um comando interativo, fazer upload/download de um arquivo e observar uma operação do servidor que continua sem o app aberto. Esse é o **núcleo demonstrável**. Outros itens avançam conforme capacidade e tempo.
