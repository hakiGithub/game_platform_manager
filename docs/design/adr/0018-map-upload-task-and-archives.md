# ADR-0018: 地图上传任务化与压缩包支持

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-30 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0016](0016-rcon-host-capability.md)（RCON 宿主能力，裁剪/热重载依赖）、[ADR-0002](0002-main-app-plugin-scope-isolation.md) |
| 参考 | D:\program\open_source\l4d2-server-next-master（Go 参考实现：解压后只挑 .vpk、剥离目录结构） |
| Supersedes | 无 |

## 背景（Context）

原 `POST /maps/upload` 在请求线程内串行完成"暂存 + VPK 解析 + SSH 上传 + 自动裁剪"，大文件长阻塞；且只接受 `.vpk`，用户手中的 rar/zip/7z 压缩包被直接拒绝（2026-08-30 实测 `大灾变.rar` 报"只支持 VPK 格式"）。改造分两步落地：先任务化（已实现），再压缩包支持（本 ADR 主体）。

参考实现（l4d2-server-next，Go）的核心策略：解压到临时目录后**只挑 `.vpk` entry、其余丢弃**，文件名取 Base 并白名单清洗（天然免疫路径穿越），GBK 文件名处理，无解压炸弹限制、无 VPK magic 校验、全同步执行。本平台在其基础上取长补短：保留任务化异步（优于参考）、补 VPK 校验（已有 magic + missions 防线）、补解压炸弹防护（参考项目没有）。

## 决策（Decision）

### 决策 1：上传任务化（map-upload，已实现）

`POST /maps/upload` 同步阶段只做扩展名校验 + 文件暂存，提交 `map-upload` 任务（source=L4D2，scopeKey=instanceId 实例互斥）即返回 `{taskId, filename, size}`。重活（VPK 解析、SSH 上传、自动裁剪）由 `MapUploadTaskHandler` 在执行队列完成（超时 10 分钟、重试 1 次、暂存文件 finally 清理）。前端提示"已提交到执行队列"。

### 决策 2：压缩包格式支持（vpk/zip/rar/7z）

- stageUpload 扩展名白名单放宽为 `.vpk/.zip/.rar/.7z`（对齐参考项目，tar 不做）
- Handler 按扩展名分派：vpk 直接走 doUpload；压缩包先解压到临时目录，**只收集 `*.vpk`，其余 entry 丢弃**；解压时跳过 macOS 垃圾（`__MACOSX/`、`.DS_Store`），剥离目录结构
- **RAR 解压用 net.sf.sevenzipjbinding 16.02-2.01（all-platforms，含全平台原生库）**。修正记录：初版选 junrar 7.5.5，实测 workshop 包普遍为 RAR5，junrar 直接抛 `UnsupportedRarV5Exception`（其并不支持 RAR5，ADR 初稿的假设有误）；7-Zip JBinding 同时覆盖 RAR4/5，且主应用已依赖其原生栈。加密压缩包解压时报 WRONG_PASSWORD，给出明确提示
- zip 沿用现有 GBK 强制读取（中文文件名），7z 用现有 commons-compress 栈

### 决策 3：解压安全防护

- **Zip-Slip**：所有 entry 落盘前过 `ZipSlipGuard.normalizeAndCheck`（补齐 ArchiveExtractUtil 的既有缺口，插件安装链路同步受益）
- **插件运行期依赖规则（实测教训）**：PF4J 插件的第三方库在运行期经主应用 classpath 委托解析——插件 pom 的 compile 依赖若不在主应用 classpath，运行期抛 `NoClassDefFoundError` 且执行线程死亡、任务永久卡 RUNNING（`executeAsync` 只 `catch (Exception)` 接不住 Error）。junrar → sevenzipjbinding 已按此规则加入 core pom
- **任务失败语义**：任务框架对"正常返回的 TaskResult"一律标 COMPLETED，只有 Handler 抛异常才 FAILED——map-upload 的"全部失败/无 vpk"路径必须抛异常而非返回 failure
- **解压炸弹**：解压总字节数上限（默认 4GB）+ 条目数上限（默认 10000），超限立即中止并清理临时目录；参数在 `L4D2Config` 新增 `archive` 节（`maxExtractBytes`/`maxEntries`）
- 嵌套压缩包不支持（包内的 zip/rar entry 不是 .vpk，自然丢弃）；密码压缩包不支持

### 决策 4：多 VPK 部分成功语义

压缩包含多个 VPK 时逐个处理：单个 VPK 校验/上传失败**记录后继续**处理其余；任一成功即任务 SUCCESS，result 汇总"成功 x / 失败 y"及失败清单（任务详情可查）；全部失败才 FAILED。

### 决策 5：分片上传链路一致性

分片上传（chunk-upload）合并完成后若文件是压缩包，**同样提交 `map-upload` 任务**走同一 Handler——避免"小文件能解包、大文件原样落盘 addons"的行为分裂。

### 决策 6：暂存文件生命周期

暂存文件**成功后删除、失败保留**（供重试）；失败后不再重试的任务残留由既有启动清理机制兜底（对齐参考项目分片临时目录 6 小时过期清理的思路）。

## 后果（Consequences）

### 正面

- rar/zip/7z 压缩包可直接上传，与参考项目能力对齐
- 大文件不再阻塞 HTTP 请求线程，任务进度/失败原因在执行队列可视
- 解压安全防护（zip-slip/炸弹上限）补齐，且惠及插件安装链路
- 多 VPK 打包一次上传全部生效

### 负面

- 新增 junrar 依赖（纯 Java，无原生负担）；RAR5 高级特性（加密/分卷/恢复记录）不支持
- 解压上限参数误配过小会导致合法大地图被拒（可配置缓解）

### 中性

- 参考项目的"同步处理 + 无炸弹防护 + 无 VPK 校验"均不采纳；本平台裁剪默认开启（参考默认关）

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| sevenzip-jbinding | 原生库解 rar，格式支持最全 | 平台原生 .dll/.so 部署重，跨平台打包复杂 |
| 调宿主机 unrar/7z 命令 | 起进程解压 | 依赖游戏服主机装软件，Windows/Linux 差异不可控 |
| 全量解压上传 | 压缩包内容全部解到 addons | addons 会被非 VPK 文件污染，参考项目也只挑 .vpk |
| 严格失败语义 | 任一 VPK 失败整个任务 FAILED | 上传 3 张图挂 1 张回滚 2 张，用户体验差 |
| 分片链路不接 | 仅直传支持压缩包 | 同一压缩包大小不同行为分裂 |

## 未来方向

- 解压后自动触发 RCON 热重载（参考项目为手动触发；等用户需求出现再做）
- 压缩包内嵌套压缩包递归提取（目前明确不支持）
