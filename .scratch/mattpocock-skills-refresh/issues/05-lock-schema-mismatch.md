# 05: 修复 skills-lock.json 的 hash schema 漂移

Status: resolved
Type: bug
Blocked by: 04

## 现象

本票由票 04 的独立复验发现，先以 `Status: needs-triage` 记录，再完成修复与复验。

票 01 把 `skills-lock.json` 从项目级 lock schema 改成了全局 lock 使用的
`skillFolderHash`（40 位 Git tree object ID）字段，但票 04 与 spec 的实现决定
要求 lock 记录每个 skill 的 `SKILL.md` SHA-256。当前文件无法满足该验收：

```powershell
$lock = Get-Content -Raw skills-lock.json | ConvertFrom-Json
$entry = $lock.skills.'ask-matt'
$entry.skillFolderHash.Length
```

当前输出为 `40`，而 SHA-256 必须是 `64` 位十六进制。

## 证据

固定上游 clone 的 `ask-matt` Git tree hash 与 lock 一致，但 `SKILL.md`
SHA-256 并不一致：

```powershell
$clone = 'C:\Users\haki\AppData\Local\Temp\codex-mattpocock-skills-49dd158d1076134a641b33efb035946536778336'
$lock = Get-Content -Raw skills-lock.json | ConvertFrom-Json
$entry = $lock.skills.'ask-matt'
(git -C $clone rev-parse 'HEAD:skills/engineering/ask-matt').Trim()
$entry.skillFolderHash
(Get-FileHash -Algorithm SHA256 (Join-Path $clone $entry.skillPath)).Hash.ToLowerInvariant()
```

观察结果：

- Git tree hash 与 lock 的 `skillFolderHash` 相等。
- `SKILL.md` SHA-256 为 64 位，且与 `skillFolderHash` 不相等。

`skills@1.7.2` 的项目级 lock 路径使用 `computedHash`（`computeSkillFolderHash`
产生 64 位 SHA-256）；当前 `skillFolderHash` 是全局 lock 的 tree-hash 形状。
项目更新逻辑会读取 `entry.computedHash`，因此当前 schema 还会让
`npx skills update` 无法正确判断本地副本已是最新。

## 影响

- 票 04 的“`SKILL.md` SHA-256 与锁文件一致”验收不成立。
- 项目级 skill 更新路径可能反复把已同步的 skill 当作有更新。
- 维护者无法从单一 lock 条目同时核对上游 tree 与文件内容 hash。

## 期望

保留固定 commit 与 `skillPath`，并为每个上游 skill 记录：

- `computedHash`: 上游 skill 目录的 64 位 SHA-256（项目 lock/CLI 兼容）。
- `skillMdHash`: 上游 `SKILL.md` 的 64 位 SHA-256（验收直接可核对）。
- `skillFolderHash`: 固定 commit 的 Git tree hash（现有 tree 校验可继续使用）。

修复后重新跑锁、tree、副本、发现性与文档路径检查，并更新票 04 的验证命令。

## 完成证据

- lock 的 27 个 `computedHash` 与上游目录 SHA-256 一致。
- lock 的 27 个 `skillMdHash` 与上游 `SKILL.md` SHA-256 一致。
- 项目级 CLI 更新不再因缺少 `computedHash` 将全部条目判为需更新。

## Answer

- 已从固定 clone 重算并写回 27 个 `computedHash` 与 `skillMdHash`，保留
  27 个 `skillFolderHash`。
- `npx skills@1.7.2 update ask-matt -p -y` 输出
  `All project skills are up to date`，证明项目级更新路径已恢复。
- 重新执行 27 项 lock/tree/54 个双目录副本检查，错误数为 0。
