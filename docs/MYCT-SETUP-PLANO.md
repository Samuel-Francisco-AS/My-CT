# MYCT-SETUP — Fedora instalável e recuperável pelo Android

**Atualização 21/10/2026:** documentação e implementação SETUP-1/2/3/4, com
correções pós-auditoria, consolidadas pela [PR #59](https://github.com/Samuel-Francisco-AS/My-CT/pull/59).
APK code48 aceito funcionalmente no Moto pelo proprietário. Provisionamento
completo em Fedora físico limpo permanece **NÃO VALIDADO**. Este texto preserva
o planejamento original de 20/10; seu estado inicial abaixo é histórico.
[Resultado, limitações e evidências da integração](MYCT-SETUP-INTEGRATION-RELATORIO.md).

**Decidido:** 20/10/2026, pelo proprietário.

**Estado:** planejamento aprovado; **nenhuma implementação, instalação ou recuperação executada**.

**Acompanhamento:** [issue #53](https://github.com/Samuel-Francisco-AS/My-CT/issues/53).

**Ordem obrigatória:** SETUP-1 (base) → SETUP-2 (Transmission/Prowlarr) → SETUP-3 (Rclone/Drive) → SETUP-4 (backup/recuperação).

## Objetivo

Permitir instalar o APK em um Android novo, conectar a um **Fedora recém-instalado** por SSH e, após diagnóstico e aprovação humana, preparar o ambiente necessário ao My-CT. O sistema deve voltar a funcionar sem copiar procedimentos pessoais do Fedora do desenvolvedor e sem depender de Luna, Codex ou outra IA.

**Não significa** instalar Fedora, habilitar SSH sem acesso prévio, autenticar-se sem credenciais, restaurar arquivos não respaldados ou contornar autorizações de serviços externos.

## Caminho previsto

```text
Android My-CT (Kotlin/Compose)
  └─ Pareamento SSH existente: senha + verificação de fingerprint do host
      └─ Diagnóstico Fedora (somente leitura)
          └─ Plano exibido no Android: operações, privilégios, efeitos, espaço
              └─ Aprovação humana por etapa
                  └─ Executor mínimo no Fedora (scripts/CLI versionados)
                      ├─ DNF / dependências
                      ├─ Core C++20 + helpers necessários
                      ├─ PostgreSQL dedicado / migrações
                      └─ unidades systemd
                          └─ Verificação final / próximos passos opcionais
```

O APK deve ter uma experiência de **Configurar servidor** no fluxo SSH existente. Não criar serviço de comandos genéricos privilegiados no Core nem deixar senha sudo/root salva. A execução e acompanhamento podem ocorrer por SSH, sem servidor HTTP administrativo público. Caso o SSH se desconecte, reler o estado do host antes de retomar; não presumir que um comando terminou.

## Regras transversais

- **Pré-requisitos fora do instalador:** Fedora iniciado, usuário autorizado, rede alcançável, serviço SSH ativo e fingerprint verificável por canal confiável. Na instalação nova, o usuário refaz o pareamento; não pressupor que dados de app desinstalado sobrevivam.
- **Alvo inicial:** Fedora com DNF e systemd (primeira prova na versão Fedora suportada pelo projeto). Fedora Atomic/Silverblue e outras distros ficam fora do primeiro escopo.
- **Inventário antes de instalar:** SO, arquitetura, CPU/RAM/disco livre, versões/pacotes, serviços, permissões, portas locais, caminhos e disponibilidade de `sudo`.
- **Plano explícito antes da ação:** mostrar comandos/ações resumidas, pacotes e impactos, distinguir operações de usuário das que exigem privilégio; confirmação específica para modificações sensíveis.
- **Manifesto versionado no repositório:** componentes, verificações de versão/estado, dependências, comandos permitidos de instalação e validação; instalação de artefatos My-CT com versão e integridade conhecidas. Não executar script remoto opaco por `curl | sh`.
- **Reexecução segura:** instalar apenas o que falta; não duplicar unidades, linhas de configuração, bancos ou usuários. Registrar status e erro por componente e permitir tentar novamente. Não automatizar rollback de dados de terceiros.
- **Respeitar o existente:** não reinstalar, apagar, reconfigurar ou tomar posse de instalações compatíveis de PostgreSQL, Transmission, Prowlarr, Rclone ou Jellyfin sem revisão. Não substituir credenciais nem expor interfaces além do loopback por padrão.
- **Sem complexidade gratuita:** scripts/CLI pequenos e contratos simples; nada de novo framework de agentes, sistema de pacotes próprio, daemon root permanente, contêiner ou dependência obrigatória da IA.
- **Ambiente de teste:** não formatar o Fedora pessoal como método de teste. Usar ambiente Fedora limpo controlado/disponível na implementação; manter o host atual preservado até consentimento para instalar.

## SETUP-1 — Base Fedora (primeira implementação)

**Escopo:** diagnosticar e configurar o mínimo para Core/funcionalidades-base operarem numa instalação limpa.

1. Levantar dependências reais do código atual (em `server/`, `ops/`, `ops/systemd/`, `server/migrations/`) antes de definir o manifesto; não copiar cegamente a lista de pacotes do Fedora pessoal.
2. Implementar detecção e prévia no Fedora; expor diagnóstico no Android pelo SSH já funcional. Em modo inspeção, não alterar sistema.
3. Distribuir/compilar componentes necessários à versão do My-CT escolhida, com procedimento reproduzível; usar DNF para dependências confirmadas. Evitar impor toolchain completo no servidor quando um artefato verificado for suficiente.
4. Preparar PostgreSQL apenas quando necessário: detectar cluster existente, criar banco/usuário dedicados quando apropriado, aplicar migrações versionadas sem destruir dados. Isolar acesso da rede externa.
5. Instalar Core C++20, helpers essenciais inventariados e unidades systemd adequadas; conferir startup e conexão real do painel Saúde. Componentes opcionais devem indicar **não configurado**, não quebrar a base.
6. Exibir resultado final por componente: instalado, já presente, requer ação, incompatível, falhou. Oferecer diagnóstico e tentativa assistida de recuperação.

**Aceite SETUP-1:** (a) Fedora limpo acessível via SSH → diagnóstico → prévia → aprovação → Core/DB/serviços funcionais; (b) segunda execução não duplica nem destrói; (c) interrupção/falha reportada com retomada; (d) abertura no Android real e saúde do Core confirmadas. Registrar limitações reais sem bloquear fase com testes excessivos.

## SETUP-2 — Transmission e Prowlarr

- Oferecer instalação **opcional** de Transmission headless e Prowlarr compatíveis com Fedora/arquitetura; verificar antes se já existem.
- Configurar serviços e acesso local com limites de escopo. Reaproveitar `ops/myct-torrent`, `ops/myct-search` e integração Android existente; nunca criar segundo downloader.
- Não sobrescrever torrents ativos, diretórios, sessão RPC nem chaves API preexistentes. Configurações e ações de usuário no Prowlarr (indexadores, permissões de fontes e testes de pesquisa) entram em **manual guiado**.
- **Aceite:** instalação limpa ou detecção de existente; controle básico de torrent e busca real pelo My-CT, sem regressão nem exposição pública.

## SETUP-3 — Rclone e Google Drive

- Instalar Rclone como opção; preparar estrutura e auxiliar na criação/seleção de remote. Preservar configuração existente.
- Guiar a pessoa na criação/configuração do projeto e credenciais OAuth do Google Cloud, autorizar a conta e verificar acesso ao Drive, inclusive fluxo sem navegador no Fedora.
- Tokens e segredos ficam sob controle da conta Fedora, fora de APK, Git, logs e relatórios. Não prometer autorização automática nem embutir credenciais universais.
- Integrar ao `myct-cloud`/arquivamento já existente, sem enviar arquivos automaticamente.
- **Aceite:** remote configurado pelo usuário, verificação de leitura e cópia/restauração de arquivo pequeno, com integridade confirmada.

## SETUP-4 — Backup e recuperação

- Definir inventário de estado restaurável: preferências e versão do My-CT, banco com migração/backup aplicável, manifestos de mídia/arquivos, configurações de serviços gerenciados e mapeamento de caminhos. Não confundir dados locais não copiados com itens recuperáveis.
- Produzir exportação com manifesto, versões, integridade e destino escolhido; suporte a restauração supervisionada em máquina Fedora diferente. Backups criptografados/segredos requerem decisão explícita sobre custódia e mecanismo, sem exportação implícita em texto.
- No novo Fedora, refazer SSH e confirmar host; diagnosticar, instalar o necessário, comparar configurações e **mostrar prévia das alterações** antes de restaurar. Não sobrescrever dados locais automaticamente.
- Arquivos multimídia grandes continuam opcionais e dependem de backup remoto realmente existente. Cloud OAuth exige reautorização ou restauração segura deliberada.
- **Aceite:** backup pequeno conhecido → ambiente limpo → revisão → recuperação de configuração e dados de teste → verificação de funcionamento; registrar itens irrecuperáveis com clareza.

## Dependências, não-objetivos e referências

- **Não começar** SETUP-2 sem SETUP-1 validada, nem SETUP-3 sem SETUP-2, nem SETUP-4 sem SETUP-3, salvo replanejamento explícito do proprietário.
- MYCT-S1/S2 já designam **Busca/Prowlarr/Bitmagnet** ([issue #43](https://github.com/Samuel-Francisco-AS/My-CT/issues/43)); esta trilha usa `MYCT-SETUP-*` para não colidir. Bitmagnet permanece fora do provisionamento inicial.
- Não reabrir por arrasto UX da [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9), automações da #4 ou reorganizar o Jellyfin.
- Referências de implementação: [README](../README.md), [AGENTS](../AGENTS.md), [Core e serviço](MYCT-4-BOOT-VAULT.md), [PostgreSQL](MYCT-3B-POSTGRES-REPORT.md), [Busca](MYCT-S1-BUSCA-PROWLARR-BITMAGNET.md), [Nuvem](MYCT-C1-ARQUIVAMENTO-NUVEM.md).
- Planejamento não autoriza nenhum comando administrativo, mudança no Fedora ou publicação de APK. Criar PR de implementação somente quando a etapa respectiva começar.
