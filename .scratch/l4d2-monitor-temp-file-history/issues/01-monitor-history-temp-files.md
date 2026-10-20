# 01: 性能监控历史改用临时文件

Status: resolved
Type: enhancement
来源: 用户反馈（2026-10-20）

## 现象

性能监控每秒采集一次，当前通过 `ExtensionClient.create` 写入扩展资源表。历史查询会
从表中读取最多 10000 条记录，再在内存中降采样。数据量增长后既增加数据库写入压力，
也增加查询时的内存占用。

## 期望

- 采集结果追加写入插件本地临时文件，不落扩展资源表。
- 历史查询流式读取文件，按查询时间范围和目标桶数直接聚合，不加载完整历史。
- 临时文件按实例隔离，保留时长到期后清理。
- 实例删除时清理对应文件。
- 保留现有监控 API 和前端图表契约。

## 验收

- [x] 采集不再调用 `ExtensionClient.create`。
- [x] 历史查询不再调用 `ExtensionClient.list` / `listAll`。
- [x] NDJSON 文件按实例写入，查询结果按时间升序返回。
- [x] 超过降采样阈值时结果不超过 `downsampleTo`。
- [x] 过期文件和实例删除时文件可被清理。
- [x] 监控相关单元测试、插件构建通过。

## Comments

- 2026-10-20：新增 `MonitorMetricFileStore`，按 `java.io.tmpdir/l4d2-monitor/{instanceId}/yyyyMMdd.ndjson`
  追加采集数据；查询时流式读取并聚合，内存仅保留最新值和有限桶。
- 2026-10-20：监控采集与 API 已移除 `ExtensionClient` 历史读写；实例删除与保留期清理会删除对应临时文件。
- 2026-10-20：修复降采样最终桶数可能超过 `downsampleTo` 的边界问题。
- 验证：`MonitorCollectorServiceTest`、`MonitorControllerTest`、`MonitorMetricFileStoreTest` 通过；
  `plugin-l4d2-core` 打包通过；插件前端 40 个测试及生产构建通过。
- 2026-10-20（人工验证复核）：临时文件历史读写与界面已生效，但主动采集未持续运行。
  插件 Spring 子容器未注册 `@Scheduled` 注解处理器，现有 `collectMetrics()` 不会被周期调用；
  同时采集入口依赖默认值为 `null` 的 `Monitor.gameId`，即使调度生效也会直接返回。
- 2026-10-20：重新打开票据。改为由插件生命周期自管守护调度线程，并通过
  `InstanceQueryService.listByGameCode("l4d2")` 获取实例，移除对宿主注入数据库 `gameId` 的依赖。
- 2026-10-20（修复验收）：`onLoad()` 启动单线程守护调度，`onUnload()` / `@PreDestroy`
  停止；固定延迟执行，采集慢时不会堆积并发任务。补充了按 gameCode 查询、启动停止幂等、
  停止时中断运行中采集的单元测试。
- 验证：监控相关 4 个测试类通过；插件 JAR 打包通过；后端重启后临时 NDJSON 文件在 4 秒内
  从 14532 字节增长到 15211 字节，5 分钟历史接口返回 69 个点；Playwright 探针通过。
