# ADR-0005: run_status 状态词汇表统一（InstanceStatus 唯一权威）

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-13 |
| 决策者 | User (grill session, architecture review 候选 1) |
| 关联 | [ADR-0004](0004-host-lan-identification.md)（isLanHost，未来 PatchInstallService 消费方） |
| Supersedes | 无 |

## 背景（Context）

`game_instance.run_status` 列存在两套互不一致的状态码词汇，在同一列内由不同写入方混写：

- **旧词汇**（`InstanceServiceImpl` 主 CRUD 路径、`DeployService` 部署流程裸整数、`DeployRecoveryTask`/`DeployRecoveryListener`）：`2=异常`、`3=停止中`、`5=部署中`、`6=启动中`
- **新词汇**（`DeployAdapter.InstanceStatus` 枚举 0-7，由 4 个部署适配器、`InstanceSyncService` 对账策略、`DeployService` 的 start/stop 写入）：`2=STARTING`、`3=STOPPING`、`4=ERROR`、`5=INSTALLING`、`6=UPDATING`、`7=NOT_INSTALLED`

码 2 与码 6 语义直接冲突：旧写入方写 2 表示异常，新词汇读者读出 STARTING；旧写入方写 6 表示启动中，新词汇读者读出 UPDATING。显示层（`InstanceVO.getRunStatusDesc` 的旧词汇 computed getter、`InstanceServiceImpl.mapRunStatusToString`）与前端 6 份自建映射（`stores/instance.js` 数字版、`instance/index.vue`、`instance/detail.vue`、`dashboard/index.vue`、`plugin/index.vue`、`PluginTab.vue`）各自维护一套文本/颜色，已实际漂移（如后端把码 2 映射为 "error" 而枚举说 2=STARTING）。

## 决策（Decision）

### 决策 1：InstanceStatus 枚举是 run_status 列的唯一权威语义

`DeployAdapter.InstanceStatus`（0-7）成为状态码的唯一权威词汇表，其语义固定为：

| 码 | 枚举 | wireKey | 中文描述 |
|----|------|---------|---------|
| 0 | STOPPED | stopped | 已停止 |
| 1 | RUNNING | running | 运行中 |
| 2 | STARTING | starting | 启动中 |
| 3 | STOPPING | stopping | 停止中 |
| 4 | ERROR | error | 异常 |
| 5 | INSTALLING | installing | 安装中 |
| 6 | UPDATING | updating | 更新中 |
| 7 | NOT_INSTALLED | not_installed | 未安装 |

枚举新增 `wireKey` 字段承载英文键；`description` 是中文文本的唯一来源。

### 决策 2：所有写入方改用枚举常量

旧词汇写入方全部改为引用 `InstanceStatus` 枚举常量（而非裸整数）：错误标记统一 `ERROR`（码 4）、启动中统一 `STARTING`（码 2）、部署开始统一 `INSTALLING`（码 5，与旧"部署中"同码同义）。`DeployService` 删除裸整数版 `updateRunStatus(int)` 私有方法，与枚举版 `updateInstanceStatus(InstanceStatus)` 合并。

### 决策 3：线上契约三字段保留、语义归一

`InstanceVO` 三字段全部保留但语义归一：`runStatus`（新词汇数字，兼容 plugin-l4d2 已构建前端）、`runStatusDesc`（枚举 description 派生，唯一文本源）、`status`（枚举 wireKey，前端过滤键）。填充点收敛到 `convertToVO`（全后端唯一生产构建点）；删除 `InstanceVO` 的旧词汇 computed getter 与 `mapRunStatusToString`。

### 决策 4：不做数据迁移

项目处于开发期，无存量数据，不编写数据修正迁移。

### 决策 5：前端删除全部自建状态映射

前端 6 份文本/颜色映射删除；视图文本直接消费 `runStatusDesc`；新增唯一共享工具（`statusType(status)` 颜色映射 + `ACTIVE_STATUSES` 活跃状态集合）。task 状态与容器状态是独立领域词汇，不在本决策范围。

## 后果（Consequences）

### 正面

- 状态语义只写一处（枚举），写入方/显示层/前端不再可能各说各话
- 文本权威唯一：改文案只动枚举 description 一处
- `convertToVO` 单点填充，插件路径（`InstanceQueryService` → `InstanceService`）自动受益
- 漂移被测试锁死：枚举派生映射有单测，前端工具单测锁定颜色/活跃集合

### 负面

- 前端多处英文键过滤需从旧键（`deploying`）迁移到新键（`installing`/`updating`）
- 码 5 的用户可见文案由"部署中"变为"安装中"（与枚举 description 一致）
- plugin-l4d2 已构建前端（`resources/ui/` 产物）若内部自建旧语义数字映射，码 2/6 在其页面可能显示错误；本仓库无法重建该产物，属已知涟漪

## 备选方案（Alternatives）

### 备选 1：旧词汇为权威，改写 InstanceStatus 枚举

**否决理由**：枚举已被 4 个部署适配器、sync 对账策略及单测大量引用，改写波及面大于修正旧写入方；且旧词汇状态空间少三态（installing/updating/not_installed），未来补丁安装（ADR-0004）等场景需要区分。

### 备选 2：只统一显示层，不动写入方

**否决理由**：码 2 在 DB 中同时可能是 error 也可能是 starting，显示层无法区分；漂移只是变得一致，语义冲突仍在。

### 备选 3：码 5 的 wireKey 沿用 "deploying"

**否决理由**：wireKey 与 description 应同源于枚举，另设别名等于再造第二个文本权威；"部署中"文案可随需求统一改为"安装中"。

## 相关文档

- [ADR 索引](README.md)
- [术语表 InstanceStatus](glossary.md)
- [ADR-0004 主机局域网标识](0004-host-lan-identification.md)
