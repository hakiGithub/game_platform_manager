# 02: CloudDriveService.transfer 加 passcode 参数

Status: ready-for-human

## 任务

- plugin SDK `CloudDriveService.transfer` 签名改为 `(accountName, shareUrl, passcode, targetPath, timeout)`（passcode null=无提取码）。
- core `CloudDriveExecutor`/`CloudDriveServiceFactory` 同步：`TransferRequest.of(shareUrl,target)` 改为带 passcode 的构造（查 SDK：`new TransferRequest(shareUrl, passcode, targetMountPath, rule)`）。
- 既有测试/调用方同步（当前无插件调用方，破坏面为零）。

## 验收

- 编译通过；executor 单测补 passcode 透传断言。

Blocked by: （无）
