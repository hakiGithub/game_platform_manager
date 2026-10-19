# 02: CloudDriveService.transfer 加 passcode 参数

Status: resolved

## 任务

- plugin SDK `CloudDriveService.transfer` 签名改为 `(accountName, shareUrl, passcode, targetPath, timeout)`（passcode null=无提取码）。
- core `CloudDriveExecutor`/`CloudDriveServiceFactory` 同步：`TransferRequest.of(shareUrl,target)` 改为带 passcode 的构造（查 SDK：`new TransferRequest(shareUrl, passcode, targetMountPath, rule)`）。
- 既有测试/调用方同步（当前无插件调用方，破坏面为零）。

## 验收

- 编译通过；executor 单测补 passcode 透传断言。

Blocked by: （无）

## Answer

- `CloudDriveService.transfer` 已改为带 `passcode` 的五参数签名，`CloudDriveServiceFactory` 与 `CloudDriveExecutor` 已同步，`CloudInstallService` 调用点也已更新。
- `plugin-l4d2-core` 编译通过（`mvn -pl plugin-l4d2/plugin-l4d2-core -am -DskipTests compile`）。
- 2026-10-20 补充 `CloudDriveExecutorTransferTest`：覆盖 passcode 原样透传、空白 passcode 归一化及轮询至完成；该测试 2/2 通过。
- 与相关 cloud-drive / PatchInstall 用例一起运行，定向测试 18/18 通过；ticket 验收项已满足。
