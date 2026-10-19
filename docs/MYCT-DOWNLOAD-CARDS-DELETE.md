# MYCT Downloads — cards compactos e excluir dados pelo card

Candidata: **0.7.9-download-cards-rc1**, code 38, mesmo pacote dev.myct e assinatura anterior.

Branch: myct-download-cards-cleanup (empilhada sobre MYCT Media PR #41, sem merge).

## Mudanças

### Torrents

- Os cards ficam **recolhidos por padrão**, mostrando nome, estado e barra/resumo de progresso.
- Botões rápidos: Detalhes/Recolher, Pausar/Retomar e **Excluir arquivos…**.
- Quando expandidos, mostram velocidades, peers, acumulados, ratio, pasta, hash e botões **Remover da lista…** (preserva arquivos) / Ver em Arquivos.
- Ordenação Android por ID Transmission decrescente: torrents mais novos em cima. Mesmo nas atualizações de polling a lista permanece estável pelo hash (chave LazyColumn).
- **Excluir arquivos…** abre diálogo de confirmação irreversível, com aviso de seeding. Invoca novo comando `myct-torrent delete-data HASH`.
- O backend confirma que é um torrent gerenciado pelo My-CT, recusa symlinks, arquivos especiais e dados com `nlink>1` (hardlinks externos). Também verifica referências registradas no estado Media, sob lock, antes do RPC Transmission `torrent_remove(delete_local_data=true)`.
- Quando houver links Jellyfin, deve-se primeiro removê-los em **Mídia → Armazenamento**, preservando os originais até o usuário decidir como proceder.
- O botão já existente Remover da lista preserva os dados e seeding cessa, sem mudança de semântica.

### HTTP/HTTPS

- Entrada no histórico de download terminado/cancelado/falho/interrompido ganha **Excluir arquivo e histórico…**, com confirmação.
- Download ainda ativo mantém Cancelar; para eliminar o parcial, deve-se cancelar **antes** de excluir.
- Novo comando CLI C++ `myct-download delete UUID` verifica ID, estados terminais, serviço systemd inativo, diretórios sem links simbólicos e nome de arquivo válido.
- Remove somente o payload do download (arquivo final ou `.part`) e seu registro de histórico. Preserva arquivos adicionais no mesmo diretório (não usa `rm -rf` no diretório de payload).
- Para não prejudicar uma biblioteca externa ou falsear espaço livre, recusa arquivos com mais de um hardlink.

## Hotfixes herdados (evitar regressão do PR #34)

O PR #34 `fix/torrent-dns-trackers` permaneceu draft sem merge e **não estava presente na main** quando a presente candidata empilhada nasceu. Uma atualização via `install` do helper desse worktree sobrescreveu o binário corrigido instalado no Fedora com uma versão antiga; um link magnet voltou a mostrar incorretamente `Transmission indisponível` quando um tracker falhou no DNS.

Nesta branch, os commits `8c193a2` e `642b69c` consolidaram as correções e regressões do PR #34, em conjunto com a nova ação `delete-data`:
- tolerar falha DNS de tracker individual removendo esse tracker da URI encaminhada ao daemon;
- rejeitar endereços IP literais privados, filtrar resultados DNS não públicos;
- aceitar separadores vazios repetidos `&&` e preservar `xt=urn:btih:` literal ao editar a URI para Transmission;
- diagnosticar mensagens/ códigos do RPC sem vazar URI magnet.

**Processo obrigatório antes de distribuir helpers:** conferir que nenhuma correção pendente em branch/PR anterior se perdeu; executar testes de regressão `test_torrent.py` sobre a branch exata a instalar; nunca comparar só o versionCode Android quando o helper Python também muda.

## Instalação / testes antes de alterar Fedora

Criar worktree separado:

    cd ~/Projetos/My-CT
    git fetch origin
    git worktree add --detach ../My-CT-download-cards origin/myct-download-cards-cleanup
    cd ../My-CT-download-cards

Executar testes do adaptador Transmission em ambiente temporário (RPC falso):

    python3 -m py_compile ops/myct-torrent
    python3 -m unittest discover -s ops/tests -p 'test_torrent.py' -v

Compilar C++ e rodar validação sem download real (unittest test_download.py inclui integração já existente com systemd e HTTP local de teste; não executa exclusão de dados reais):

    make -C server myct-download
    python3 -m unittest discover -s server/tests -p test_download.py -v

Android:

    cd android
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --console=plain

Os testes do servidor HTTP criam dados em HOME temporário, mas alguns testes antigos iniciam unidades transitórias de usuário e HTTP local; evitar rodá-los enquanto outro teste de download estiver em curso. Eles não apagam downloads reais.

Após gates PASS, usuário confirma versão/code/certificado SHA-256 do APK e executa instalação manual dos dois helpers atualizados:

    install -Dm0700 ops/myct-torrent ~/.local/bin/myct-torrent
    install -Dm0700 server/myct-download ~/.local/bin/myct-download

Para HTTP, aguardar nenhum download ativo antes de substituir o helper. Nunca sobrescrever o binário em execução como teste de simultaneidade.

**Aceite físico inicial sem exclusões reais:**

1. Confirmar que os torrents mais novos aparecem primeiro (ID maior = mais recente na sessão/histórico do Transmission).
2. Cards recolhidos, Detalhes abre todos os campos anteriores; Pausar/Retomar disponíveis.
3. Excluir arquivos… abre confirmação clara; **Cancelar**, sem tocar em excluir.
4. Remover da lista… continua avisando que dados serão preservados; **Cancelar**.
5. HTTP: arquivos concluídos/cancelados mostram Excluir arquivo e histórico…; arquivos ativos só exibem Cancelar. Abrir confirmação e **Cancelar**.
6. Qualquer tentativa real de excluir torrent ou download deve ser testada **somente após autorização separada**, preferindo payload descartável e verificação de ausência de links na mídia.

Gates de remoção definitiva ainda pendentes. Não fazer merge nem release sem aceite. Não modificar serviços, mídia ou torrents reais durante testes.
