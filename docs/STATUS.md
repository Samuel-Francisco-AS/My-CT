# Estado atual do My-CT

**Referência:** `main`, documentação revisada em 31/10/2026. Este arquivo descreve o estado conhecido do código e os aceites registrados, **não** garante que serviços estejam instalados em outro computador. Para confirmar versões, consulte o `android/app/build.gradle.kts` e os relatórios de aceite.

## Versão e distribuição

- Android: `applicationId=dev.myct`, `versionName=0.14.4-desktop-ux2`, `versionCode=57`, `minSdk=26`, conforme o Gradle na revisão documental.
- A versão UX2 recebeu aceite funcional informado pelo proprietário no Moto G06 e foi integrada à `main` na [PR #64](https://github.com/Samuel-Francisco-AS/My-CT/pull/64). O aceite cobre rotação, trackpad, teclado e estabilidade observada; não equivale a homologação em outros dispositivos.
- O APK 0.14.4 da UX2 é artefato de compilação **local** e não foi publicado como release oficial dessa versão nos registros examinados. Releases anteriores continuam disponíveis em [Releases](https://github.com/Samuel-Francisco-AS/My-CT/releases); não confunda uma release antiga com o estado atual da `main`.
- A licença Apache-2.0 para código original e os guias comunitários estão integrados. As bibliotecas externas preservam suas próprias licenças em [licenciamento](LICENSING.md).

## Capacidades existentes na linha principal

| Área | Situação demonstrada e limites |
| --- | --- |
| SSH / Terminal | Sessão SSH real com verificação da identidade do host, emulador Termux incorporado; ajustes de IME e teclas extras. Latência intermitente relatada anteriormente, sem solução geral comprovada. |
| Arquivos | SFTP remoto, upload/download e integração SAF; cenários de fundo/erro não são todos equivalentes a aceite físico completo. |
| Core Fedora | Serviço de saúde C++20 e PostgreSQL dedicado; instalação e início efetivos são independentes de atualizar o Git. |
| Downloads e torrents | Downloads HTTP no Fedora e integração Transmission; motores externos continuam trabalhando sem o celular conectado. |
| Antivírus / Cofre | Integração ClamAV e desbloqueio humano do GNOME Keyring; respeitar limites de recursos e não automatizar credenciais. |
| IDE e modo local | Editor Sora com edição local/remota; terminal PTY local e arquivos SAF. Compilação offline dentro do APK não foi incorporada; integração com Termux é distinta. |
| Mídia e Cloud | Ferramentas de biblioteca/hardlinks, limpeza e Google Drive/Rclone com contratos e salvaguardas específicos; operações com dados reais exigem cuidado. |
| Busca | Integração ao Prowlarr/indexadores com funcionalidades documentadas nas fases S1/S1D. |
| Desktop gráfico | Cliente RFB/VNC através de túnel SSH, UX2 aceita no Moto G06. Helper de gestão Iniciar/Encerrar é instalado separadamente, com autorização restrita; sem áudio/streaming gamer. |
| SETUP | Base, complementos, nuvem e recuperação documentados e consolidados na `main`. A instalação completa em **Fedora físico limpo NÃO foi validada**. |

## Limitações que não devem ser escondidas

1. Não existe garantia de instalação automática completa e reproduzível em qualquer Fedora; a [validação em máquina limpa](MYCT-SETUP-INTEGRATION-RELATORIO.md) continua pendente (issue [#53](https://github.com/Samuel-Francisco-AS/My-CT/issues/53)).
2. Atualizar ou compilar o repositório **não** substitui helpers instalados em `~/.local/bin`, unidades systemd, bancos, configuração de SSH, TigerVNC ou serviços externos.
3. A UX2 não foi distribuída como release GitHub correspondente; não invente URLs de APK para essa versão.
4. O risco da operação de remoção com arquivo substituído foi **documentado e aceito**, não corrigido: [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65). Não apresente essa decisão como garantia de proteção.
5. Nenhum teste físico amplo nem auditoria de segurança exaustiva decorre automaticamente de um build bem-sucedido.

## Fonte de evidência e manutenção

Leia os [relatórios por fase](HISTORY.md) para datas, testes e limites, em especial [Desktop UX2](MYCT-DESKTOP-FECHAMENTO.md), [SETUP](MYCT-SETUP-INTEGRATION-RELATORIO.md), [IDE](MYCT-R4-FECHAMENTO.md) e [modo local](MYCT-L1-FECHAMENTO.md). Consulte [arquitetura](ARCHITECTURE.md), [instalação](INSTALLATION.md) e [operações](OPERATIONS.md). Ao aceitar uma nova entrega, atualize **primeiro este estado**, depois o índice e a documentação da área. Não reinterprete registros históricos como estado atual.
