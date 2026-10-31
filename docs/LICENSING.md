# Licenciamento do My-CT

O código, a documentação e os recursos **originais do My-CT** são
disponibilizados sob a **Apache License, Version 2.0** (SPDX: `Apache-2.0`),
exceto quando houver indicação explícita de outra licença. O texto integral
está em [`../LICENSE`](../LICENSE); as atribuições estão em [`../NOTICE`](../NOTICE).

A licença permite usar, estudar, modificar, redistribuir e comercializar o
código licenciado, inclusive em derivados proprietários, observadas as
obrigações da própria Apache-2.0 (inclusive avisos de copyright, indicação de
arquivos alterados e preservação de `NOTICE` quando aplicável).

## Código e recursos de terceiros incorporados

**A licença raiz não substitui nem relicencia arquivos de terceiros.**
Preserve seus avisos e cumpra suas licenças ao copiar, modificar, compilar
ou redistribuir o código e os APKs:

| Componente | Licença / observações | Referência no repositório |
| --- | --- | --- |
| Termux `terminal-emulator` e `terminal-view` | Apache-2.0, conforme exceção do upstream; patches My-CT documentados | [`../android/terminal/upstream/`](../android/terminal/upstream/README.md), [licença upstream](../android/terminal/upstream/LICENSE.termux.md) |
| Sora Editor | LGPL-2.1-or-later; preservar possibilidade de modificar e substituir os componentes LGPL distribuídos | [`../android/IDE-LICENSES.md`](../android/IDE-LICENSES.md), [avisos da IDE](../android/app/src/main/assets/ide/NOTICE.txt) |
| Componentes TM4E e anotações Eclipse | EPL-2.0, com outros trechos MIT; direitos preservados | [licenças e avisos da IDE](../android/app/src/main/assets/ide/NOTICE.txt) |
| Gramáticas TextMate / VS Code | MIT e outras licenças permissivas conforme cada fonte | [proveniência](../android/app/src/main/assets/ide/SOURCES.txt), [avisos da IDE](../android/app/src/main/assets/ide/NOTICE.txt) |
| JSch / jBCrypt | BSD-style / ISC-style | [avisos incluídos no APK](../android/app/src/main/assets/THIRD_PARTY_LICENSES.txt) |
| Bouncy Castle | MIT | [avisos incluídos no APK](../android/app/src/main/assets/THIRD_PARTY_LICENSES.txt) |
| AndroidX, Compose e demais dependências | Licenças próprias, predominantemente Apache-2.0 | [configuração Gradle](../android/app/build.gradle.kts), [avisos do APK](../android/app/src/main/assets/THIRD_PARTY_LICENSES.txt) |

Os programas externos que o My-CT utiliza por comandos, serviços ou APIs
(por exemplo, PostgreSQL, Jellyfin, Transmission, Prowlarr, Rclone e ClamAV)
**não passam a ter licença Apache-2.0** apenas por integrarem a solução.
Cada distribuição desses programas deve respeitar seus próprios termos.

## Distribuição e contribuições

- Ao distribuir cópias e versões derivadas do material Apache-2.0, inclua
  uma cópia da licença e preserve os avisos pertinentes, inclusive o `NOTICE`.
- Identifique de maneira visível os arquivos modificados por terceiros,
  conforme a seção 4(b) da Apache-2.0.
- Ao distribuir o APK, preserve o material de licenças empacotado em
  `assets/THIRD_PARTY_LICENSES.txt` e `assets/ide/`, além de cumprir as
  condições específicas da LGPL/EPL e demais licenças aplicáveis.
- Contribuições intencionalmente submetidas ao projeto seguem a seção 5
  da Apache-2.0, salvo declaração expressa em contrário ou acordo separado.
- O histórico de autoria e os avisos de copyright preexistentes são
  preservados; a inclusão desta licença não altera a titularidade de terceiros.

Para atualizar bibliotecas ou copiar novo código externo, confira a licença
da versão exata e atualize as atribuições correspondentes antes de distribuir.
