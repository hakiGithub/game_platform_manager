# 08 — 难度/模式词汇表散在 3 语言 4 副本，已有可见漂移

**Status:** claimed
**Type:** task
**来源:** 架构评审 2026-10-15（候选 8）

## 现象

L4D2 的玩家数正则与难度/模式中英对照表共 **4 份副本**：

| 副本 | 位置 | 内容 |
|---|---|---|
| 1 | `L4D2RconService:32` | `PLAYERS_PATTERN` 正则 |
| 2 | `StatusParser:37` | **逐字相同**的正则副本 |
| 3 | `L4D2RconService:288-389` | 难度/模式中文↔英文切换表（≈100 行） |
| 4 | `frontend/src/utils/statusParser.ts:232,251` | TS 侧难度/模式对照表 |
| — | `frontend/src/utils/gameConstants.ts:6,31` | 第四份，含 `alias` 字段 |
| — | `frontend/src/api/index.ts:21-23` | `toCode()` 兼容层，只用于把 Java 输出翻回前端期望 |

**已经漂移**：`statusParser.ts` 对未知值返回 `'normal'`，而 API 层对未知返回 `''` —— 
`api/index.test.ts:57` 这条用例存在的目的就是给这处不一致打补丁。

## 根因

同一份词汇表被重新实现在 3 种语言里，且没有一个 owner。改一次要动 4 处。

**已有实际成本**：`ad6d58b fix(l4d2): 仪表盘难度/游戏模式前端映射修复` —— 
一次修复被施加在前端副本上。

ADR-0005 已为实例状态确立「后端枚举是唯一文本源」的做法（前端只保留 UI 元数据），
难度/模式未套用同一模式。

## 修复建议

插件侧一个词汇表 module（以 `StatusParser` 为唯一 owner，吸收 `L4D2RconService` 的切换表）：

```
parse(statusText) → Status
difficultyLabel(raw) → String      // 后端出中文标签
gameModeLabel(raw) → String
toWireCode(cnLabel) → String
```

前端删除 `difficultyMap` / `modeMap` / `toCode()`，改为消费后端已翻译的中文标签
（与 `instanceStatus.js` 消费 `runStatusDesc` 同形）。

## 验收

- 玩家数正则只有一处定义。
- 难度/模式词汇表只有一处；前端不再有对照表与兼容层。
- 未知值行为统一（不再一处 `normal`、一处空串）。
- 现有 `StatusParserTest` 扩展为词汇表的权威用例集。

## 关联

与票 01/04 同属一类根因：**契约住在约定里而非一个 owner 模块里**。
