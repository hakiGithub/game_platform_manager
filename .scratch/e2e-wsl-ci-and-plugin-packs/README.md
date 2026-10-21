# e2e-wsl-ci-and-plugin-packs

把 E2E 变成干净容器环境可重复执行，并把 l4d2 的插件用例包范式复制到其余 3 个插件。

- Spec: [spec.md](spec.md)

## 票状态

| 票 | 类型 | 状态 | 阻塞 |
|----|------|------|------|
| [01 E2E 全栈容器化](issues/01-e2e-docker-stack.md) | task | **resolved** | - |
| [03 结果落盘与交付](issues/03-result-artifact.md) | task | ready-for-agent | 01 |
| [04 修 l4d2 日志页已知失败](issues/04-fix-log-page-flake.md) | bug | ready-for-agent | - |
| [05 sdtd 插件用例包](issues/05-sdtd-plugin-pack.md) | task | ready-for-agent | 04 |
| [06 dst 插件用例包](issues/06-dst-plugin-pack.md) | task | ready-for-agent | 05 |
| [07 dnf_tw 插件用例包](issues/07-dnf-tw-plugin-pack.md) | task | ready-for-agent | 06 |
| [08 CI 冒烟子集](issues/08-ci-smoke-subset.md) | task | ready-for-agent | 01, 03 |

## 执行建议

01 已落地（容器化跑通）。04（修已知失败）无依赖，可与 03 并行。
05 等 04；06 等 05；07 等 06。08 等 03。

## 当前进度（2026-10-22）

Part A 完成：`bash scripts/e2e-docker-run.sh` 从零起栈跑 18 条冒烟用例 **21.4 秒全绿**。
仓库新增 6 个文本脚本（13 KB），无二进制、无第二份源码。
详见 [票 01](issues/01-e2e-docker-stack.md) 的实测结论与六个坑。
