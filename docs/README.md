# Documentação do My-CT

Este diretório separa a **documentação vigente** da **memória histórica de implementação**. Se você acabou de conhecer o projeto, não comece pelos relatórios MYCT de cada fase: muitos descrevem versões anteriores e têm pendências posteriormente resolvidas.

## Comece aqui

| Necessidade | Leia |
| --- | --- |
| O que o My-CT faz, versão e o que está realmente validado | [Estado atual](STATUS.md) |
| Obter o código, compilar o Android e preparar uma conexão | [Instalação e primeiros passos](INSTALLATION.md) |
| Componentes, dados, redes e limites entre Android/Fedora | [Arquitetura atual](ARCHITECTURE.md) |
| Alterar código, executar testes e enviar PR | [Desenvolvimento](DEVELOPMENT.md) e [contribuições](../CONTRIBUTING.md) |
| Diagnosticar uma instalação existente sem alterar serviços | [Operações no Fedora](OPERATIONS.md) |
| Relatar vulnerabilidades e lidar com informações sensíveis | [Política de segurança](../SECURITY.md) |
| Direitos de uso e licenças de componentes de terceiros | [Licenciamento](LICENSING.md), [LICENSE](../LICENSE) e [NOTICE](../NOTICE) |
| Entender por que uma funcionalidade mudou ou foi aceita | [Histórico e evidências](HISTORY.md) |

## Documentação por componente

- [Android / funcionalidades e build](../android/README.md)
- [Core C++20 / PostgreSQL](../server/README.md)
- [Desktop e helper administrativo](../ops/desktop/README.md)
- [Proveniência e recompilação da IDE / Sora](../android/IDE-LICENSES.md)
- [Termux integrado e patches](../android/terminal/upstream/README.md)
- [Instruções para agentes colaboradores](../AGENTS.md)

## Como interpretar as evidências

- **Implementado**: código presente na `main`; não implica deploy ou aceite físico.
- **Build/teste automatizado**: compilação ou teste executado; não equivale a validação end-to-end.
- **Aceito no aparelho**: cenário observado e descrito no relatório de uma versão/dispositivo; não é homologação geral.
- **Implantado**: executável ou configuração aplicada a uma máquina específica, com autorização; não decorre de merge Git.
- **Histórico**: foto de uma etapa. Frases como “pendente”, “próximo” ou “não iniciado” podem ter sido superadas.

A referência vigente é [STATUS.md](STATUS.md), com as ressalvas dos [relatórios históricos](HISTORY.md). Nenhuma documentação substitui a verificação do próprio código e ambiente antes de alterar dados ou serviços.

## Manutenção

Ao finalizar uma nova fase, atualize `STATUS.md` e o guia relevante; acrescente link às novas evidências em `HISTORY.md`. Não transforme o README principal numa cronologia de 80 fases. Use [CONTRIBUTING.md](../CONTRIBUTING.md) para critérios de PR.
