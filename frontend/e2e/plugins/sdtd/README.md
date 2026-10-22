# sdtd 用例包

7 Days to Die（`plugin-7dtd`，`plugin.id=plugin-sdtd`，gameCode=`sdtd`）的插件子应用 E2E。

## 特点

- 部署方式只有 `linuxgsm-docker`（`games/sdtd.yml` 只声明了这一种）。
- 实例前置沿用 l4d2 包范式：`beforeAll` 经平台 API 造主机 + 部署实例，`afterAll` 删除。
- 容器首启会走 LinuxGSM auto-install（GitHub serverlist + SteamCMD），**分钟级**；
  页面类断言不依赖游戏进程是否已经在跑 —— 插件的列表接口在实例"已停止"时也返回
  明确空态，这本身就是可断言的行为。
- 事件上报（`/events`）依赖游戏端 CSMM modlet，牺牲主机上不会有真实事件，
  因此该页只断言"引导提示 + 三个 Tab + 空表"，不断言有数据。

## 跑法

```bash
# WSL，容器化栈
bash scripts/e2e-docker-run.sh e2e/plugins/sdtd

# Windows 受管模式
npm run e2e -- e2e/plugins/sdtd
```

未设置 `E2E_TEST_HOST_*` 时整组 SKIP。
