# Arquitetura atual

O My-CT combina **cliente Android nativo** e **componentes independentes no Fedora**. A fronteira entre componentes é deliberada: a interface Android pode ser encerrada sem desligar serviços de download ou o Core.

## Mapa lógico

```text
Android (Kotlin + Compose)
 ├─ Terminal SSH / SFTP ────────────────┐
 ├─ IDE Sora local / SAF / PTY Android   │
 ├─ Saúde via túnel SSH ────────────────┤
 ├─ Downloads / torrent / busca ────────┤  Fedora/Linux
 ├─ Mídia / Cloud / Cofre ──────────────┤  ├─ OpenSSH e ferramentas do usuário
 └─ Desktop RFB via túnel SSH ──────────┘  ├─ My-CT Core (C++20) + PostgreSQL
                                            ├─ CLIs Python/C++ e systemd
                                            └─ serviços externos opcionais
```

O diagrama mostra papéis, não implica que todas as funcionalidades usem uma única API. **SSH/SFTP são independentes da API de saúde do Core.**

## Android

- `android/app/`: aplicativo `dev.myct`, Kotlin/Jetpack Compose; módulos de saúde, terminal, arquivos, downloads, mídia, cloud, desktop, IDE e interfaces relacionadas.
- `android/terminal/`: emulador de terminal derivado apenas dos módulos permitidos do Termux, com manifesto, patches e verificação em `android/terminal/upstream/`.
- Editor Sora `0.23.6` e gramáticas TextMate: consulte [licenças e reconstrução](../android/IDE-LICENSES.md).
- Modo **local**: editor e armazenamento privado/SAF mais terminal PTY próprio. Integrar-se ao Termux instalado não significa que o APK distribua GCC/Clang ou um Linux completo.
- Modo **remoto**: autenticação SSH e verificação de host, SFTP, túneis para serviços e operações explicitamente autorizadas.

## Fedora

- `server/`: Core C++20, endpoints de saúde e acesso `libpq` ao PostgreSQL, com `Makefile` e testes.
- `ops/`: CLIs e módulos operacionais (downloads, torrent, antivírus, vault, cloud, mídia, busca), unidades systemd e módulo `setup/`.
- O PostgreSQL do projeto é uma dependência de recursos específicos; saúde e terminal não precisam que todo o ecossistema esteja habilitado.
- Transmission, Prowlarr, Jellyfin, Rclone, ClamAV e TigerVNC são **serviços/aplicações externos** com seus próprios ciclos de vida, configuração e licenças.
- Desktop: framebuffer RFB/TigerVNC numa porta **loopback** no Fedora, alcançada via túnel SSH; `ops/desktop/` descreve o helper restrito para `status/start/stop`. Não disponibilizar a porta VNC diretamente na Internet.

## Fronteiras e princípios

1. Não publicar endpoints internos, RPCs, bancos ou VNC na rede pública como atalho.
2. Identidade SSH verificada; evitar execução arbitrária ou privilégios globais no Core.
3. Operações destrutivas dependem da ação e decisão do usuário; documentar riscos sem introduzir restrições especulativas.
4. Preservar CPU, RAM, SSD, estabilidade do Fedora e funcionamento headless.
5. Código do Git e binários instalados são objetos diferentes: `git pull` não é deploy.
6. Terceiros conservam suas licenças. Consulte [LICENSING.md](LICENSING.md).

## Fonte da verdade

Para os detalhes de implementação, leia o código atual, [STATUS.md](STATUS.md) e os [fechamentos de fase](HISTORY.md). O antigo [diagrama arquitetural inicial](02-ARQUITETURA-INICIAL.md) permanece como registro de evolução, **não** como lista de funcionalidades atuais.
