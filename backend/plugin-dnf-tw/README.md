# plugin-dnf-tw · dnf-tw 交付载体（版本目录为**占位模板，不可上线**）

> design.md §3.3 组 J、§7.3 B-14/B-15、§9.1 上线顺序 ②；PRD FR-22 / FR-23、BR-15、§8.6、AC-23 / AC-24。
>
> 本期 OP-02 裁决：交付「框架 + 版本目录占位模板」，真实版号 / 补丁 URL / 目标路径由人类 Owner 后续提供。
> **本模块不含任何可部署的版本条目**，装载它不改变 dnf-tw 的现有行为（AC-24 ④）。

---

## 模块里有什么

| 路径 | 是什么 |
| --- | --- |
| `pom.xml` | 聚合模块（`packaging=pom`），布局参照 `plugin-l4d2`；`<description>` 带「占位模板，不可上线」 |
| `plugin-dnf-tw-core/pom.xml` | 插件 JAR 模块；`finalName = ${project.artifactId}-${plugin.version}` ⇒ 产物名 `plugin-dnf-tw-core-1.0.0.jar` |
| `…/java/…/DnfTwPlugin.java` | PF4J 插件主类（只提供构造器，无 Spring 子容器业务） |
| `…/java/…/DnfTwExtension.java` | `@Extension` 扩展点实现，`getGameCode() = "dnf_tw"`；目录走 `getDeployVersions(deployType)` |
| `…/java/…/VersionCatalogTemplate.java` | 模板的唯一读取路径：解析 → 按未填充标记过滤 → 逐字段搬运成 SDK 声明类型 |
| `…/resources/plugin.properties` | PF4J 元数据七键，`plugin.description` 带「占位模板，不可上线」 |
| `…/resources/version-catalog.yml` | **占位模板文件**：字段骨架按 PRD §8.1～§8.3 完整列出，每个待填字段值为未填充标记 `UNFILLED` |

**不建 `frontend/`**：FR-22 / AC-23 ④ 明确本模块无插件前端，ADR-0003 亦不引入 standalone 模式。

`DnfTwExtension` 只实现四个必填方法 + `getDeployVersions`；`getDeployExtensionSteps(ctx)`（§16.2 解析顺序 ① 支）保持 SDK 默认空集——dnf-tw 属纯声明型插件，步骤集由目录条目自带的 `patches ++ scripts` 承载。

---

## 本期可观察结果（缺口期状态）

```
version-catalog.yml 全部条目带 UNFILLED 标记
   → VersionCatalogTemplate 过滤 → 对外声明 0 条目
   → 主应用 DeployVersionCatalogService 得 CatalogView.state == EMPTY   ← 不是 INVALID、不抛异常
   → dnf-tw 恒处 PRD §8.4.2 的 S1
```

S1 的界面/行为侧后果（AC-24 的判据）：向导**不出现**「目标版本」选择项 · 提交载荷**不存在** `deployVersion` 键 ·
**不进入**扩展阶段、无步骤行、**无任何「不合法」提示行** · 部署结果与改造前逐项一致。

---

## 真实资料到位后怎么填（FR-23 / BR-15 ④「填充即生效」）

只改 `plugin-dnf-tw-core/src/main/resources/version-catalog.yml` 一个文件：

1. 复制模板里的条目块，把需要表达的字段值从 `UNFILLED` 换成人类 Owner 提供的真实资料；
2. **字符串字段一律加引号**（`versionId` / `displayName` / `imageTag` 与步骤里的 `label` / `url` /
   `targetPath` / `sha256` / `includePattern` / `format` / `content`）。不加引号时 YAML 1.1 按标量类型解析：
   点分数字读成小数（尾零丢失）、前导零按八进制解读、`yes`/`no`/`on`/`off` 读成布尔、冒号分隔的数字读成六十进制；
   `VersionCatalogTemplate.text()` 对这些取值**抛异常，不做 `String.valueOf` 隐式转写**——
   `versionId` 既是部署载荷的键，也是镜像 tag 的组成部分，差一个字符就是另一个版本；
3. 不需要表达的**可选**字段整行删掉——留着 `UNFILLED` 等于没填完，该条整条不生效
   （标记比对忽略大小写，`unfilled` 的小写手误同样不生效）；
4. 不改 `DnfTwExtension` / `VersionCatalogTemplate`、不改主应用 `backend/core`、不动 SDK 声明接口、
   不动 `games/dnf_tw.yml`、不加 `configInfo` 列（BR-01 / G-01 / N-01 / §3.4）；
5. 重新构建 + 热部署即生效（命令见下节）。

空目录有三种同形写法——空文件、`versions:` 整段留空、`versions: []`——都是 0 条目，
主应用据此判 `EMPTY`（不是 `INVALID`）。

**为什么「不改代码」这件事是可信的**：读取路径只有一条——解析整份模板 → 对**每个**条目扫描是否仍带
未填充标记 → 未带标记的条目逐字段搬运成 `DeployVersionDeclaration`。三个环节都不看「模板填了没填」，
也没有「未填充就返回空列表」的特判分支；本期读为空是**过滤的结果**，不是硬编码的结果。
`VersionCatalogTemplateTest` 把这条钉住：同一入口喂一份填完的模板 ⇒ 条目立即对外声明；
出厂模板 ⇒ 0 条目；骨架文件确实含条目 ⇒ 证明空来自过滤。

**校验不在本模块**：§8.1～§8.3 的必填 / 唯一 / 字符集与 N1…N5 五条由主应用唯一读者执行
（design.md §16.3），任一不合规 ⇒ **整目录**判声明不合法（禁止部分采纳）。本模块只做形状搬运，
不替主应用过滤（`position: container` 原样搬运、不按 deployType 过滤），否则会把 N4 / N5 的判定对象抹掉。

以下一律抛异常（不猜、也不静默采纳），主应用侧归 `ABSENT` 并记 warn：
未定义字段名、非布尔的开关位、`position` 落在 host/container 之外、字符串字段被解析成数字或布尔、
同一个键写两遍（SnakeYAML 后写覆盖已用 `LoaderOptions.setAllowDuplicateKeys(false)` 关闭）。

---

## 构建与热部署（design.md §9.1 上线顺序 ②）

```bash
cd backend
mvn -pl plugin-dnf-tw/plugin-dnf-tw-core -am install -DskipTests

PLUGIN_ID=plugin-dnf-tw JAR_NAME=plugin-dnf-tw-core-1.0.0.jar \
  bash scripts/deploy-plugin.sh \
  --jar backend/plugin-dnf-tw/plugin-dnf-tw-core/target/plugin-dnf-tw-core-1.0.0.jar
```

`scripts/deploy-plugin.sh` 与 `scripts/start-all.sh` **都不需要登记本插件**（核对结论，故不改脚本）：

| 核对点 | 结论 |
| --- | --- |
| `deploy-plugin.sh` 的 `FRONTEND_DIR` / `PLUGIN_MODULE` 硬编码 l4d2 | 只在**构建臂**里被读到；命中 `--jar` 即整段跳过构建臂（前端 `npm run build` 与 `mvn install` 都在 else 臂），本插件无前端、JAR 由上面第一条命令自打 ⇒ 绕开两处硬编码 |
| 卸载 / 覆盖 / 重复 jar 清理 / 加载 | 全部按 `PLUGIN_ID` + `JAR_NAME` 走，二者可 env 覆盖；卸载传 `purgeTasks=false` 保留任务中心历史 |
| `start-all.sh` 的插件臂 | 只代打并复制 l4d2 的 jar；`plugins-dir: ./plugins` 是 PF4J 启动扫描目录，落在 `plugins/` 下的 jar 由启动加载器装载 ⇒ 已热部署过的本插件在下次重启后照常加载，无需脚本改动 |
| 后端未运行时的分支 | `deploy-plugin.sh` 会直接落盘到 `plugins/` 并退出，下次 `start-all.sh` 自动加载 |

需要改脚本时的正确做法是回报，不顺手参数化 l4d2 在用的脚本（本期范围外）。

---

## 与验收资产 `plugin-stub` 的边界

`plugin-stub` 是**验收资产**（只在 `acceptance-assets` profile 里构建、不进发布物），本模块是**产品模块**
（挂在 `backend/pom.xml` 默认 `<modules>`）。二者任何时候不得混放：桩插件与夹具不出现在本模块的
版本目录、不进 `plugins/`、不进产品发布物（AC-26 ②③、BR-15 ②）。
