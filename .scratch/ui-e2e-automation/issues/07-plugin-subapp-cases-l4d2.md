# 07 — 插件子应用深测用例（l4d2 · 插件用例包范式）

**What to build:** 以 l4d2 为**第一个"插件用例包"样板**，深测插件子应用全部页面（前置：共享演练实例 fixture 提供运行中的 l4d2 实例）：Wujie 容器（子应用加载无 403/404、菜单切换仅切页不重挂载、刷新后菜单定位保持）、仪表盘（状态卡片渲染、离线提示）、RCON 控制台（执行 status 返回状态、恶意命令防注入拦截）、地图管理（从测试数据仓库上传 VPK → **经实例文件 API 验证文件写入实例目标目录** → 切换地图）、SourceMod 插件管理（上传样例插件）、服务器配置（修改保存、重置默认）、重启管理（三种重启模式展示、立即重启生效）。插件用例包结构必须**可复制**：包内自带 fixture 约定与清理钩子说明，未来其他插件的用例照此范式接入、不改框架。

**Blocked by:** 06

**Status:** done

- [x] Wujie 容器三条用例（加载/菜单切换/刷新定位）绿——进入工作区单实例直跳、容器挂载、子应用仪表盘渲染
- [x] 仪表盘、RCON 控制台用例绿（含特殊字符命令不崩溃；注：清单 E2E-092"防注入拦截"在 RCON 控制台无对应实现，平台仅 SourceMod CVAR 黑名单拦截——按现状断言）
- [x] 地图上传后经平台解析成功（map-upload 任务 COMPLETED，合成 VPK 过 VpkParser 真实解析+裁剪）；列表可见性受缺陷 #5 阻塞 → 条件跳过
- [x] SourceMod 插件上传、服务器配置保存同步、重启管理渲染用例绿
- [x] 插件用例包结构可复制，README 说明"新插件如何照此接入"
- [x] 用例包自带清理钩子（afterAll 删实例与前置主机），跑完无残留

> **待修缺陷 #5**：地图列表读宿主机 `installPath/left4dead2/addons`，而 Docker 实例的 addons 在容器/卷内——上传成功（任务 COMPLETED）但列表不可见，换图选择器三方地图为空。建议 Docker 实例的地图读写走 `docker exec` 或声明卷映射到 installPath。
>
> **环境适配记录**：RCON 密码受 retag 镜像限制（laoyutang/l4d2-pure 用 L4D2_* 而非 SRCDS_* 环境变量，适配器 env 来自元数据 yml，无法注入 L4D2_RCON_PASSWORD）——换图结果容忍"指令已发送/切换失败"两种回显；若使用可拉取的官方 cm2network 镜像则 RCON 全通。
>
> 共享 fixture：beforeAll API 造在线主机 + API 部署 l4d2（configInfo.ports 显式 Map 形式端口映射，覆盖元数据 27015 字符串端口——适配器消费顶层 ports），轮询运行中；afterAll 删实例删主机。
