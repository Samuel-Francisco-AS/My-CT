# Desenvolvimento e testes

Este é o ponto de entrada para quem vai modificar o My-CT. Antes de enviar código, leia [CONTRIBUTING.md](../CONTRIBUTING.md), [SECURITY.md](../SECURITY.md), [AGENTS.md](../AGENTS.md) e o [estado vigente](STATUS.md).

## Estrutura do repositório

| Diretório | Conteúdo |
| --- | --- |
| `android/app/` | Cliente Kotlin/Compose, IDE Sora, modelos e testes JVM/instrumentados |
| `android/terminal/` | Implementação terminal com upstream Termux rastreado e patches |
| `server/` | Core C++20, libpq, migração inicial, testes Python |
| `ops/` | Scripts/CLIs, unidade systemd, SETUP e testes |
| `docs/` | Guias atuais, decisões, evidências e relatórios históricos |

## Ambiente e compilação

- O cliente usa Gradle Wrapper; consulte `android/app/build.gradle.kts` para a versão Android, SDK/NDK/CMake e dependências **efetivas**. A documentação da UX2 registra compilação com JDK 21.
- O servidor usa `server/Makefile` e C++20. O Core depende de `pg_config`/`libpq-devel` para compilar, além das ferramentas padrão.
- O Fedora real, banco, keyring, SSH, serviços e arquivos pessoais **não** são ambiente descartável de teste. Use fixtures e instâncias isoladas.

Comandos exemplares, sem instalação de pacotes:

```bash
# A partir da raiz: integridade do Termux incorporado
python3 android/terminal/upstream/verify.py

# Testes JVM e build Android (precisa de SDK/JDK configurados)
(cd android && ./gradlew :app:testDebugUnitTest)
(cd android && ./gradlew :app:assembleDebug)

# Testes C++/Python locais do servidor; requer ferramentas de build/libpq
(cd server && make test)
```

Testes em `ops/tests/` e `ops/setup/tests/` podem requerer fixtures, bibliotecas ou isolamento próprios. Consulte seus casos antes de executar qualquer ação. **Não** trate `make test`, `assembleDebug` ou um mock como aceite físico Fedora/Moto.

## Como preparar uma mudança

1. Identifique um problema real; discuta mudanças de UX, segurança, exclusão, permissões e arquitetura antes de implementar.
2. Faça um diff pequeno e escolha testes proporcionais. Evite bibliotecas novas sem justificativa de consumo, manutenção e licença.
3. Não altere serviços, firewall, SELinux, SSH, bancos, backups ou arquivos de produção sem autorização específica.
4. Documente resultado, testes executados versus não executados, regressões, limitações e forma de reversão.
5. Atualize a documentação **atual** ([STATUS.md](STATUS.md), guias de módulo) quando o comportamento mudar. Registros históricos devem permanecer preservados, com novo complemento ao invés de reescrita retrospectiva.

## Licenças e proveniência

O código original do My-CT usa Apache-2.0. Fontes Termux/Sora/TM4E e outras obras incorporadas têm termos próprios: [LICENSING.md](LICENSING.md), [IDE-LICENSES.md](../android/IDE-LICENSES.md). Não remova créditos, arquivos de fontes ou textos incluídos no APK. Contribuições seguem os termos em [CONTRIBUTING.md](../CONTRIBUTING.md).

## Segurança e relato

Para vulnerabilidades não divulgadas, utilize o processo descrito em [SECURITY.md](../SECURITY.md). Uma alteração de segurança não é automaticamente autorizada só porque o risco foi identificado. O exemplo da [issue #65](https://github.com/Samuel-Francisco-AS/My-CT/issues/65) permanece documentado como risco aceito pelo mantenedor.
