# My-CT — páginas de apresentação e privacidade (OAuth)

Este branch é reservado à publicação das duas páginas informativas do My-CT Archive via GitHub Pages.

- `index.html`: página inicial do aplicativo para tela OAuth Google.
- `privacy.html`: política de privacidade da integração Google Drive.
- `.nojekyll`: publicação estática, sem processador de Markdown.

**Publicação exige habilitação manual no GitHub:** Settings → Pages → Deploy from a branch → `gh-pages` → `/(root)` → Save. Não publicar a pasta `docs` da `main`, pois ela contém documentação operacional do servidor. Não colocar segredos, dados de login, tokens ou arquivos pessoais nas páginas.

URL esperada **somente após publicação real**: `https://samuel-francisco-as.github.io/My-CT/` e `https://samuel-francisco-as.github.io/My-CT/privacy.html`. Conferir os endereços pelo botão **Visit site** no GitHub, pois podem variar conforme configurações de domínio.

A URL do site e a política devem ser do **mesmo domínio**, estar acessíveis publicamente e descrever corretamente o uso da API pelo Rclone. A aceitação do Google OAuth e eventuais solicitações de verificação não são garantidas pela simples existência destas páginas.

Arquivos do aplicativo, scripts do Fedora e documentação técnica permanecem na branch `main` privada, separados da árvore de publicação.
