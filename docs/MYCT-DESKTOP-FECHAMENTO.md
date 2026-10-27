# MYCT-DESKTOP — fechamento consolidado

**Data do aceite final:** 27/10/2026  
**Projeto:** My-CT — Android nativo e Fedora 44 headless  
**Branch de implementação:** `feat/myct-desktop-vnc`  
**Integração na main:** [PR #64](https://github.com/Samuel-Francisco-AS/My-CT/pull/64)  
**Candidata final aceita:** `0.14.4-desktop-ux2` / `versionCode=57` / `applicationId=dev.myct`  
**Dispositivo de aceite:** Moto G06, testes realizados pelo proprietário  
**Release pública:** não criada neste fechamento

## Resultado e escopo do aceite

A fase Desktop está **aceita funcionalmente no escopo físico informado pelo proprietário**.

- **Desktop-1 / F1:** o My-CT estabeleceu túnel SSH e VNC autenticado, apresentou framebuffer real do Fedora, permitiu mover ponteiro, usar clique direito, navegar no menu Openbox e abrir aplicativos gráficos. A primeira candidata code53 foi reprovada por sobrepintura preta do Canvas; F1 code54 corrigiu os controles e a navegação Android.
- **F2:** o erro de desconectar antes da resposta administrativa foi corrigido. O proprietário instalou manualmente o helper restrito e comprovou, no Moto G06, a consulta de autorização e estado da unidade, o encerramento confirmado, a inicialização confirmada e o retorno da visualização gráfica. Desconectar o cliente não equivale a encerrar o serviço.
- **UX1:** interface com área gráfica prioritária, barra compacta, painéis/diagnóstico sob demanda e tela cheia melhorada; visual aprovado fisicamente. A rotação ainda desconectava o cliente e exigia senha.
- **UX2:** proprietário confirmou em 27/10/2026 a permanência da sessão durante rotações, funcionamento do trackpad e do teclado e estabilidade geral satisfatória no Moto G06.

Esse aceite **não** é evidência de testes físicos exaustivos de toda combinação de gesto, tecla, edição complexa de texto, período prolongado, aplicativos X11, rede e orientação; nem afirma validação integral das demais seções do aplicativo após o merge.

## Arquitetura consolidada

`DesktopView` / `DesktopScreen` (Android, Compose + Canvas + InputConnection) ↔ `DesktopModel` (proprietário do vínculo/estado) ↔ cliente RFB nativo Kotlin ↔ túnel SSH autenticado/JSch com host verificado ↔ `127.0.0.1:5901` no Fedora ↔ TigerVNC `:1` / Openbox.

- O servidor VNC permanece **somente no loopback** do Fedora; não requer porta VNC na LAN/Internet.
- Rotação reanexa uma superfície ao cliente já autenticado por janela limitada de 3 s, sem persistência de senha VNC. Saída real, segundo plano ou perda da sessão SSH liberam conexão e recursos conforme política documentada.
- Cliente nativo sem viewer terceiro no Android e sem gateway WebSocket.
- O gerenciamento usa `/usr/local/libexec/myct-desktop-control` (C++20, `status/start/stop` exclusivamente para `vncserver@:1.service`) sob autorização restrita `/etc/sudoers.d/myct-desktop`. **Nenhum sudo geral** ou porta remota aberta.
- O helper está instalado no Fedora do proprietário e sua reversão manual está descrita em [ops/desktop/README.md](../ops/desktop/README.md). O backup da instalação autorizada foi registrado em `/var/lib/myct-desktop-backups/install.ypDPnoDq`. O merge Git não reinstala ou atualiza esse binário no Fedora.
- O serviço VNC não foi habilitado automaticamente no boot por esta fase.

## Evidências e limites de validação

| Evidência | Resultado / alcance |
| --- | --- |
| JVM UX2 (Codex) | 409 casos: **407 PASS, 2 SKIP**, 0 erros/falhas informados |
| Helper C++ (Codex) | **4 testes PASS** com fixtures; não executaram `start/stop` real durante a suíte |
| Build / lint (Codex) | **PASS**, lint sem erros na candidata code57 |
| Instrumentação | **Compilada, não executada** no Android físico/emulador |
| Aceite físico F1/F2/UX1 | Imagem e interação real; gerenciamento systemd e visual UX1, conforme relatórios individuais |
| Aceite físico UX2 | Rotação sem perda de sessão, trackpad e teclado funcionais, estabilidade geral aceita pelo proprietário |
| GitHub CI | Nenhum check/status publicado foi encontrado no SHA UX2 durante a auditoria de integração; sem alegação de CI verde |
| Métricas físicas UX2 | Sem medição registrada de FPS, latência, PSS prolongado, consumo de bateria ou aquecimento |

Os dois SKIP citados na entrega são `FedoraHandshakeTest` e `IdeFedoraSftpTest`. O APK assinado de candidata `artifacts/myct-desktop-0.14.4-ux2.apk`, seus hashes e logs locais são **artefatos não versionados**, não parte do merge; os fontes/testes/docs são versionados. Não inventar link de download GitHub nem release pública.

## Pendências não bloqueantes / acompanhamento

1. **Rotação lenta:** se o Android exceder os 3 s de reanexação, é esperado encerrar a conexão e solicitar nova autenticação. Não armazenar senha para ocultar essa situação.
2. **IME:** composição e correções limitadas ao sufixo local conhecido; edição arbitrária de texto remoto, caracteres complexos e comportamento específico de apps X11 exigem validações próprias.
3. **Ergonomia/performance:** avaliar uso prolongado, métricas reais no Moto G06, eventuais melhorias de toque e consumo, sem reabrir a fase concluída automaticamente.
4. **Keyring gráfico:** a sessão Openbox via TigerVNC pode solicitar desbloqueio do chaveiro GNOME; evitar desbloqueio automático ou redução de proteção para esconder o diálogo.
5. **Instrumentação:** cenários Android foram compilados e simulados onde indicado, mas ainda podem ser executados futuramente em dispositivo habilitado.
6. **Sessão remota:** sem áudio, streaming de jogos, clipboard VNC sincronizado ou recuperação automática ilimitada; tais funcionalidades não fizeram parte desta entrega.

## Uso cotidiano

No Moto G06, estabelecer primeiro a sessão SSH confiável do My-CT. Abrir **Desktop**; se a unidade estiver ativa, usar **Conectar** e fornecer a senha VNC localmente. Usar mouse, trackpad, teclado e modo imersivo. **Desconectar** encerra apenas a visualização; **Encerrar Desktop** exige confirmação e fecha programas gráficos. O comando **Iniciar Desktop** depende da autorização restrita já instalada pelo proprietário.

Consulta não destrutiva no Fedora, se necessária:

```sh
systemctl show vncserver@:1.service --property=ActiveState,SubState,UnitFileState
```

Preservar SELinux Enforcing, SSH host-key verification, configuração loopback TigerVNC e configurações de Fedora. Não repetir `start/stop` apenas por validação de código que não modifica a administração.

## Integração e retomada

- `main` e `feat/myct-desktop-vnc` foram auditadas antes de integrar: branch Desktop 3 commits à frente e 0 atrás da main, com base comum `706ef2d60c5a0fb005ad58c16fb86ce87e2d1b5a`, no instante da comparação inicial.
- [PR #64 — Desktop e UX1/UX2](https://github.com/Samuel-Francisco-AS/My-CT/pull/64) consolida a integração autorizada pelo proprietário. Consultar o próprio PR para o status final e hash do merge; este relatório é escrito **antes** da operação de merge.
- Não realizar force push, não apagar branches históricas, não gerar release nem reiniciar o Fedora para integrar o Git. A sincronização do clone local com `main` após integração é passo separado e deve preservar eventuais alterações locais.

## Relatórios por fase

- [Desktop-1](MYCT-DESKTOP-1-RELATORIO.md)
- [F1](MYCT-DESKTOP-F1-RELATORIO.md)
- [F2](MYCT-DESKTOP-F2-RELATORIO.md)
- [UX1](MYCT-DESKTOP-UX1-RELATORIO.md)
- [UX2 e aceite posterior](MYCT-DESKTOP-UX2-RELATORIO.md)
- [Helper, instalação e reversão](../ops/desktop/README.md)

**Decisão de fechamento:** fase Desktop aprovada no escopo comprovado, com limitações documentadas e integração à main autorizada. O encerramento do PR confirma apenas a integração Git, não substitui os aceites físicos registrados nem instala nada no servidor.
