# dst 用例包

Don't Starve Together（`plugin-dst`，`plugin.id=plugin-dst`，gameCode=`dst`）的插件子应用 E2E。

## 特点

- 部署方式只有 `linuxgsm-docker`。
- **无 Klei cluster token 时，服务器启动即退**（token 需 Steam 账号生成，无法自动化注入）。
  因此启停/在线类断言全部按"可观测状态"分支，不强行断言运行中。
- 本包刻意**只断言"页面契约 + 接口有响应 + 明确空态"**，不深入断言配置读写落盘：
  详见 [票 10](../../../../.scratch/e2e-wsl-ci-and-plugin-packs/issues/10-dst-path-root-mismatch.md)
  —— 平台给 linuxgsm-docker 实例的 `containerWorkDir` 是 `/app`，而 DST 插件约定的
  数据根是 `/data`，两处不一致导致插件读写落在错误目录。用例里把这一点显式标注，
  不静默改成"读默认值也算通过"。

## 跑法

```bash
bash scripts/e2e-docker-run.sh e2e/plugins/dst
npm run e2e -- e2e/plugins/dst     # Windows 受管模式
```

未设置 `E2E_TEST_HOST_*` 时整组 SKIP。

实测：**8/8 连续两轮全绿**（1.2m `--attach` / 1.7m 冷启动）。

## 本包特有的三个坑

1. **tab-pane 懒挂载 + waitForResponse 时序**：`el-tab-pane` 切 Tab 才挂载子组件，
   `waitForResponse` 必须建在点击之前。房设置的 INI 用例改成"切 Tab → 点编辑器自带的
   「重新加载」→ 等响应"，把时序攥在用例手里。
2. **`el-radio-group` 是 `role=radio`**：玩家统计的时间范围（最近 24 小时…）
   不能用 `getByRole("button")` 找。
3. **环境残留会伪装成插件缺陷**：宿主端口被上次的探针容器占住时，
   `docker compose up` 失败但实例仍在库里，插件侧报「无法解析实例容器名」。
   跑之前先 `docker ps -a | grep -i 'e2e\|probe'` 确认干净。

## 未覆盖（票 10 修复后升级）

见 `plugin-subapp.spec.js` 文件末尾 TODO：改值回读、名单落盘、modoverrides 写盘。
