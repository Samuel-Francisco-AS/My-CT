# MYCT Media — interface Android, entrega candidata (18/10/2026)

Branch: myct-media-android, derivada da myct-media-hybrid-mvp. PR empilhado: base na branch do backend, não na main. **Sem merge e sem APK construído nesta sessão.**

## Funcionalidade implementada no código Android

- Downloads agora inclui uma terceira seção: **Mídia** ao lado de HTTP/HTTPS e Torrents.
- A seção utiliza a sessão SSH já autenticada, exclusivamente para executar `myct-media` (no Fedora).
- **Atualizar prévia:** consulta `myct-media scan` e `myct-media list`. Não importa arquivos.
- **Importar reconhecidos…:** mostra confirmação antes de executar `myct-media sync`, sem sobrescrever destinos ocupados.
- **Revisão manual:** classificar como série/anime (título, temporada, episódio) ou filme (título, ano); ignorar item desconhecido com confirmação.
- **Na biblioteca:** lista exclusivamente os hardlinks registrados pela CLI e permite remover cada um com confirmação. O original do Transmission é preservado.
- Nenhum token Jellyfin é necessário: o Jellyfin detectou automaticamente o episódio Frieren S01E02 importado pelo proprietário.
- Não altera scanner, Transmission, Jellyfin, timer, permissões, acesso ao cofre ou Core do Fedora.

## Antes de instalar no Moto G06

O worktree do backend já está criado em ~/Projetos/My-CT-media e contém o código do PR #36, mas a branch Android ainda não foi trazida ao Fedora. Ela poderá ser verificada em OUTRO worktree:

    cd ~/Projetos/My-CT
    git fetch origin
    git worktree add --detach ../My-CT-media-android origin/myct-media-android
    cd ../My-CT-media-android
    python3 -m unittest discover -s ops/tests -p 'test_media.py' -v
    cd android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain

Se Gradle falhar, registrar a saída real e não publicar release. Confirmar packageId dev.myct, versionCode e assinatura antes de sobrepor o APK instalado. Não desinstalar o My-CT nem limpar dados do aplicativo. Novas alterações de versionCode, APK, assinatura e release devem ser avaliadas com os PRs de navegação em aberto para não regredir versão.

## Roteiro físico quando o APK existir

1. Conectar via terminal SSH já fixado e abrir Downloads → Mídia.
2. Atualizar prévia e conferir reconhecidos, pendentes e existentes.
3. Classificar um vídeo de teste confiável (não propaganda); checar hardlink, seeding e reprodução no Jellyfin.
4. Ignorar arquivo promocional e confirmar que não foi importado.
5. Confirmar importação de um lote pequeno. Conferir o registro da CLI, conteúdo Jellyfin e consumo de disco.
6. Retirar um link gerenciado, verificar que desaparece do Jellyfin após a atualização e que o arquivo fonte continua intacto no Transmission.
7. Desconectar/reconectar SSH e confirmar que a listagem continua correta.
8. Timer opcional separado, somente após aceite do fluxo Android.

## Limites

Não houve compilação Android, execução de testes JVM, assinatura, release nem validação física nesta sessão de GitHub. O passo seguinte é passar os testes no Fedora, corrigir eventuais problemas e gerar o APK mantendo o pacote, o certificado e uma versão superior à instalada. O PR #35 (navegação) e #34 (magnet) permanecem intocados.
