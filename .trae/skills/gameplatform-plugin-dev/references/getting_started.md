# 快速开始与项目结构

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

## 1. 版本与维护约定

| 项 | 规则 |
|---|---|
| 现行版本 | 本 SKILL 目录始终为最新版，`references/changelog.md` 维护版本号与"对齐主应用版本/commit"标注 |
| 物理快照 | 每逢**主版本**变更（插件 API 破坏性改动或重大重构），将旧版另存归档保留 |
| Changelog | `references/changelog.md` 维护；主应用新增/变更 API 时追加条目并升版本号 |
| 升版规则 | minor 变更只更 changelog 与版本号；major 变更才产出新快照文件 |
| 权威来源 | 接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记 |

---

## 2. 快速开始：最小插件

最小可运行插件只需三件物：`plugin.properties`、`{GameCode}Plugin`（PF4J 入口）、`{GameCode}Extension`（扩展点实现）。

```java
// 1) PF4J 入口
public class MyGamePlugin extends Plugin {
    public MyGamePlugin(PluginWrapper wrapper) { super(wrapper); }
    @Override public void start() { log.info("MyGame 启动"); }
    @Override public void stop() { log.info("MyGame 停止"); }
}

// 2) 扩展点实现
@Extension
public class MyGameExtension implements GameEnhancementExtension {
    @Override public String getGameCode() { return "mygame"; }
    @Override public String getGameName() { return "我的游戏"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public String getDescription() { return "示例插件"; }
    @Override public String getBasePackage() { return "com.gameplatform.plugin.mygame"; }
}
```

```properties
# 3) src/main/resources/plugin.properties
plugin.id=plugin-mygame
plugin.class=com.gameplatform.plugin.mygame.MyGamePlugin
plugin.version=1.0.0
plugin.gameCode=mygame
plugin.basePackage=com.gameplatform.plugin.mygame
```

打包为单个 JAR 放入主应用 `plugins/` 目录，启动主应用即被加载。验收标准见 `references/checklist.md`。

---

## 3. 项目结构与命名规范

### 3.1 模块布局

```
plugin-{gameCode}/
├── pom.xml
├── src/main/java/com/gameplatform/plugin/{gameCode}/
│   ├── {GameCode}Plugin.java          ← PF4J Plugin 入口类
│   ├── {GameCode}Extension.java        ← GameEnhancementExtension 实现
│   ├── controller/                     ← REST 控制器
│   ├── service/                        ← 业务逻辑
│   ├── extension/                      ← @ExtensionModel 资源类 + Spec
│   └── util/
├── src/main/resources/
│   ├── plugin.properties               ← 必须
│   └── ui/                             ← 前端资源（纯后端插件可无）
│       ├── index.html
│       └── assets/
└── src/test/java/
```

> **纯后端插件**：可省略 `ui/` 目录，`getFrontendEntry()` 返回默认值即可，主应用不会为其渲染前端入口。

### 3.2 命名约定

| 元素 | 规范 | 示例 |
|---|---|---|
| Maven artifactId | `plugin-{gameCode}` | `plugin-l4d2` |
| Java 包名 | `com.gameplatform.plugin.{gameCode}` | `com.gameplatform.plugin.l4d2` |
| Plugin 入口类 | `{GameCode}Plugin` | `L4D2Plugin` |
| Extension 实现类 | `{GameCode}Extension` | `L4D2Extension` |
| 扩展资源类 | `{GameCode}{Purpose}Resource` | `AdminResource` |
| 业务 Spec 类 | `{GameCode}{Purpose}Spec` | `AdminSpec` |
| API 路径前缀 | `/api/plugin/{gameCode}/` | `/api/plugin/l4d2/` |

---

## 4. plugin.properties 配置

每个插件 JAR 根目录必须包含 `plugin.properties`：

```properties
# === 必填 ===
plugin.id=plugin-l4d2
plugin.class=com.gameplatform.plugin.l4d2.L4D2Plugin
plugin.version=1.0.0

# === 可选（可由 Extension 方法替代） ===
plugin.gameCode=l4d2
plugin.basePackage=com.gameplatform.plugin.l4d2

# === 自定义属性（通过 PluginContext.getCustomProperties() 获取） ===
rcon.defaultPort=27015
rcon.timeout=5000
```

**配置优先级**：`plugin.basePackage` > `extension.getBasePackage()`。

---

## 5. Plugin 入口类

```java
public class MyGamePlugin extends Plugin {
    public MyGamePlugin(PluginWrapper wrapper) { super(wrapper); }
    @Override public void start()  { log.info("MyGame 启动"); }
    @Override public void stop()   { log.info("MyGame 停止"); }
    @Override public void delete() { log.info("MyGame 卸载"); }
}
```

> Plugin 类是 PF4J 生命周期入口，业务逻辑应放在 Extension 实现中。

---

## 6. pom.xml 规范

```xml
<project>
    <parent>
        <groupId>com.gameplatform</groupId>
        <artifactId>game-platform-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>plugin-mygame</artifactId>
    <packaging>jar</packaging>
    <dependencies>
        <!-- 插件 API 模块（provided scope） -->
        <dependency>
            <groupId>com.gameplatform</groupId>
            <artifactId>game-platform-plugin</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>
        <!-- Spring 相关依赖全部 provided -->
    </dependencies>
</project>
```

| 规则 | 说明 |
|---|---|
| 依赖 scope | 所有框架级依赖必须 `provided`，避免打包进插件 JAR |
| 插件 JAR | 单个 JAR 放入主应用 `plugins/` 目录 |

> 插件模块 pom.xml 必须显式声明 `maven-compiler-plugin` 并设置 `<encoding>UTF-8</encoding>`，确保中文编译正确。

### 6.1 独立仓库构建（可选路径，v3.6.0）

插件也可以放在平台仓库之外的独立仓库，用无 parent 的独立 pom（参考 `examples/plugin-mygame/pom.xml` 或 `backend/plugin-template/pom.xml`）。除上表规则外，额外要求（**四个坑详见 `gotchas.md` §15**）：

1. 先在平台仓库 `backend/` 下 `mvn -pl api,plugin install -DskipTests` 安装 provided 依赖到本地仓库；
2. maven-compiler-plugin 必须加 `<parameters>true</parameters>`（否则子容器注入宿主服务报双候选 bean 二义性）；
3. 自带 `lombok`（provided）——平台父 pom 的全局 lombok 不可继承；
4. 改 pom/编译配置后 `clean package`，否则增量编译把旧 class 打进 jar。

部署外部仓库产物（jar 不在平台 `backend/plugins/` 下）：

```bash
PLUGIN_ID=plugin-{gameCode} JAR_NAME={jar文件名} \
  bash scripts/deploy-plugin.sh --skip-build --jar /path/to/plugin-{gameCode}-1.0.0.jar
```

（deploy-plugin.sh 的构建流程绑定 plugin-l4d2 模块，外部仓库必须 `--skip-build --jar` 组合。）
