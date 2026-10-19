# MYCT-S1/S2 — Busca de torrents: Prowlarr primeiro, Bitmagnet depois

**Acordado em:** 19/10/2026  
**Situação:** planejamento registrado, implementação ainda não iniciada  
**Issue:** [#43](https://github.com/Samuel-Francisco-AS/My-CT/issues/43)  
**Base inspecionada:** `main`, após consolidação da PR #42 (commit `99aaa24538`).

## Por que fazer

Hoje o usuário pesquisa magnets em sites externos e os transfere manualmente para **Downloads → Torrents** do My-CT. Quer pesquisar diretamente no Moto G06 por uma nova seção do aplicativo, com o Fedora 44 headless atuando como intermediário. A pesquisa deverá ser **sob demanda e dirigida por palavras**, e não um crawler DHT aleatório como requisito inicial.

Prowlarr agrega pesquisas em indexadores configurados: evita visitar cada interface, mas **ainda depende da disponibilidade desses indexadores**. Bitmagnet acrescentaria posteriormente um catálogo local descoberto por DHT, que pode ser consumido via Torznab no próprio Prowlarr.

## Desenho mínimo

```text
Moto G06 — My-CT (Kotlin/Compose)
  ├── Busca (NOVA): pesquisar e selecionar resultado
  │      |
  │      v
  │   SSH autenticado existente
  │      |
  │      v
  │   ~/.local/bin/myct-search (helper Fedora)
  │      |
  │      v
  │   HTTP 127.0.0.1:9696 / API Prowlarr
  │      ├── Indexadores configurados (S1)
  │      └── Bitmagnet Generic Torznab (S2, opcional)
  │
  └── Downloads → Torrents (EXISTENTE)
         |
         v
      ~/.local/bin/myct-torrent → Transmission RPC loopback :19091
         |
         v
      ~/Downloads/My-CT/Torrents → Media/Jellyfin (existente)
```

A separação **Busca = descobrir**, **Downloads = executar/gerenciar** é intencional. Não criar outro cliente torrent nem mudar o diretório/ciclo de nomeação. Não tocar automaticamente no Jellyfin.

O Prowlarr utiliza API HTTP de busca (GET ou POST `/api/v1/search`). É responsabilidade do helper esconder credenciais, converter as respostas para um contrato pequeno e reportar falhas sanitizadas. A lista de campos reais será confirmada com a versão instalada antes de congelar o contrato. A API key de Prowlarr é **somente Fedora**, em configuração privada do usuário; não deve aparecer em argumento de processo, APK, resposta JSON, logs ou Git.

## MYCT-S1A — Fedora: pesquisa útil primeiro

1. Inspecionar Fedora, serviços atuais, recursos disponíveis, instalação .NET/Prowlarr e ocupação da porta 9696. **Não instalar ainda sem executar essas verificações no host.**
2. Escolher método enxuto de execução do Prowlarr, preferencialmente um binário Linux x64 e serviço systemd para o usuário; sem desktop, VM ou stack de contêineres obrigatória.
3. Manter HTTP limitado a loopback. Validar API pelo próprio Fedora via SSH; não liberar porta no firewall, não criar redirecionamento público.
4. Configurar inicialmente 1 ou 2 indexadores compatíveis, sem presumir que 1337x ou outro site está funcionando. Verificar busca textual manual real.
5. Criar helper `ops/myct-search` com ações mínimas `status` e `search <consulta>`, saída JSON padronizada (lista de resultados com título, tamanho, indexador, seeders opcionais, magnet se existir, estado de erro). Limitar consulta e quantidade de resultados para respostas legíveis no aparelho.
6. Teste mínimo: uma busca real, indexador sem resultados, servidor desconectado, resultado malformado e resposta sem magnet. Medir CPU/RAM no Fedora e impacto durante reprodução pelo Jellyfin quando possível.

**Gate S1A:** o proprietário consegue pesquisar via CLI a partir do SSH do My-CT, com indexadores de verdade; não exige Android novo.

## MYCT-S1B — Android: aba Busca

1. Acrescentar `AppSection.Search` / rótulo **Busca** ao drawer e um ícone no estilo atual. Preservar o motor de animações R5, Cofre e navegação.
2. `SearchModel` utiliza conexão SSH existente, job cancelável e geração para descartar respostas antigas. Não adicionar acesso HTTP direto pelo Android.
3. `SearchScreen`: campo de pesquisa, ação Pesquisar, indicador de carregamento, resultados (título, tamanho, origem, seeders quando conhecidos) e erros/estado vazio. Busca sempre iniciada pelo usuário; sem polling de catálogos.
4. Cada resultado com **magnet válido** terá botão explícito **Adicionar aos downloads**. Usar o adaptador `myct-torrent` já existente; avisar sobre envio aceito, duplicata e estado a conferir na aba Downloads. Não disparar múltiplas vezes por retentativa automática.
5. Se a fonte fornecer somente arquivo `.torrent`, página ou link indireto, exibir resultado sem ação de envio automático nesta primeira versão. Investigar suporte adicional em microetapa, sem contornar o caminho supervisionado pelo My-CT.
6. Preservar package/assinatura, dados instalados, scroll de telas existentes, arquivos/hardlinks e estado de Downloads.

**Gate S1B:** no Moto G06, pesquisar em indexador real e adicionar **um magnet público e permitido** ao Transmission via comando existente. Verificar o item em Downloads/Torrents, inclusive duplicata. Build ou mock isolados não equivalem a esse aceite.

## MYCT-S2 — Bitmagnet, apenas após decisão baseada em S1

- Confirmar utilidade do Prowlarr, armazenamento livre, consumo do Fedora e demanda por catálogo DHT.
- Instalar Bitmagnet com **banco PostgreSQL próprio**, sem migração nem acesso ao schema Core My-CT.
- Configurar sua descoberta DHT como **opt-in** e limitar períodos/recursos; não supor que a pesquisa DHT atende a consultas guiadas em tempo real.
- Usar endpoint `/torznab` via indexador **Generic Torznab** do Prowlarr. A aba Busca permanece a mesma; idealmente apenas a fonte dos resultados muda.
- Medir crescimento do banco e efeitos em disco/rede. Caso não compense, adiar/descartar S2 sem bloquear S1.

## Aceite, limites e evidências

- Fazer primeiro um **fluxo vertical útil**; não instalar Sonarr, Radarr, Flaresolverr, automação RSS, rotinas periódicas ou painel extra sem necessidade comprovada.
- O Prowlarr reúne fontes externas, mas não elimina sua dependência; erros de indexador devem ser comunicados, sem prometer cobertura universal.
- Jamais expor API/token do Prowlarr, Transmission RPC ou PostgreSQL à internet ou persistir secrets no Android.
- Nenhuma mudança em serviço Fedora, firewall, SELinux, host, RPM, dados ou download real nesta fase **documental**. Implantação ocorre em etapa explícita no próprio host.
- Registrar comandos testados, resultados, consumo e pendências. Manter PR de implementação em draft até teste real no Fedora e Moto G06.
- As correções do magnet da PR #42 e operações de Mídia/Downloads continuam sendo a base, nunca reintroduzir código antigo da PR #34.

## Referências técnicas

- [API v1 Prowlarr](https://prowlarr.com/docs/api/)
- [Instalação Prowlarr](https://prowlarr.com/)
- [Configurar indexadores (Servarr Wiki)](https://github.com/Servarr/Wiki/blob/master/prowlarr/indexers.md)
- [Bitmagnet: integração Prowlarr via Torznab](https://bitmagnet.io/guides/servarr-integration.html)
- [Bitmagnet: requisitos e riscos](https://bitmagnet.io/faq.html)

**Próxima ação:** executar S1A no Fedora, testar pesquisa real via SSH e somente então conectar a nova aba Android ao contrato comprovado.
