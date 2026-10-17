# 版本与构建：以后在哪改

## 一句话

**日常只改一个文件：`gradle.properties`。**

`build.gradle` 从它读版本，`META-INF/neoforge.mods.toml` 从它读版本和依赖区间，所以改一处，全部生效。

---

## 1. 想改什么 → 改哪里

| 想改 | 改 `gradle.properties` 的 | 自动反映到 | 还要连带做什么 |
| --- | --- | --- | --- |
| **Mod 版本号**（最常用） | `mod_version` | jar 名 `howtogo-<版本>.jar`、`mods.toml` 的 `version`、游戏内 mod 列表 | 无 |
| group / Maven 坐标 | `maven_group` | 发布时的 groupId | 不影响 Java 包名（改包名是另一件事，见 §5） |
| jar 名前缀 | `archives_name` | `howtogo-<版本>.jar` | 无 |
| 目标 Minecraft | `minecraft_version` | moddev 拉取的 MC 工件 | 见 §4；并且 `minecraft_range` 要一起改 |
| 目标 NeoForge | `neoforge_version` | moddev 拉取的 NeoForge 工件 | 见 §4；并且 `neoforge_range` 要一起改 |
| 依赖版本区间 | `neoforge_range` / `minecraft_range` | `mods.toml` 的 `versionRange` | 跨版本时与上两行同步 |

`gradle.properties` 当前内容（改哪行一目了然）：

```properties
mod_version=0.2.1
maven_group=bili.dongsz.howtogo
archives_name=howtogo

minecraft_version=1.21.1
neoforge_version=21.1.256

neoforge_range=[21.1.0,21.2)
minecraft_range=[1.21.1,1.21.2)
```

---

## 2. 最常见的操作：发一个新版

1. 改 `gradle.properties`：`mod_version=0.2.2`
2. 构建：`gradle build`（IDEA 里也是跑 `build` 任务）
3. 产物：`build/libs/howtogo-0.2.2.jar`

构建的第一行会打印实际生效的版本，**对不上就是有问题**：

```
HowToGo 0.2.2 | group bili.dongsz.howtogo | NeoForge 21.1.256 | Minecraft 1.21.1
```

验证 jar 里写对了没有：

```powershell
# 看 jar 内的 mods.toml（版本 / 中文名 / 依赖区间）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead("build\libs\howtogo-0.2.2.jar")
$e = $z.GetEntry('META-INF/neoforge.mods.toml')
$sr = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
$sr.ReadToEnd(); $sr.Close(); $z.Dispose()
```

---

## 3. 谁在读哪些值（改错了会怎样）

| 值 | 读它的地方 | 改错的表现 |
| --- | --- | --- |
| `mod_version` | `build.gradle`（jar 名）、`mods.toml` 的 `${mod_version}` | 游戏内 mod 列表版本不对；依赖本 mod 的附属模组版本检查失败 |
| `neoforge_range` / `minecraft_range` | `mods.toml` 的 `versionRange` | 区间的上界写错最危险：会让玩家把 mod 装在从未测试过的版本上，然后把崩渍当 bug 报回来 |
| `minecraft_version` / `neoforge_version` | moddev 插件 | 拉到错误版本的 MC/NeoForge 工件，编译报一堆找不到符号 |
| `maven_group` | 发布坐标 | 无（本地构建不影响） |
| `archives_name` | jar 名 | 无（除非脚本/文档引用了固定文件名） |

`build.gradle` 里那三行 `version = property('mod_version')` 之类**不要改成写死的字符串**——它现在直接读本项目的 `gradle.properties` 文件，就是为了让外部同名属性（上层目录的 `gradle.properties`、`-P` 参数）无法悄悄覆盖版本。

---

## 4. 跨 Minecraft 版本升级（例：1.21.1 → 1.21.2）

按顺序做完这几件事，少一件都会在编译或启动时炸：

1. **`gradle.properties`**：`minecraft_version`、`neoforge_version`、`minecraft_range`、`neoforge_range`
2. **`libs/`**：两个 jar 换成对应 MC 版本
   - `xaeroworldmap-neoforge-<MC>-<ver>.jar`
   - `mcphone-<MC>-neoforge.jar`
   （`libs/` 被 `.gitignore` 排除，每个 clone 自己放）
3. **`build.gradle` 的 Xaero pin**：`compileOnly "maven.modrinth:NcUtCpym:<versionId>"` 要和 `libs/` 里那份同代——它是编译期看到的 Xaero API，和 libs 里那份不一致就会出现 `class_243` 之类的映射错乱
4. **`run/mods/`**：测试用的 Create / MTR / MTR Map Overlay / Xaero 小地图全部换成同一 MC 版本
5. **`java.toolchain.languageVersion`**：MC 要求更高的 Java 时改（当前 21）
6. **moddev 插件版本**：`id 'net.neoforged.moddev' version '...'`，新版 NeoForge 常常要求新版插件
7. **代码本身**：Minecraft/NeoForge 的 API 变更——这才是真正的工作量，前六项只是配置

---

## 5. 改 Java 包名（`bili.dongsz.howtogo`）

和版本号无关，改动面大：

- 源码目录 `src/*/java/bili/dongsz/howtogo/` 要跟着重命名
- 所有 `package` / `import` 语句
- `META-INF/services/*` 里的全限定类名（MCphone 那个文件）
- `mods.toml` 的 `modId` 若也改，`data/howtogo/`、`assets/howtogo/`、`config/howtogo/` 都要同步
- **`maven_group` 只是发布坐标，改它不会动包名**

除非必要，不建议在同一个提交里既改版本又改包名——出了问题分不清是哪一项引起的。

---

## 6. 不要改的地方

| 别做 | 原因 |
| --- | --- |
| 在 `build.gradle` 里写 `version = '1.0.0'` | 版本从 `gradle.properties` 读；写死会制造两个真源，且下次构建又被覆盖 |
| 在 `mods.toml` 里把 `${mod_version}` 改成字面量 | 那样版本要改两处，迟早不同步 |
| 把 `libs/*.jar` 提交进仓库 | 第三方 jar 不随本仓库分发（`.gitignore` 已排除），也避免版权问题 |
| 用 `-Pmod_version=x` 临时改版本 | 现在的实现会忽略它（故意的：版本要可复现）。确需临时覆盖请改文件 |

---

## 7. 构建前置条件（换机器时）

1. `libs/` 放好两个 jar（缺了会报 `com.november.mcphone` / Xaero API 找不到）
2. Gradle 9.8.0 可用（wrapper 直连 `services.gradle.org` 失败时可用镜像）
3. `maven.neoforged.net` 可解析——若 DNS 被污染，见 `_local-mvn/init.gradle` 那套本地仓库兜底（临时手段，网络正常后即可删除）
