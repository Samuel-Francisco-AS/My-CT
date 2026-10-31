# Instalação e primeiros passos

Este guia separa **compilar o cliente Android** de **preparar o Fedora servidor**. O My-CT não é, neste momento, uma distribuição com instalação ponta a ponta validada para qualquer máquina. **Não execute provisionamento administrativo automaticamente.**

## 1. Requisitos

- Cliente: Android com API 26 ou superior (conforme `minSdk`). O aceite físico da UX2 foi feito no Moto G06; outros aparelhos não estão automaticamente homologados.
- Para compilar: Git, JDK compatível com o projeto (JDK 21 nos ensaios), Android SDK para `compileSdk=36`, Gradle wrapper do repositório, CMake `3.22.1` e NDK `28.2.13676358`, conforme `android/app/build.gradle.kts`. As dependências podem exigir acesso aos repositórios Gradle/SDK durante a preparação inicial.
- Para funções remotas: Fedora/Linux com OpenSSH e permissões do usuário; recursos adicionais dependem de Core, PostgreSQL, Transmission, Prowlarr, Rclone, Jellyfin, ClamAV e TigerVNC quando utilizados. **Eles não são requisitos obrigatórios de todos os modos.**

## 2. Obter o código e compilar o APK

```bash
git clone https://github.com/Samuel-Francisco-AS/My-CT.git
cd My-CT/android
./gradlew :app:assembleDebug
```

O APK de desenvolvimento fica em `android/app/build/outputs/apk/debug/app-debug.apk` (relativo à raiz do repositório). Instale-o somente em dispositivo autorizado e confirme a assinatura antes de atualizar um app existente: APKs assinados com chaves diferentes **não** são atualizações compatíveis. **Não desinstale nem limpe os dados** para contornar uma incompatibilidade sem planejar backup e migração.

Validações opcionais antes de instalar:

```bash
# Na raiz do projeto:
python3 android/terminal/upstream/verify.py

# No diretório android/:
./gradlew :app:testDebugUnitTest
```

O `--offline` do Gradle só funciona quando plugins e dependências já estão em cache. Não substitua arbitrariamente o JDK/SDK se o build falhar: confira a configuração de seu ambiente e o log primeiro.

## 3. Conectar ao Fedora existente

1. Tenha um host Linux **administrado por você**, com SSH operacional na rede privada. Confirme a identidade da chave do host e a conta permitida.
2. Abra o My-CT e configure a sessão SSH usando seus próprios dados. Não compartilhe credenciais em logs, prints, issues ou pull requests.
3. Teste primeiro **Terminal** e **Arquivos/SFTP**, antes de habilitar recursos de mídia, cloud ou desktop.
4. Configure cada serviço adicional **separadamente**, conforme seu cenário. O app não instala nem habilita todos os serviços automaticamente ao ser instalado.

Para operação e observação dos serviços sem alterações, use [OPERATIONS.md](OPERATIONS.md). Para montagem e compilação do Core, consulte [server/README.md](../server/README.md).

## 4. Provisionamento SETUP — limite importante

O módulo SETUP permite diagnóstico e planos com aprovação humana para Base e componentes opcionais. Sua integração ao código foi documentada na [PR #59](https://github.com/Samuel-Francisco-AS/My-CT/pull/59). **O fluxo completo em Fedora físico recém-instalado ainda NÃO foi validado.**

- Comece pelo **diagnóstico**, leia todas as ações propostas e os privilégios solicitados.
- Não aplique planos em um host que tenha serviços, bancos ou arquivos importantes sem backup, revisão e autorização do proprietário.
- Não copie comandos de instalação específicos de relatórios antigos sem confirmar se ainda se aplicam. Consulte [plano SETUP](MYCT-SETUP-PLANO.md) e [resultado da integração](MYCT-SETUP-INTEGRATION-RELATORIO.md).
- Complementos como Transmission, Prowlarr e Rclone têm planos e autenticações distintos; OAuth do Google exige interação humana.
- O Desktop gráfico usa uma sessão VNC já configurada e um helper administrativo opcional, cuja instalação é **manual e deliberada** ([guia Desktop](../ops/desktop/README.md)).

## 5. Limitações e distribuição

- O APK UX2 0.14.4 foi aceito em aparelho real, mas não há link de release pública confirmado para essa versão. Verifique [Releases](https://github.com/Samuel-Francisco-AS/My-CT/releases) e a versão de cada artefato antes de instalar.
- Compilar não instala helpers nem altera o Fedora. Para atualizar o código de um serviço já instalado, é preciso um procedimento de implantação **autorizado**, distinto de `git pull`.
- Para redistribuir APK/código, preserve [LICENSE](../LICENSE), [NOTICE](../NOTICE) e [licenças de terceiros](LICENSING.md).

Voltar ao [README](../README.md) ou consultar o [estado atual](STATUS.md).
