# MYCT-5D — Plano de verificação de arquivos

**Situação:** 5D-A **ACEITA fisicamente** no Moto G06 em 16/10/2026, [PR #19](https://github.com/Samuel-Francisco-AS/My-CT/pull/19) integrada à `main`. [Relatório e evidência de aceite](MYCT-5D-A-REPORT.md). 5D-B **não iniciada** e depende de autorização explícita. O plano original abaixo permanece como referência.
**Issue:** [#18 — MYCT-5D](https://github.com/Samuel-Francisco-AS/My-CT/issues/18).  
**Dependência:** fechamento e merge da [MYCT-5C / PR #17](https://github.com/Samuel-Francisco-AS/My-CT/pull/17), seguidos de sincronização local da `main`.

## Por que existe

O My-CT já baixa HTTP/HTTPS e, na fase MYCT-5C em validação, gerencia torrents no Fedora a partir do Moto G06. O proprietário quer selecionar um arquivo já baixado e descobrir **o que é, quanto ocupa, qual seu hash, se sua integridade corresponde à fonte e se um antivírus encontrou alguma ameaça**. A inspeção deve atender downloads HTTP/HTTPS, torrents e arquivos do explorador SFTP, sem ficar presa à tela de Torrents.

O projeto continua pessoal/de portfólio, em Fedora 44 headless (i7-3770, ~8 GiB RAM) e Android Kotlin/Compose. Priorizar utilidade direta; sem novos serviços ou API se um comando local via SSH resolver.

## Ordem aprovada

### MYCT-5D-A — Inspeção básica: primeira entrega funcional

**Experiência de uso:** localizar arquivo no Android (Arquivos ou Downloads), tocar em **Verificar arquivo**, receber relatório gerado no Fedora.

1. **Identificação e metadados:** nome, localização, tamanho exato, tamanho legível, extensão e formato identificado por conteúdo (avaliar `file`/libmagic disponível, não confiar apenas no sufixo do nome). Se houver divergência entre extensão e tipo identificado, informar sem assumir intenção maliciosa.
2. **SHA-256:** calcular no servidor, exibir/copy no Android. Permitir comparação com checksum conhecido informado pelo proprietário (por exemplo, de uma distribuição Linux oficial); resultado **igual/diferente/não informado**. Um digest sozinho não certifica origem ou segurança.
3. **BitTorrent:** considerar botão próprio para solicitar ao Transmission a reverificação dos dados do torrent por peças. Esta operação pode consumir disco e alterar temporariamente seu estado; não fazê-la automaticamente nem dispará-la ao apenas abrir a tela. **Correspondência BitTorrent não prova ausência de malware**, já que o próprio torrent pode conter conteúdo nocivo.
4. **Limites:** começar com arquivo individual e caminhos controlados sob gerenciadores existentes. Impedir leitura arbitrária de alvos fora do escopo aceito, symlinks indevidos e comandos construídos a partir de entrada livre. Consultas limitadas e resposta sem conteúdo do arquivo; hash de arquivos grandes pode requerer operação assíncrona simples e cancelamento.
5. **Interface:** relatório legível com avisos distintos de execução bem-sucedida, falha de acesso, arquivo ausente e verificação incompleta. O usuário não deve precisar usar comandos SSH manualmente.

**Aceite real mínimo:** escolher arquivo já existente no Fedora usando Moto G06 → exibir tamanho e tipo identificados → calcular SHA-256 e comparar com checksum conhecido. Manter transferências e terminal funcionando. Não exigir varredura profunda ou antivírus para declarar a 5D-A aceita.

### MYCT-5D-B — Antivírus local, sob demanda

1. Inventariar `ClamAV` e dependências; avaliar `clamscan` sem daemon residente inicialmente, medir uso real de CPU/RAM/IO e custo de atualização das assinaturas. Fazer instalação/configuração somente após consentimento explícito do proprietário.
2. Acrescentar **Verificação antivírus** no relatório, executada quando solicitada. Estados sugeridos: **detecção**, **nenhuma detecção nas assinaturas disponíveis**, **indisponível**, **erro/análise parcial**, **assinaturas desatualizadas**.
3. Não emitir atestado de segurança ou executar arquivos suspeitos; hashes e checksums não equivalem a antivírus. A ausência de alertas não exclui malware novo/desconhecido.
4. Não executar ações automáticas de exclusão, isolamento, movimentação, bloqueio de seeding, upload para terceiros ou retenção desnecessária de dados sensíveis. Tratamento de ameaça requer decisão posterior do proprietário.
5. Respeitar tempo, profundidade de arquivos compactados, limites de extração, volume, uso de disco e capacidade do hardware. Um arquivo compactado não totalmente inspecionado exige resultado **incompleto**, nunca selo de segurança.

**Aceite real:** exame no Fedora a partir do Moto com assinaturas verificáveis/atualizadas e resultado explicado, consumo aceitável para i7-3770/8GB, e nenhum impacto persistente no Transmission, SFTP, Core ou Cofre. Testes de detecção, se necessários, devem usar fixture padrão segura, não malware real.

## Arquitetura inicial sugerida (revisável)

```text
Android (Arquivos / Downloads)
    → SSH autenticado existente (comando limitado, independente do PTY)
    → ferramenta curta de inspeção no Fedora
    → stat / identificação de formato / SHA-256
    → [sob solicitação] Transmission recheck ou ClamAV
    → resultado estruturado para o Android
```

A ferramenta concreta e os formatos do contrato serão definidos depois de inspecionar o código em `main`. Preferir reutilizar o acesso SSH, SAF/SFTP e a navegação existente. **Sem API pública, banco ou daemon de inspeção obrigatórios**. Uma tarefa longa pode precisar de acompanhamento sem bloquear a UI; começar pelo menor formato possível.

## Decisões e dívidas adjacentes

- A MYCT-5C precisa terminar primeiro, incluindo decisão sobre pastas legíveis com título real e preservação do seeding. Não alterar arquivos em seed para testar inspeção; usar leitura somente ou RPC oficial apropriado.
- Suporte a `.torrent` via SAF permanece dívida separada; não incluído na 5D.
- Refinamentos de UX/lag/scroll SFTP e aceites físicos adiados continuam na [issue #9](https://github.com/Samuel-Francisco-AS/My-CT/issues/9).
- Fases posteriores: **MYCT-R** (refinamento) e **MYCT-6** (automações). A ideia de analisar downloads automaticamente após conclusão pode ser discutida **depois** da análise manual funcionar, não entra como requisito inicial.
- Nenhuma data de execução imposta, nenhum pacote instalado, serviço iniciado ou código alterado por este registro.

## Handoff para próxima conversa

Depois de MYCT-5C encerrada e `main` sincronizada no Fedora:

1. Consultar esta página, [issue #18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18), `AGENTS.md` e o guia `docs/10-RETOMADA-PROJETO.md` vigente.
2. Inventariar ferramentas existentes (`file`, `sha256sum`, estado do Transmission), definindo o escopo mínimo com o proprietário.
3. Criar branch nova a partir de `origin/main` **somente ao iniciar a 5D-A**.
4. Entregar CLI/integração Android pequena com prova real no Moto. Pedir autorização antes de qualquer instalação de pacotes para 5D-B.
5. Registrar resultados e pendências; PR sem merge antes do aceite.

**Regra:** não transformar a inspeção de arquivos em plataforma de segurança corporativa. Primeiro relatório útil e verdadeiro, depois antivírus sob demanda.
