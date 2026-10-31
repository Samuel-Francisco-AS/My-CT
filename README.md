# My-CT

**Seu Fedora no bolso.** O My-CT é um projeto open source que conecta um aplicativo Android nativo a um computador Fedora/Linux, sem exigir um monitor ligado nem depender permanentemente de serviços de IA.

Pelo celular, você pode acessar um **terminal SSH**, transferir arquivos por **SFTP**, editar código, acompanhar a saúde do servidor e interagir com downloads, torrents, mídia, nuvem e um desktop gráfico — cada recurso respeitando o serviço necessário no Fedora. Há também ferramentas locais do aplicativo que funcionam sem conexão com o PC.

> **Estado do projeto (31/10/2026):** desenvolvimento ativo. A `main` contém o Android `0.14.4-desktop-ux2` (código 57); o Desktop UX2 recebeu aceite funcional no Moto G06. **A instalação ponta a ponta em Fedora físico limpo ainda não foi validada.** O APK UX2 dessa versão não tem release pública correspondente confirmada. Veja o [estado detalhado](docs/STATUS.md).

## O que você encontra

| Funcionalidade | O que faz |
| --- | --- |
| Terminal | SSH real com emulação de terminal baseada nos módulos permitidos do Termux, teclas extras e verificação da identidade do host |
| Arquivos | Navegação e transferência SFTP, além de acesso Android via SAF |
| Saúde e cofre | Informações do Fedora via Core C++20; integração com GNOME Keyring por desbloqueio humano |
| Downloads e BitTorrent | Controla ferramentas independentes do Android, incluindo integração ao Transmission |
| IDE | Editor Sora para arquivos locais e remotos, com gramáticas e ferramentas de edição |
| Terminal local | PTY Android separado da sessão SSH; integração opcional com ferramentas externas como Termux |
| Mídia e nuvem | Ferramentas para organização de bibliotecas/hardlinks e armazenamento via Rclone/Google Drive |
| Busca | Integração com Prowlarr e indexadores configurados pelo usuário |
| Desktop | Visualização e controle de uma sessão TigerVNC via túnel SSH; gestão do serviço com helper restrito opcional |
| SETUP | Diagnóstico e planos supervisionados para preparar componentes do Fedora; instalação limpa integral ainda não validada |

Os serviços externos **não** são iniciados ou instalados simplesmente porque o APK foi instalado. Recursos como torrents, antivírus, nuvem e desktop exigem configuração apropriada. Leia os [limites de validação](docs/STATUS.md).

## Comece pelo caminho certo

1. **Conhecer:** leia a [visão do estado atual](docs/STATUS.md) e a [arquitetura](docs/ARCHITECTURE.md).
2. **Compilar o Android:** siga [Instalação e primeiros passos](docs/INSTALLATION.md). É preciso JDK, SDK Android, NDK/CMake e o Gradle Wrapper.
3. **Conectar:** use uma máquina Linux **sua**, com SSH configurado e chave do host verificada. Comece com Terminal e Arquivos antes dos módulos opcionais.
4. **Diagnosticar o Fedora existente:** veja [Operações](docs/OPERATIONS.md). Não aplique comandos administrativos sem entender o impacto.
5. **Obter versões publicadas:** consulte [Releases](https://github.com/Samuel-Francisco-AS/My-CT/releases) e confira a tag/versão de cada APK. Releases antigas não representam necessariamente a `main` atual.

Exemplo de compilação do APK de desenvolvimento, **sem instalar serviços no Fedora**:

```bash
git clone https://github.com/Samuel-Francisco-AS/My-CT.git
cd My-CT/android
./gradlew :app:assembleDebug
```

O artefato gerado fica em `android/app/build/outputs/apk/debug/app-debug.apk`. Para atualizar um app existente sem perder dados, o APK precisa ter assinatura compatível; não desinstale nem limpe os dados para contornar isso.

## Como é construído

- **Android:** Kotlin, Jetpack Compose, JSch, Sora Editor e módulos Termux incorporados com proveniência documentada.
- **Fedora:** C++20, `libpq`/PostgreSQL e CLIs Python/C++, com serviços independentes administrados pelo usuário.
- **Integração:** SSH/SFTP e túneis para recursos específicos; o modo Android local funciona separadamente de serviços do Fedora.
- **Filosofia:** autonomia, desempenho, simplicidade, alterações revisáveis e ausência de dependência obrigatória de IA.

Detalhes e diagramas: [Arquitetura](docs/ARCHITECTURE.md).

## Documentação

| Para… | Leia |
| --- | --- |
| Saber o que foi implementado, aceito e ainda falta | [Estado atual](docs/STATUS.md) |
| Instalar/compilar com limites claros | [Instalação](docs/INSTALLATION.md) |
| Entender componentes e limites | [Arquitetura](docs/ARCHITECTURE.md) |
| Rodar testes e desenvolver | [Desenvolvimento](docs/DEVELOPMENT.md) |
| Diagnosticar serviços existentes | [Operações](docs/OPERATIONS.md) |
| Consultar todos os relatórios de fase | [Histórico e evidências](docs/HISTORY.md) |
| Navegar por todos os guias | [Índice da documentação](docs/README.md) |

Os relatórios antigos foram **preservados**, mas podem falar de funcionalidades ainda pendentes em versões anteriores. Verifique a data e o [estado atual](docs/STATUS.md) antes de seguir qualquer procedimento histórico.

## Contribuir e segurança

Contribuições, relatórios de bugs e melhorias bem delimitadas são bem-vindos. Comece em [CONTRIBUTING.md](CONTRIBUTING.md) e consulte [SECURITY.md](SECURITY.md) antes de divulgar uma vulnerabilidade. Algumas limitações são riscos conhecidos e aceitos pelo mantenedor, como a [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65); não trate ausência de correção como desconhecimento do problema.

## Licença

O **código original do My-CT** é disponibilizado sob [Apache License 2.0](LICENSE). Bibliotecas e recursos de terceiros (incluindo Termux, Sora Editor, JSch e gramáticas) mantêm **suas próprias licenças**; consulte [NOTICE](NOTICE) e [Licenciamento](docs/LICENSING.md).

---

O My-CT está em evolução. O objetivo não é prometer compatibilidade universal, mas tornar o Fedora e as ferramentas de desenvolvimento úteis a partir do Android, com comportamento verificável e respeito às escolhas do usuário.
