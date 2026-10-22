# e2e-wsl-ci-and-plugin-packs

把 E2E 变成干净容器环境可重复执行，并把 l4d2 的插件用例包范式复制到其余 3 个插件。

- Spec: [spec.md](spec.md)

## 票状态

| 票 | 类型 | 状态 | 阻塞 |
|----|------|------|------|
| [01 E2E 全栈容器化](issues/01-e2e-docker-stack.md) | task | **resolved** | - |
| [03 结果落盘与交付](issues/03-result-artifact.md) | task | ready-for-agent | 01 |
| [04 修 l4d2 日志页已知失败](issues/04-fix-log-page-flake.md) | bug | **resolved** | - |
| [05 sdtd 插件用例包](issues/05-sdtd-plugin-pack.md) | task | **resolved** | - |
| [06 dst 插件用例包](issues/06-dst-plugin-pack.md) | task | **resolved** | - |
| [07 dnf_tw 插件用例包](issues/07-dnf-tw-plugin-pack.md) | task | ready-for-agent | - |
| [08 CI 冒烟子集](issues/08-ci-smoke-subset.md) | task | ready-for-agent | 01, 03 |
| [09 l4d2 Docker 路径基准错配](issues/09-l4d2-docker-path-root-mismatch.md) | bug | ready-for-human | - |
| [10 DST 读写路径基准错配](issues/10-dst-path-root-mismatch.md) | bug | ready-for-human | - |
| [11 进入工作区硬编码 dashboard](issues/11-plugin-workspace-entry-hardcodes-dashboard.md) | bug | ready-for-human | - |

## 执行建议

01、04、05、06 已落地。03 与 07 可并行，08 等 03。
09、10、11 是用例包暴露出的产品缺陷，需产品决策，不阻塞用例包推进。

## 当前进度（2026-10-22）

Part A 完成：`bash scripts/e2e-docker-run.sh` 从零起栈跑 18 条冒烟用例 **21.4 秒全绿**。
仓库新增 6 个文本脚本（13 KB），无二进制、无第二份源码。
详见 [票 01](issues/01-e2e-docker-stack.md) 的实测结论与六个坑。

票 04 完成：l4d2 用例包全量 **28 条连续两轮全绿**（3.2m / 3.3m）。
日志页原来恒为空表的根因是 Docker 实例的 `containerWorkDir` 指向镜像里不存在的
目录（产品缺陷，另开票 09），用例侧改用平台文件 API 造夹具，
靠断言"平台能列出 → 插件能列出"保住断言力度。详见
[票 04](issues/04-fix-log-page-flake.md)。

票 05 完成：sdtd 用例包 **8 条连续两轮全绿**（28.6s / 27.9s），覆盖全部 6 个菜单页
加一条白名单写操作闭环。范式复制过程踩到 4 个定位器坑（主机 IP 唯一、侧边栏菜单
定位、Element Plus tab-pane 懒渲染、el-upload 双层 role），都不是范式本身的问题，
**`e2e/support/` 与 `playwright.config.js` 零改动**。详见
[票 05](issues/05-sdtd-plugin-pack.md)。

票 06 完成：dst 用例包 **8 条连续两轮全绿**（1.2m / 1.7m），覆盖全部 7 个菜单页。
`e2e/support/` 与 `playwright.config.js` 仍然零改动。本轮暴露 2 个产品缺陷
（票 10 路径基准 `/app` vs `/data`、票 11 落地页硬编码 dashboard），已在用例里
显式标注并留升级 TODO，不静默吞掉。详见 [票 06](issues/06-dst-plugin-pack.md)。

下一个可开工：**票 07（dnf_tw，10 菜单 + docker-compose + 5 个 MySQL 库）**，
以及 **票 03（结果落盘）→ 08（CI 冒烟子集）**。
