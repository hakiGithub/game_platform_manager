# 07 — 环境阻塞：容器内 GitHub 不可达，dst/sdtd 启停断言待转正

**Status:** needs-triage

## 背景

LinuxGSM 容器首启需访问 GitHub 下载 serverlist.csv，本机 WSL 容器内访问 GitHub 不通 → `/app/<shortname>` 脚本永不生成 → dst/sdtd 服务器无法进入运行中。这是**环境问题**（LinuxGsmDockerAdapter 源码注释有预言），非产品缺陷。票 08 已实现连通性预检秒级跳过。

## 解除条件（三选一）

1. 给 Docker 容器配置 HTTP 代理（daemon/容器级 `HTTP_PROXY`）；
2. 在可直连 GitHub 的环境跑 E2E（远程牺牲主机，凭据走 `E2E_TEST_HOST_*`）；
3. 为 LinuxGSM 提供离线 serverlist（镜像侧改造，成本最高）。

## 转正步骤

解除阻塞后，`frontend/e2e/main-app/instance/drill-legs.spec.js`：
- dst 腿：`needsToken: true` 保持（另需 DST cluster token，Steam 账号生成——见票内备注）；启停断言中"无 token 启动即退"逻辑确认是否仍适用；
- sdtd 腿：删除"轮询重试启动"用例中 `waitForLinuxGsmReady` 返回 false 的 SKIP 分支，恢复完整 `运行中` 断言；
- 跑 `npm run e2e:round` 验证整轮 PASS。

## 验收

- [ ] 阻塞解除（容器内 `curl -m 8 -sI https://raw.githubusercontent.com` 返回 2xx/3xx）
- [ ] sdtd 腿完整启停绿
- [ ] 整轮 PASS
