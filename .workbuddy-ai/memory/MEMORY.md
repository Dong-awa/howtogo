# HowToGo 项目长期记忆

## 仓库与分支约定

- 主仓库：`Dong-awa/howtogo`（非 fork），默认分支 `1.21.1-neoforge`。
- 分支命名：主仓库用 `<mcver>-<loader>`（如 `1.20.1-fabric`、`1.20.1-forge`、`1.21.1-neoforge`）；
  贡献者 fork 里用 `<loader>-<mcver>`（如 `fabric-1.20.1`、`forge-1.20.1`）。两者顺序相反，别搞混。
- 多版本布局：共享代码放 `src/common/java`，各加载器/版本专有代码放 `src/main/java`；
  本地用 `.worktrees/` 下的 git worktree 承载各版本分支（已被 .gitignore 忽略）。
- 1.21.x 用 Java 21，26.x 用 Java 25（见 `gradle.properties` 的 `javaToolchainVersion`）。

## 已知环境坑

- 本机 `git ls-remote` / `git fetch` 走 https 会报 schannel
  `CRYPT_E_NO_REVOCATION_CHECK (0x80092012)`。远端查询改用 `gh api` 更稳。
- 本机 `gh` 已登录 `mc506lw`，token 具备 `repo` / `workflow` 权限。
- Git Bash 下 `gh api --jq` 里的 `//`（jq 备选运算符）会被路径转换弄坏，改用 `+` 拼接或避开。
