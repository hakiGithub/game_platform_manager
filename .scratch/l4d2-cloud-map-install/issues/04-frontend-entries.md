# 04: 前端入口（MapCenter 转存并安装 + Download 云盘链接页）

Status: ready-for-human

## 任务

- MapCenter.vue 详情展开区 downloadLinks 旁加「转存并安装」按钮 → Dialog：云盘账号下拉（直调主应用 `GET /api/cloud/accounts`，只列 HEALTHY）、目标实例下拉（复用现有实例选择）、可选提取码；提交 `POST /api/plugin/l4d2/download/cloud`（自动带 source/sourceId/title）。
- Download.vue 加「云盘分享链接」表单：账号 + 分享链接 + 提取码 + 实例 + 可选 targetPath；裸链接无 sourceId 时后端用 `share-{hash8}`。
- 任务列表展示 taskType=CLOUD（tag 云盘），详情含 transferMode/accountName/cloudPath。

## 验收

- 两入口均可提交并在下载页看到任务进度与结果。

Blocked by: 03

## Comments

2026-09-15 网页真实测试（IAB + 全栈 8080/3000）：
- Download「云盘分享」tab：账号下拉跨应用拉到主应用 /cloud/accounts（天翼直播盘），提交后任务列表出现"云盘"类型行，失败原因透传展示 ✅
- MapCenter 行展开：三条链接各带「转存并安装」；弹窗自动带地图名/分享链接/转存目录 /maps/orange-810/自带提取码 ✅
- 真实提交走通到天翼转存引擎；爬虫库全部分享链接已过期（天翼个人分享 30 天），clp-web 对照同一链接同样 resource-gone，非集成问题。有效分享的成功路径待真实链接联调。
- 修复部署问题：plugins/ 目录同时存在旧 SNAPSHOT 与新 1.0.0 jar，PF4J 加载旧的同 id jar 导致 UI 未更新（已删除旧 jar）。
