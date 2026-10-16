# MYCT-5D-A — inspeção e integridade de arquivo individual

16/10/2026 · Issue [#18](https://github.com/Samuel-Francisco-AS/My-CT/issues/18).
**ACEITA fisicamente pelo proprietário no Moto G06 em 16/10/2026.** Base `73255f8`,
`main` limpa e sincronizada por fast-forward antes da branch
`myct-5d-a-file-inspection`. Antes da validação física, nenhum merge ou instalação tinha sido executado pelo agente. A validação ocorreu com APK 0.5.5/code 12 e helper disponível no Fedora; fechamento no PR #19.

## Fechamento — aceite físico no Moto G06 (16/10/2026)

**Aceite expresso do proprietário nesta conversa, com quatro capturas de tela anexadas.** Foram confirmados os seis testes combinados para o núcleo 5D-A:

1. Na aba Arquivos, localização do arquivo regular de prova no diretório Downloads.
2. Inspeção via **Verificar arquivo**, identificando conteúdo `abc`, **3 bytes**, MIME `text/plain` e SHA-256 esperado `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
3. Cópia e inserção de checksum correto → **CORRESPONDE**.
4. Alteração de um dígito hexadecimal → **DIVERGENTE**.
5. Limpeza do campo opcional → **NÃO INFORMADO**.
6. Encerramento do relatório com **terminal SSH ainda funcionando**.

**Resultado:** MYCT-5D-A **PASS** no fluxo mínimo real Fedora → SSH → APK 0.5.5/code 12 → relatório/checksum. As capturas foram fornecidas na conversa de aceite e não são arquivos versionados no repositório. Não confundir este aceite com ensaio físico de todas as situações adversas, inspeção de torrents/HTTP individuais, hash de arquivo gigante ou cancelamento prolongado. Esses testes ampliados não são necessários para declarar concluído o recorte aprovado.

**Fechamento autorizado:** integrar [PR #19](https://github.com/Samuel-Francisco-AS/My-CT/pull/19) à `main` e posteriormente sincronizar o checkout Fedora. Nenhum serviço, download em andamento, seeding, banco ou cofre será alterado pelo merge. **MYCT-5D-B (ClamAV) não iniciada** e requer autorização explícita. A issue #18 permanece aberta para 5D-B e opções futuras de recheck de peças; dívidas da #9 permanecem separadas.

---

## Entrega

Na aba **Arquivos**, tocar num arquivo regular → **Verificar arquivo**. Relatório
com nome, caminho, bytes exatos/tamanho decimal legível, extensão, MIME por
conteúdo e SHA-256 completo. Copiar SHA-256 e colar/digitar checksum conhecido:
**CORRESPONDE**, **DIVERGENTE**, **NÃO INFORMADO**; entrada inválida exige 64
caracteres hexadecimais. Comparação ignora maiúsculas e espaços nas extremidades.
Extensão reconhecida pelo Android diferente do MIME gera indicação dos dois tipos;
não é uma classificação de risco.

Funciona para arquivos regulares sob o home, incluindo Downloads/My-CT e arquivos
individuais das pastas de torrents. Downloads/BitTorrent não foram alterados.
Atalho próprio em Downloads e recheck de peças ficam para etapa posterior.

## Implementação e limites

- `server/inspect.cpp`: CLI C++20 `myct-inspect`; Makefile compila com `-lcrypto`.
  Usa OpenSSL EVP já instalado para SHA-256 por streaming de 64 KiB e
  `/usr/bin/file` por `fork`/`exec`, sem shell, para MIME por conteúdo.
- SSH exec independente reutiliza autenticação/host key existentes. O caminho
  UTF-8 chega codificado em hexadecimal (apenas `[0-9a-f]` no argumento shell).
  Sem transferência do conteúdo ao Android. Resposta JSON v1, limitada pelo
  leitor SSH existente a 256 KiB por saída; erros vão ao stderr/status não zero.
- Escopo: **home do usuário SSH obtido por `getpwuid`, sem confiar em `$HOME` para
  autorizar arquivos**. SFTP pode navegar além desse escopo, mas a inspeção recusa.
  `openat2(RESOLVE_BENEATH | RESOLVE_NO_SYMLINKS)` recusa symlinks em qualquer
  componente, traversal e escapes; somente arquivo regular aberto para leitura.
  Falha fechada se o kernel não oferecer `openat2` (disponível no Fedora 44).
- `file` recebe o inode já aberto por `/proc/self/fd`, não reabre o caminho
  fornecido. Limite de 5 s para identificação. Ferramenta ausente/sem identificação
  apresenta aviso, preservando o SHA calculado. `application/octet-stream` é
  tratado como formato não identificado. Nenhum arquivo inspecionado é executado.
- Tamanho, mtime e ctime são conferidos após hashing; mudança/erro não produz
  relatório de sucesso. Isso não é snapshot nem proteção contra escritor hostil.
  Para downloads em andamento, aguardar conclusão e verificar novamente.
- Android usa `runInterruptible(Dispatchers.IO)` e indicador indeterminado.
  Cancelar/fechar relatório ou sair do app cancela a operação. Recriação da
  Activity conserva o estado em memória; sem fila, histórico ou retomada.
  Fechar stdin/exec envia EOF; o helper verifica EOF entre blocos e durante a
  identificação, encerrando o trabalho. Queda TCP depende da detecção SSH.
  Leitura de disco bloqueada no kernel pode atrasar cancelamento.
- Limite de 30 min por inspeção; timeout/falha de canal não fecha deliberadamente
  PTY nem a sessão principal. Uma queda da conexão compartilhada afeta todas as
  abas. Somente o proprietário poderá comprovar o fluxo real SSH/Moto.
- Nenhum daemon, API, banco, serviço, pacote, configuração global ou credencial
  alterado. Sem antivírus, recursão, quarentena, envio externo ou recheck torrent.
  SHA-256 sozinho não comprova origem nem ausência de malware.

## Ambiente e validação

Fedora 44; g++ 16.2.1, file 5.46, sha256sum/coreutils 9.10,
OpenSSL 3.5.9 (headers/libcrypto presentes), JBR do Android Studio e SDK 36.
Não foi necessário instalar dependências. `sha256sum` permanece disponível para
conferência manual; o helper usa EVP para controlar cancelamento sem processo de
hash separado.

Comandos:

```sh
make -C server test
python3 -m unittest discover -s ops/tests -v
ANDROID_HOME="$HOME/Android/Sdk" JAVA_HOME=/opt/android-studio/jbr \
  ./android/gradlew -p android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline
git diff --check
```

- Servidor: **18/18 PASS**, incluindo 7 testes novos: SHA conhecido `abc`,
  conteúdo diferente da extensão, ausente, sem permissão, espaços/acentos/aspas/
  metacaracteres, symlink final/intermediário, fora do home, traversal,
  diretório/FIFO, vazio/binário, EOF/cancelamento e alteração concorrente.
- Operacionais existentes: **13/13 PASS**; sem testes físicos repetidos.
- Android: **72 PASS / 1 opcional ignorado**, build/assembleDebug e lintDebug aprovados;
  lint com 0 erros, 2 avisos de versão e 1 sugestão de estado Compose.
- Testes JVM novos: comparação correta/incorreta/ausente/inválida, comando real
  `/bin/sh` com filename hostil sem execução, parsing incompleto, CLI ausente,
  falha remota, interrupção e liberação do canal. Teste de teardown JSch real com
  transporte **simulado** confirma EOF/fechamento de exec preservando PTY e sessão;
  não é prova autenticada no aparelho.
- Medição `/usr/bin/time`, fixture sintética de 64 MiB (zeros/sparse): **0,29 s**
  elapsed, 0,26 s user/0,03 s system, pico **25.340 KiB RSS** (inclui `file`).
  Tamanho/mtime preservados. Binário ~40 KiB. Não representa taxa de disco real
  nem teste de carga durante seeding.
- Hipótese corrigida no teste: bytes 0..255 foram reconhecidos pelo `file` como
  formato válido; fixture trocada por zeros para exercitar tipo não identificado.
  Teste de EOF inicialmente omitia o tamanho de pacote negociado no transporte
  simulado; corrigida a fixture, confirmou pacotes SSH EOF/close sem desconectar
  PTY/sessão. Nenhuma mudança de produção foi necessária para esse ajuste.

## APK utilizado no aceite físico

`0.5.5-file-inspection`, code **12**, pacote `dev.myct`, debug.
Release de pré-lançamento: [myct-5d-a-v0.5.5-rc1](https://github.com/Samuel-Francisco-AS/My-CT/releases/tag/myct-5d-a-v0.5.5-rc1).
Asset `myct-5d-a-0.5.5-debug.apk` e arquivo `.sha256`.

```text
25b3a14f0678d798d6793d960d444f8faef039d0c416a75a6e5cf4151f3d7b0c  myct-5d-a-0.5.5-debug.apk
```

Assinatura SHA-256 do certificado conferida igual à 0.5.4
(`b788958be4c919889fc30813f13f1aceb5b6b69670514c82e16288f4b60b83b3`). Instalar por atualização, sem desinstalar/limpar dados.
APK não instala o helper nem modifica o servidor.

## Instalação manual do helper (instruções de referência)

Nenhum comando abaixo foi executado como instalação nesta entrega. Depois de
revisar/obter a branch/PR no Fedora:

```sh
cd ~/Projetos/My-CT
git fetch origin
git switch myct-5d-a-file-inspection
make -C server myct-inspect
install -Dm0700 server/myct-inspect "$HOME/.local/bin/myct-inspect"
```

Instala somente a nova CLI do usuário. Não requer sudo, restart, parada de torrent,
atualização do `myct-download`/hook ou alteração de Core/Cofre. Checkout Git não
atualiza executáveis instalados. Para remover a funcionalidade futuramente,
remover apenas esse helper mediante decisão do proprietário; não há serviço.

## Roteiro Moto G06 (histórico de teste)

1. Baixar APK e `.sha256` da release; conferir com `sha256sum -c` no diretório
   dos assets. Instalar sobre 0.5.4 pelo gerenciador Android, ou opcionalmente
   `adb install -r artifacts/myct-5d-a-0.5.5-debug.apk` com ADB autorizado.
2. Após instalar o helper manualmente no Fedora, criar fixture não sensível:
   `(set -C; printf abc > "$HOME/Downloads/myct-5d-prova.txt")` (usar nome novo).
3. Conectar SSH como de costume, abrir Arquivos → Downloads → fixture →
   Verificar arquivo. Esperado: 3 bytes, text/plain e
   `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
4. Copiar o digest, colar no campo: CORRESPONDE; alterar um dígito hexadecimal:
   DIVERGENTE; limpar: NÃO INFORMADO. Testar nome com espaços/acentos.
5. Selecionar também um arquivo HTTP e um arquivo regular de torrent concluído,
   usando os caminhos do SFTP. Não mover/renomear nem interromper seeding.
6. Com arquivo grande existente, observar progresso, cancelar e conferir terminal
   utilizável. Repetir manualmente e sair do app; deve informar cancelamento.
7. Em fixture própria, remover permissão ou retirar arquivo após listagem e antes
   da inspeção; conferir erro e uso do terminal/SFTP. Restaurar permissão apenas
   na fixture. Testar desconexão de rede separadamente e reconectar manualmente.
8. Registrar versão, resultados e problemas. Não considerar esta candidata aceita
   fisicamente até o proprietário confirmar inspeção/comparação real no Moto.

Retomada: MYCT-5D-A aceita fisicamente; PR #19 autorizado para merge à `main`. 5D-B, issue #9 e automações #4 continuam adiadas.
