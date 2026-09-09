# 02 — 测试数据仓库 + 主机凭据契约与 SKIP 机制

**What to build:** 测试数据仓库与"有没有真实主机"的统一处理。集中资产目录收纳用例所需数据：YAML 导入用游戏元数据文件、SourceMod 插件样例文件、按 VPK 格式合成的最小地图包（自检格式合法；真机可用性在地图上传票验证），用例只从仓库取数、运行时禁止临时外网下载。定义主机凭据环境契约（`E2E_TEST_HOST_ADDRESS` / `E2E_TEST_HOST_PORT` / `E2E_TEST_HOST_USERNAME` / `E2E_TEST_HOST_AUTH`），实现全局 SKIP 帮助器：凭据缺失时主机类用例统一跳过并在报告注明 `SKIP (no test host)`。用一条依赖主机的哑用例验证两态行为。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 资产入库且可被用例引用，含合成 VPK、SourceMod 插件样例、YAML 元数据
- [ ] 无主机凭据 → 主机类用例 SKIP 且报告注明原因
- [ ] 有主机凭据 → 同一批用例正常执行
- [ ] 环境契约与配置样例写入工程 README（含"凭据不入库"红线）
