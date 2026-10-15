# MYCT-3A — Core de saúde (primeiro recorte)

Código **C++20/Linux** leve, sem pacotes externos. Não instala serviços, não abre portas LAN, não altera SSH e não acessa o PostgreSQL. Retorna métricas do **host onde o processo roda** (não mocks): CPU amostrada por 120 ms em `/proc/stat`, RAM de `/proc/meminfo`, espaço livre de `/` via `statvfs`, uptime **do Core**.

## No Fedora (sem privilégios)

```sh
cd ~/Projetos/My-CT/server
make
./myct-core --once
./myct-core
```

Em outra sessão SSH no mesmo Fedora:

```sh
curl --fail --silent http://127.0.0.1:8765/v1/health
ss -lnt | grep ':8765'  # deve mostrar apenas 127.0.0.1
```

Parar o Core com Ctrl-C. Para testes rápidos: `make test`. Porta alternativa: `./myct-core --port 18765`. Nenhum `sudo` é necessário; o binário permanece executado manualmente e **não inicia no boot**.

## Restrições e estado

- O serviço escuta **somente** `127.0.0.1`, não aceita comandos, não tem endpoint de escrita. Não substituir por `0.0.0.0`.
- No Moto G06 o endpoint **ainda não está conectado**; futuramente acessar por túnel SSH autenticado e host verificado, aproveitando a sessão existente.
- **PostgreSQL não foi instalado nem integrado.** Após validar a prova real no Fedora, realizar MYCT-3B com banco local mínimo; depois MYCT-3C (Android). Nunca declarar MYCT-3 aceita antes da integração real.
- Medidas de CPU refletem uma janela breve, não um monitor contínuo; memória disponível é `MemAvailable`, e espaço livre é o disponível ao usuário não privilegiado (`f_bavail`). Sem polling automático.

Este primeiro recorte é intencionalmente pequeno para que o servidor já produza dados reais antes de introduzir dependências e alterações operacionais.
