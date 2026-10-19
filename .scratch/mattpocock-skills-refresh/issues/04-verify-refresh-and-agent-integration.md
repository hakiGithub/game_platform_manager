# 04: 验证刷新结果与 agent 接入

Status: resolved
Type: task
Blocked by: 01, 02, 03

## 要交付的行为

更新后的 skill 集合、锁文件、术语入口和中文指南在同一仓库状态下彼此一致。维护者能用一个可重复的检查过程发现上游副本漂移、遗漏 skill、残留废弃 skill 或文档入口错误。

## 验收

- [x] 从固定上游 commit 重算 27 个目标 skill 的 `SKILL.md` SHA-256，并与锁文件一致。
- [x] 项目级 `.agents/skills/` 与 `.claude/skills/` 的上游来源 skill 内容一致。
- [x] `npx skills list --json` 或等价扫描显示目标工程/生产力 skill 可见，三个自定义平台 skill 仍可见。
- [x] `resolving-merge-conflicts` 不再可见。
- [x] `implement-spec`、`pr`、`retro` 在对应 agent 中可见，metadata 与上游一致。
- [x] `GLOSSARY.md` 可读、`CONTEXT.md` 只作兼容指针，活跃文档的权威入口一致。
- [x] 中文使用指南中的命令与路径均能在当前仓库解析。
- [x] 给出一次完整验证命令清单与结果摘要，不修改业务代码。

## 失败处理

- 若锁文件与固定 clone 不一致，回到票 01 修正 source path 或 hash，不在验证票里手改单条 hash。
- 若 agent 只看到旧版本，检查项目级目录、agent link 与当前工作目录，不要用全局副本掩盖项目级漂移。
- 若 `CONTEXT.md` 仍出现在活跃规则中，回到票 02 区分兼容指针与待迁移引用。

## Answer

- 固定上游 clone 的 27 个目录 tree hash 与 `skills-lock.json` 全部一致；27 个 `SKILL.md` 的 SHA-256 与上游逐一相等。
- `.agents/skills/` 与 `.claude/skills/` 的 54 个上游目录比较无差异；3 个自定义 skill 保留。
- `skills@1.7.2 list --json` 发现 30 项：27 个上游来源 + 3 个自定义；新增 3 项可见，`resolving-merge-conflicts` 为 0 项。
- `GLOSSARY.md` 有 71 个术语条目；`CONTEXT.md` 无术语正文且指向新文件；`docs/agents/domain.md` 与根 `AGENTS.md` 使用新入口。
- 中文指南覆盖 14/14 必需 skill，PowerShell 检查块解析通过，所有引用的本地路径存在。
- 本票没有修改业务代码，新增的只是 tracker 完成记录。

### 复验命令

```powershell
$clone = 'C:\Users\haki\AppData\Local\Temp\codex-mattpocock-skills-49dd158d1076134a641b33efb035946536778336'
$lock = Get-Content -Raw -LiteralPath 'skills-lock.json' | ConvertFrom-Json
foreach ($prop in $lock.skills.PSObject.Properties) {
    $skillPath = $prop.Value.skillPath
    $folderPath = $skillPath.Substring(0, $skillPath.Length - 9)
    $folderName = Split-Path $folderPath -Leaf
    $treeHash = (git -C $clone rev-parse "HEAD:$folderPath").Trim()
    if ($treeHash -ne $prop.Value.skillFolderHash) {
        throw "Tree hash mismatch: $($prop.Name)"
    }
    foreach ($base in @('.agents\skills', '.claude\skills')) {
        git diff --no-index --quiet -- (Join-Path $clone $folderPath) (Join-Path $base $folderName)
        if ($LASTEXITCODE -ne 0) {
            throw "Directory mismatch: $base/$folderName"
        }
    }
}
```
