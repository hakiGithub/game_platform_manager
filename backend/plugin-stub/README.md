# plugin-stub · 部署扩展步骤框架的验收资产

> design.md §3.3 组 K、§7.5 T-01/T-02；PRD §5.1.1 验收资产行、§8.6、FR-24；AC-25 / AC-26。
>
> **这不是产品模块。** 它存在的唯一理由是：PRD §11.1 承载表第一行那 19 条框架类 AC
> 需要一个「除 dnf-tw 之外还能声明扩展的东西」来承载，缺它后续每一张票都无法实测（MERC-14）。
> 它不含任何游戏语义，不进产品发布物（BR-15 ②），与 `plugin-dnf-tw` 任何时候不得混放（AC-26 ②③）。

---

## 目录里有什么

| 路径 | 是什么 | 会被构建吗 |
| --- | --- | --- |
| `src/main/java/com/gameplatform/plugin/stub/` | 桩插件本体：`StubPlugin` + `StubExtension`，只实现 `getDeployVersions` | 是（仅在本 profile 打开时） |
| `src/main/resources/plugin.properties` | PF4J 插件元数据（`plugin.gameCode=stub`） | 是（打进 jar） |
| `src/test/java/…/StubPluginLoadTest.java` | **PF4J 真实加载**桩 jar 后读目录（≥2 条目、含默认条目、PATCH/SCRIPT 混排） | 是 |
| `acceptance/games/stub.yml` | 桩游戏元数据（含 `${PLATFORM_IMAGE_TAG}` 占位模板与保留变量） | **否** —— 投放到外置 `./games` 才生效 |
| `acceptance/fixtures/patch/` | 受控补丁包 `stub-version-marker.zip`（496 B，字节级确定性构建脚本同目录） | 否 |
| `acceptance/fixtures/scripts/` | 受控脚本夹具 `stub-nonzero.sh`（固定 `exit 3`） | 否 |
| `acceptance/javaprobe/` | B-01 类型见证正例/反例 + 可复跑命令 | 否（不参与构建） |

## 怎么构建、怎么跑

`plugin-stub` **不在** `backend/pom.xml` 的默认 `<modules>` 里，只在 `acceptance-assets` profile 里
⇒ 日常的 `mvn clean compile|package`、`scripts/start-all.sh`、`scripts/rebuild-restart-all.ps1`
都不会构建它，产品发布物因此不含它（AC-26 ②）。

```bash
cd backend

# 构建 + 跑加载用例（-am 必带：桩依赖 reactor 里的 game-platform-plugin/-api）
mvn -Pacceptance-assets -pl plugin-stub -am test

# 只要 jar
mvn -Pacceptance-assets -pl plugin-stub -am package -DskipTests
# 产物：backend/plugin-stub/target/plugin-stub-1.0.0-SNAPSHOT.jar
```

## 夹具投放（验收期，一次性）

1. **桩游戏元数据**：复制 `acceptance/games/stub.yml` 到主应用工作目录下的 `./games/`
   （`game-platform.metadata.external-dir` 默认值，见 `backend/core/src/main/resources/application.yml`）。
   既有扫描器会把它落库；**不要**放进 `backend/core/src/main/resources/games/`（那是 core resources，属发布物）。
2. **受控来源**：把 `acceptance/fixtures/` 起成一个验收期静态源，桩声明里的
   `http://127.0.0.1:8099/...` 指的就是它：
   ```bash
   cd backend/plugin-stub/acceptance/fixtures && python -m http.server 8099
   ```
3. **插件本体**：把 `plugin-stub-*.jar` 放进 `plugins/`，或经
   `POST /api/pf4j/plugins/load?jarName=…` 热加载（同 `scripts/deploy-plugin.sh` 的加载臂）。

## 夹具取值为什么长这样

| 取值 | 为什么 |
| --- | --- |
| 来源指向 `127.0.0.1:8099` | 回环地址 = 一眼可辨的夹具来源，不会被误读成某个真实补丁站（N-12 / BR-15 ①）。夹具取值只服务框架验证，**不得**写成任何游戏的真实资料 |
| 两个 sha256 写死在 `StubExtension` | PRD §8.2/§8.3 的「声明了即校验」分支要有正例。`StubPluginLoadTest#fixtureChecksumsMatchDeclarations` 每次构建都核对摘要与包体一致，脱钩即构建失败，不会留到部署期被误判成实现缺陷 |
| 默认条目也带一条脚本（哨兵） | design.md §16.2：默认条目带步骤 = 合法但**永不执行**。脚本只 echo 一行标记，验收按「该标记不出现在部署日志」核对默认版本路径没有误入扩展阶段 |
| 有一条 PATCH 不写 `label` | ui-spec §6.2 的缺省回退判据（界面回退成「补丁替换 〈序号〉」，不把 `PATCH` 字面量投到界面上）需要这个形状 |
| 有一条非致命 `exit 3` 的脚本 + 一条打 stdout/stderr 的脚本 | V-22 的两个判据块（机械：`exitCode==3`；文本：stdout/stderr 各一行标记）与 AC-10（非致命继续）共用 |
| `getDeployVersions` 不按 deployType 过滤 | 「集合外 deployType 带步骤即不合法」是主应用的规则 N5。桩插件替它过滤就等于把 V-27 第二判据块的反例构造手段删掉 |

## 验收后回收（AC-26 ② 的核对物）

```bash
# ① 发布物与 plugins/ 下不留桩 jar
ls plugins/ | grep -i stub ; echo "grep 退出码非 0 即通过"
find . -name "plugin-stub*.jar" -not -path "*/target/*" ; echo "无输出即通过"

# ② 外置元数据目录不留桩 yml
ls games/ | grep -i '^stub.yml$' ; echo "grep 退出码非 0 即通过"
```

## 本票（MERC-14 / stage 1）之后的已知缺口

| 缺口 | 归谁 |
| --- | --- |
| `acceptance/games/stub.yml` 只有 `docker-compose` 一节；`linuxgsm-docker` 一节要等 design.md §7.5 T-05（两类支持集合各跑一次完整带步骤部署）时才补 | stage 6 / @Tester |
| 目录四态校验（`DeployVersionCatalogService`）本票不核对，加载用例只断言声明形状 | stage 2 |
| `installSync` 的 core 实现与同主机互斥键（V-26）不在本票，桩声明的 PATCH 步骤因此还不能真跑 | stage 3 |
| 桩插件只声明静态目录，`getDeployExtensionSteps(ctx)` 动态入口的 AC-18（两实例步骤集互不串用）需要一个走动态入口的条目 | stage 4/5 |
