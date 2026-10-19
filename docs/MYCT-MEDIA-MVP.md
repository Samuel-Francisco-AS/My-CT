# My-CT Media — MVP híbrido (18/10/2026)

**Estado:** código e 6 testes locais; NÃO instalado no Fedora; timer NÃO habilitado; interface Android AINDA NÃO integrada. Esta primeira entrega funciona pelo Terminal SSH existente no My-CT. PR rascunho, sem merge.

## Contrato

- Examina apenas torrents concluídos da instância My-CT Transmission, com metadados completos e sem erro.
- Reconhece automaticamente episódios S01E02 e filmes Titulo.2024.1080p. Nomes não reconhecidos, propaganda e colisões ficam em pending.
- scan: prévia sem importar. sync: cria hardlinks sem mover, renomear ou apagar os originais. list: entradas importadas. approve: classificação manual. skip: ignora arquivo. remove: apaga somente hardlink previamente registrado e impede recriação automática.
- Os arquivos-fonte continuam no diretório de torrents; o seeding não é interrompido.
- O estado privado fica em ~/.local/share/myct/media/state.json. Não apagar enquanto estiver usando a CLI. O timer é opcional e não altera o hook existente.

## Preparação no Fedora, após revisão da branch

    cd ~/Projetos/My-CT
    git fetch origin
    git switch myct-media-hybrid-mvp
    git pull --ff-only origin myct-media-hybrid-mvp
    python3 -m unittest discover -s ops/tests -p 'test_media.py' -v
    python3 -m py_compile ops/myct-media
    # Se necessário instalar pacote acl com DNF antes do próximo passo
    mkdir -p "$HOME/Media/Series" "$HOME/Media/Filmes"
    sudo setfacl -m u:jellyfin:--x "$HOME"
    setfacl -m u:jellyfin:r-x "$HOME/Media" "$HOME/Media/Series" "$HOME/Media/Filmes"
    install -Dm0700 ops/myct-media "$HOME/.local/bin/myct-media"
    "$HOME/.local/bin/myct-media" scan

A saída JSON de scan contém added (somente uma prévia!), existing, pending e ignored. Revisar todas as propostas ANTES de executar o primeiro sync. O utilitário dará ACL de leitura ao jellyfin nos novos hardlinks e ACL de travessia nos diretórios de Media, sem expor toda a pasta Torrents.

    ~/.local/bin/myct-media sync
    ~/.local/bin/myct-media list

O Jellyfin deve usar a biblioteca Animes existente em /home/sam/Media/Series. Criar uma biblioteca Filmes separada em /home/sam/Media/Filmes. Se necessário, pedir a análise da biblioteca pelo painel Jellyfin.

## Revisão manual

Os valores ID aparecem em pending e são o hash Transmission + caminho relativo. Colocá-los ENTRE ASPAS no shell.

    myct-media approve 'HASH:episodio.mkv' series 'Nome do Anime' 1 2
    myct-media approve 'HASH:filme.mkv' movie 'Nome do Filme' 2024
    myct-media skip 'HASH:video_promocional.mp4'
    myct-media remove 'Series/Nome do Anime/Season 01/Nome do Anime S01E02.mkv'
    myct-media list

A remoção só é permitida para caminhos gerenciados pela CLI, e não afeta o original no Transmission.

**Frieren E01 anterior:** aquele hardlink foi feito manualmente e não é gerenciado pela nova CLI. Uma colisão com o episódio do torrent completo será deixada como pending. Conferir o destino e apagar APENAS o hardlink em ~/Media usando rm -i, nunca a origem nos torrents. O sync subsequente poderá criar o novo link. Não substituir episódios automaticamente.

## Ativar importação automática, SOMENTE após teste manual real

    mkdir -p "$HOME/.config/systemd/user"
    install -m0644 ops/systemd/myct-media.service "$HOME/.config/systemd/user/"
    install -m0644 ops/systemd/myct-media.timer "$HOME/.config/systemd/user/"
    systemctl --user daemon-reload
    systemctl --user enable --now myct-media.timer
    systemctl --user list-timers myct-media.timer
    journalctl --user -u myct-media.service -n 30 --no-pager

Para parar: systemctl --user disable --now myct-media.timer

O timer roda como o usuário sam a cada aproximadamente 5 minutos; não mantém daemon e não altera o hook de conclusão, o RPC, a rede ou serviços existentes. Nenhuma ativação no Fedora foi realizada nesta entrega.

## Limitações e pendências

- Interface Android: falta tela Media para ler as pendências, aprovar, ignorar e remover pelo SSH. Por enquanto o Terminal My-CT funciona como interface.
- Classificação é conservadora (sem integração com TMDB); nomes sem temporada explícita ou com vídeos múltiplos ambíguos precisam de revisão. Conferir a prévia de scan antes de habilitar timer.
- Hardlinks não podem atravessar subvolumes Btrfs; falhas ficam pendentes, nunca copiadas.
- O mesmo inode mantém os mesmos dados e ACL de arquivo da origem, mas a pasta privada do Transmission continua inacessível pelo Jellyfin.
- Testes executados em diretórios temporários: 6 unidades Python passaram; py_compile passou. Não foi executado no Fedora real, e não foi criado novo APK; validação física do Moto G06 ainda pendente.
- Após instalar, conferir: origem ainda existe, stat reporta mesmo inode, sudo -u jellyfin test -r no link novo e reprodução no Moto G06.

Próximo passo: validar a CLI no Fedora e depois integrar a tela Compose na branch apropriada, sem conflitar com PR #35 (navegação) ou PR #34 (correção torrent).
