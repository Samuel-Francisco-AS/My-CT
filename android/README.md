# Cliente Android — My-CT

**Estado documentado em 31/10/2026:** a `main` contém `dev.myct`, `versionName=0.14.4-desktop-ux2` e `versionCode=57` (confira o [Gradle](app/build.gradle.kts)). O Desktop UX2 foi aceito no Moto G06 no escopo registrado; esta versão **não tem release APK pública correspondente confirmada**.

Para a visão do sistema completo, consulte o [README principal](../README.md), [estado atual](../docs/STATUS.md) e [instalação](../docs/INSTALLATION.md).

## Módulos e usos

- **Terminal remoto:** conexão SSH com verificação da chave do host, emulação baseada nos módulos Termux incorporados, teclas extras e teclado Android.
- **Arquivos:** SFTP, upload/download, SAF e navegação entre arquivos locais e do Fedora.
- **Saúde, downloads, torrent, cofre, mídia, Cloud e busca:** acessam Core, CLIs ou serviços configurados separadamente no Fedora. Não presumir que todos estejam instalados.
- **IDE:** editor Sora 0.23.6 para arquivos locais/remotos, gramáticas TextMate, salvamento e ferramentas de edição.
- **Terminal e workspace locais:** PTY Android e arquivos acessíveis offline. O APK **não inclui GCC/Clang nem um compilador C offline**; ferramentas do app Termux, se utilizadas, são externas.
- **Desktop:** RFB/VNC por túnel SSH para sessão Fedora já configurada; rotações, trackpad e teclado aceitos em UX2. Iniciar/Encerrar dependem de helper restrito instalado à parte; desconectar a visualização não encerra programas.

Veja [arquitetura](../docs/ARCHITECTURE.md) e [limites de aceite](../docs/STATUS.md).

## Compilar

Requisitos: JDK adequado (JDK 21 nos ensaios), Android SDK `compileSdk=36`, NDK `28.2.13676358` e CMake `3.22.1` conforme [`app/build.gradle.kts`](app/build.gradle.kts). O wrapper gerencia Gradle; o cache offline deve estar previamente preenchido.

```bash
# A partir de android/
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

O APK aparece em `app/build/outputs/apk/debug/app-debug.apk`. A versão e assinatura determinam a possibilidade de atualizar um aplicativo instalado sem perda de dados; não desinstale para contornar assinaturas incompatíveis sem planejar migração.

Verificar também os 21 arquivos upstream e três patches documentados do terminal:

```bash
# A partir da raiz do repositório
python3 android/terminal/upstream/verify.py
```

O build Gradle não comprova funcionamento físico. Para validar mudança de interface, SSH/SFTP ou Desktop, registre teste real no aparelho e o cenário exercitado.

## Direitos autorais e dependências

O código original My-CT é Apache-2.0, mas módulos Termux específicos, Sora (LGPL-2.1-or-later), TM4E/EPL, JSch, jBCrypt e gramáticas preservam licenças próprias. Consulte [IDE-LICENSES.md](IDE-LICENSES.md), [licenciamento geral](../docs/LICENSING.md), `app/src/main/assets/THIRD_PARTY_LICENSES.txt` e `app/src/main/assets/ide/`.

## Histórico e detalhes de fases

Os fechamentos de [Android inicial](../docs/08-FECHAMENTO-CICLO-ANDROID.md), [IDE R4](../docs/MYCT-R4-FECHAMENTO.md), [modo local L1](../docs/MYCT-L1-FECHAMENTO.md) e [Desktop UX2](../docs/MYCT-DESKTOP-FECHAMENTO.md) preservam decisões, testes e limitações do momento. O antigo README Android extenso está disponível no [Git histórico antes da reforma](https://github.com/Samuel-Francisco-AS/My-CT/blob/c3023973800ba5a7dad907ccf6265d405c8f5523/android/README.md). Nele, afirmações de versões antigas não devem ser tratadas como atuais.
