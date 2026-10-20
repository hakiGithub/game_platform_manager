# L4D2 Workshop 解析可用性与错误可读性

## 背景

L4D2 下载管理的 Workshop 解析依赖 Steam `IPublishedFileService/GetDetails`，
该接口要求 Steam Web API Key。当前部署没有配置 Key，导致合法 Workshop 链接
直接失败；插件异常又未被转换为业务异常，前端只显示通用“系统异常”。

## 目标

- 未配置 Steam Web API Key 时，单文件 Workshop 链接仍可正常解析。
- 公开解析接口不可用时，保留带 Key 的增强路径和集合/子项解析能力。
- Workshop 解析、链接解析和创建下载任务失败时，返回可操作的具体错误消息。
- 不改变现有响应数据结构，不记录 Web API Key 或下载凭证。

## 验收

- `POST /api/plugin/l4d2/download/parse-workshop` 对公开 L4D2 条目返回
  `code=200`、标题、文件名、大小、下载 URL 和缩略图。
- 无 Key 环境不抛 `STEAM_API_KEY 未配置` 的通用 500。
- 公开接口失败时可按配置回退到 `IPublishedFileService`。
- 非法链接和远端失败返回非 500 的可读业务码。
- Steam 客户端与控制器均有定向回归测试。

