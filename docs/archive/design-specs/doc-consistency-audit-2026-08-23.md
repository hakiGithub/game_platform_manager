# 代码与文档一致性审计报告

> 审计日期：2026-08-23
> 审计依据：项目根 `README.md` 文档导航中列出的 `docs/`、`*.AGENTS.md`、API 文档
> 方法：逐篇核对文档陈述，并用代码（Java 枚举 / 控制器 / ADR）交叉验证每一条结论
> 结论等级：🔴 高（会误导开发或公开契约错误）/ 🟡 中（明显过时或不准确）/ 🟢 低（链接或措辞瑕疵）

---

## 一、🔴 高优先级

### S1. `backend/AGENTS.md` 仍在教已废弃的 `features` / manifest.json 菜单机制

- **文档陈述**
  - 第 616 行：`"features", Map.of(`
  - 第 749 行：清单接口 `/api/plugin/{gameCode}/manifest`
  - 第 756 行：`读取 manifest.json 获取菜单配置`
  - 第 552 行：`manifest.json` 插件清单
- **代码事实**
  - ADR-0001（状态 Accepted，2026-08-02）决策项 3、9：`manifest.features` 字段**彻底删除**，`loadManifestFromFile` 机制删除，菜单清单由插件通过扩展点 `GameEnhancementExtension#getMenus()` 显式返回。
  - `backend/core/.../plugin/service/impl/PluginFrameworkServiceImpl.java:93` 注释：`ADR-0001: 仅从扩展点 getManifest() + getMenus() 构建，不再读 JAR 内 manifest.json`
  - 同文件 `:373-374`：`ADR-0001: 菜单清单由插件 getMenus() 提供，主应用不再硬编码任何插件菜单`
  - `GameEnhancementExtension.java:194` 确有 `default List<PluginMenuDeclaration> getMenus()`；`L4D2Extension.java:94` 已实现。
- **影响**：新开发者按此文档会在 core 里硬编码 L4D2 菜单与 feature flag，正好踩中 ADR-0001 明令禁止的反模式。
- **建议**：删除 `features` / manifest.json 驱动菜单的整段描述，改为介绍 `getMenus()` 扩展点 + `PluginMenuDeclaration` 强类型对象。

### S2. `docs/api/api-doc.md` 实例运行状态枚举错误（写成 0/1/2 三态）

- **文档陈述**：第 1063、1078、1145 行均写 `运行状态：0-已停止，1-运行中，2-异常`
- **代码事实**：`DeployAdapter.InstanceStatus` 枚举共 **8 态**（`DeployAdapter.java:58-66`）：
  `0 STOPPED / 1 RUNNING / 2 STARTING / 3 STOPPING / 4 ERROR / 5 INSTALLING / 6 UPDATING / 7 NOT_INSTALLED`
  即 `status===2` 是「启动中」，`异常` 是 `4`，文档把 2 与 4 搞反且漏掉 6 个状态。
- **影响**：这是公开 API 契约，前端 / 第三方按文档会误判实例状态。
- **建议**：改为 8 态映射表，并标注各状态含义与 ADR-0005 关联。

---

## 二、🟡 中优先级

### M1. 部署方式：文档普遍缺 `linuxgsm-docker`，且 `/api/api-doc.md` 用数字 1/2/3

- **代码事实**
  - `DeployAdapter.DeployType` 共 **4 种**（`DeployAdapter.java:21-24`）：
    `LINUX_GSM("linuxgsm")` / `DOCKER("docker")` / `DOCKER_COMPOSE("docker-compose")` / `LINUX_GSM_DOCKER("linuxgsm-docker")`
  - 白名单 `GameServiceImpl.java:50`：`Set.of("linuxgsm","docker","docker-compose","linuxgsm-docker")`
  - `native` 仅是历史别名：`DeploymentAccess.java:63-64` 将 `native` 归一为 `LINUX_GSM`，**不是真实部署类型**。
- **文档问题**
  - 根 `AGENTS.md` 技术栈 / 核心功能写「三种部署方式（LinuxGSM/Docker/Docker Compose）」——缺 LinuxGSM Docker。
  - `docs/api/api-doc.md:889`：`supportDeployType ... 1-LinuxGSM，2-Docker，3-Docker Compose`——数字编码且缺 `linuxgsm-docker`。
  - `docs/design/ui-design-spec.md:143`：部署向导只列三种。
  - `backend/AGENTS.md:322` 示例 `return DeployType.CUSTOM;`（枚举中**无** CUSTOM）；第 390-391 行 `deployTypes: [linuxgsm, native]`（`native` 非真实类型）。
  - `backend/api/.../InstanceCreateDTO.java:47`：`allowableValues={"docker","native"}`——既漏 3 种又含无效的 `native`（属代码层契约错误）。
- **建议**：全栈统一为 4 种字符串编码；删除 `CUSTOM` / `native` 示例；修正 `InstanceCreateDTO` 的 `allowableValues`。

### M2. `frontend/AGENTS.md` 状态映射错误

- **文档陈述**：第 354 行 `case 2: return '异常'`、第 469 行 `<el-option label="异常" :value="2" />`
- **代码事实**：同上 8 态，2=启动中、4=异常。
- **建议**：同步为 8 态，至少把 2 改为「启动中」并补 4=异常。

### M3. WebSocket 端点文档缺路径参数（根 `AGENTS.md` + `api-doc.md` 均错）

- **代码事实（实际 Handler 路由）**
  - `/ws/ssh/{hostId}`
  - `/ws/instance/{instanceId}/console`
  - `/ws/instance/{instanceId}/logs`
  - `/ws/docker/{hostId}/containers/{containerId}/exec`
  - `/ws/docker/{hostId}/containers/{containerId}/attach`
  - `/ws/docker/{hostId}/containers/{containerId}/logs`（支持 `/{tail}`）
- **文档问题**
  - 根 `AGENTS.md:169-172`：写成 `/ws/ssh`、`/ws/instance/console`、`/ws/instance/log`（应为 `logs` 且缺 `{instanceId}`）、`/ws/docker/{hostId}/containers/{containerId}/exec`（缺 `attach` / `logs` 两条）。
  - `docs/api/api-doc.md:3663 / 3732 / 3758`：写成 `/ws/docker/exec`、`/ws/docker/attach`、`/ws/docker/logs`，且示例用 `?hostId=1&containerId=...` **query 参数**传参，但代码从 **PATH** 取参——开发者照抄连不上。
- **建议**：按真实路由补全路径参数与缺失端点；示例改为 path 参数形式。

### M4. `2026-07-19-l4d2-server-next-port-design.md`（已归档至本目录）仍描述已删除的 standalone 双模式

- **文档陈述**：第 17、24-25、103、614、822、828、1157、1218、1743-1812 行多处描述 `plugin-l4d2-standalone` 独立 fat JAR、三模式（Wujie/Standalone/Dev）路由、standalone 专属实现类（`StandaloneInstanceQueryService` 等）。
- **代码事实**：ADR-0003（deprecate plugin-l4d2-standalone）已**物理删除** standalone 模块；前端仅保留 Wujie + dev 两种模式（根 `AGENTS.md`「运行模式」表）。grep `standalone` 在 `*.java` 仅命中测试文件名，无 standalone 模块源码。
- **建议**：该 spec 属历史设计稿，应移入 `docs/archive/` 或加「已废弃，参见 ADR-0003」横幅。

---

## 三、🟢 低优先级

### L1. 插件 UI 资源路径三套前缀并存，文档只呈现其一

- **代码事实**：存在三套前缀
  - `/api/plugin/{gameCode}/...`：`PluginFrameworkController` 的 `/plugin/{gameCode}/manifest|ui/**`
  - `/api/plugins/{...}`：`PluginResourceController` 的 `/plugins/{gameCode}/ui`、`/plugins/{pluginId}/...`
  - `/api/pf4j/plugin/{gameCode}/ui`：`PluginUtils.getPluginUiBasePath()` 返回值
  - `SecurityConfig.java:76-77` 放行 `/pf4j/plugin/*/ui/**` 与 `/pf4j/plugins/*/ui/**`
- **文档问题**：`docs/api/api-doc.md:1769 / 1775` 写 `/api/pf4j/plugins`、`/api/pf4j/plugin/{gameCode}/manifest`，而实际 REST 控制器映射在 `/api/plugins/...`、`/api/plugin/...`（无 `pf4j` 段）。文档与控制器路径不一致；且代码内 UI base path 用 `pf4j` 段而其他用 `plugin/plugins`——属代码内部不一致，文档未解释。
- **建议**：先在代码层统一前缀，再更新文档；当前至少应在文档中说明真实前缀为 `/api/plugin`、`/api/plugins`。

### L2. `docs/architecture/ARCHITECTURE.md:464` 插件开发指南仍指向 `.trae` 副本

- **事实**：此前已将权威插件开发文档迁移至 `docs/plugin-development/README.md`，`.trae/skills/...` 仅作 AI 副本。
- **建议**：改为 `docs/plugin-development/README.md`。

### L3. ADR-0001 内部引用已删除的 standalone

- **事实**：ADR-0001 决策项 8 提到「plugin-l4d2-standalone 模式同样从 getMenus() 读取菜单」，与 ADR-0003 删除 standalone 矛盾。
- **建议**：ADR-0001 加注「standalone 模式已由 ADR-0003 废弃」，或删除该项。

---

## 四、小结与下一步

| 等级 | 数量 | 条目 |
|------|------|------|
| 🔴 高 | 2 | S1 backend/AGENTS.md 教反模式菜单；S2 api-doc 实例状态枚举错误 |
| 🟡 中 | 4 | M1 部署方式缺 linuxgsm-docker；M2 前端状态映射；M3 WebSocket 路径参数；M4 standalone spec 未归档 |
| 🟢 低 | 3 | L1 UI 路径三前缀；L2 架构文档链接；L3 ADR 内部引用 |

**最危险**：S1（教开发者写 ADR-0001 禁止的代码）、S2（公开 API 契约错误）。

**待确认**：是否需要我直接动手修正这些文档？建议优先修 S1 / S2 / M1 / M2 / M3（高 + 中），L1–L3 可一并处理。
