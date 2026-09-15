# 04: 前端入口（MapCenter 转存并安装 + Download 云盘链接页）

Status: ready-for-human

## 任务

- MapCenter.vue 详情展开区 downloadLinks 旁加「转存并安装」按钮 → Dialog：云盘账号下拉（直调主应用 `GET /api/cloud/accounts`，只列 HEALTHY）、目标实例下拉（复用现有实例选择）、可选提取码；提交 `POST /api/plugin/l4d2/download/cloud`（自动带 source/sourceId/title）。
- Download.vue 加「云盘分享链接」表单：账号 + 分享链接 + 提取码 + 实例 + 可选 targetPath；裸链接无 sourceId 时后端用 `share-{hash8}`。
- 任务列表展示 taskType=CLOUD（tag 云盘），详情含 transferMode/accountName/cloudPath。

## 验收

- 两入口均可提交并在下载页看到任务进度与结果。

Blocked by: 03
