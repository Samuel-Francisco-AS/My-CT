# MYCT-IDE — salvamento remoto sem resíduos de backup

Data: 22/10/2026. Candidata de código para validação; não declarar aceite no Moto G06 sem teste físico.

## Motivo
O salvamento remoto SFTP da R4 mantinha sempre um hardlink do original em `.myct-edit-UUID/original`, inclusive após sucesso. Isso sujava árvores de trabalho Git durante contribuições open source.

## Comportamento desta candidata
- Salvar continua explícito. O original não é truncado; conteúdo e metadados são conferidos antes de publicar por rename atômico.
- O hardlink de recuperação existe apenas durante a transação. Após a nova versão ser relida e verificada, a IDE remove `new`, `original` e o diretório `.myct-edit-UUID`.
- Se a gravação falhar **antes** da publicação, a IDE tenta remover todos os temporários. Se a limpeza falhar, exibe o diretório residual explicitamente.
- Se o rename ou a verificação final falharem, o estado é incerto: mantém cópia de recuperação e bloqueia novos saves até conferência. Nunca apaga a única cópia segura por preferência estética.
- Se a publicação for confirmada mas a limpeza falhar, a IDE registra o arquivo como salvo e informa o diretório pendente em Detalhes, sem apagar o código.
- Criação de arquivos na workspace local e SAF não mudou. A workspace local já remove temporários de gravação após uso; versões internas de provedores SAF não são gerenciadas pelo My-CT.
- Backups `.myct-edit-*` **anteriores** a esta versão NÃO são apagados automaticamente: podem ser cópias necessárias para recuperar edições antigas.

## Verificação antes de merge
1. Executar `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline` no ambiente de build configurado.
2. Executar `MYCT_TEST_LOCAL_SFTP=1` com os testes JVM para exercitar o servidor SFTP real do fixture (sem acessar o Fedora pessoal).
3. No Moto G06, editar e salvar arquivo descartável dentro de repositório de teste Fedora; conferir `git status --short --untracked-files=all` e `find . -name '.myct-edit-*' -print`. Esperado: apenas alteração desejada no arquivo, nenhum resíduo novo.
4. Repetir dois saves, conflito externo, cancelamento antes do commit e desconexão simulada. Confirmar que o buffer permanece e que só a situação incerta retém recuperação.
5. Não realizar limpeza global nem instalar APK sem aceite do proprietário.

Limite fundamental: desligamento abrupto, falha de canal ou queda do servidor podem impedir a limpeza. A correção elimina retenção **intencional** após sucesso, não garante ausência absoluta de vestígios em todas as falhas do sistema.
