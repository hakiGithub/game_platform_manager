"""v0.3.10 落文：三处代码事实更正（机械替换，每处断言旧串恰出现 1 次）。

用法: python spec-work/apply-v0310.py [--phase1|--phase2]
phase1 = 判定正文 + 元数据 + 性质注；phase2 = §17 自查块 + §18 行（需实测数字，由脚本参数注入）
"""
import sys

DOC = "docs/design/MERC-3/design.md"
OLD_REF = "bf0681c"   # 父提交 = v0.3.9（本文档链头）

T1_OLD = "| **版本** | v0.3.9 |"
T1_NEW = "| **版本** | v0.3.10 |"

T2_OLD = "不回改该格、也不新设第二枚基准**。 |"
T2_NEW = (
    "不回改该格、也不新设第二枚基准**。 **v0.3.10 追加的现行状态（只做加法，上方各版旧句一字未删）**："
    "本版仍是收口后的文档同步批，形制与 v0.3.9 同类——三处按已交付代码的机械更正（Leader 处置评论 `01a0d42d` §三-A，"
    "触发点是 MERC-20 的交付回传 `01a0cf5e` §4）：§15.2「实现落点」一行的前提被代码事实推翻、§14.14 的 holder 后缀取不到那枚步骤序号、"
    "§8.4 对 `.platform-extension/` 的删除粒度写粗了。**判定正文被改写的既有行恰这三行，另有两行是在原段内加限定的纯插入；"
    "不新开判据、不新增编号、判定对象与核对物一字不变**。`prd.md` 与 `docs/ui/MERC-3/*` 按「对读基准版本」语义不回改。 |"
)

T3_OLD = "**判定正文被改写的既有行恰那两行签名、其余全为纯插入**；单独 commit、只触本文件、禁 force-push）。"
T3_NEW = T3_OLD + (
    " → **v0.3.10 = 三处代码事实更正批（MERC-20 交付回读）**（Leader 处置评论 `01a0d42d` §三-A："
    "**①** §15.2「实现落点」行按代码事实更正——`executeCommand` 的 `timeoutMs` 只兜住建连与认证（`SshUtil:356` → `getOrCreateSession`），"
    "命令执行走 `executeRemoteCommand` 无超时（`SshUtil:368`）⇒ 脚本耗时的实际护栏在命令文本里：`timeout <秒> bash <file>`，秒粒度**向上**取整；"
    "**②** §14.14 的 holder 形状更正为 `EXT:<instanceId>:<进程内自增>`（那枚步骤序号在定稿的 SDK 签名里不可得）；"
    "**③** §8.4 的 `.platform-extension/` 收口粒度细化为「删自建脚本文件 + 尝试 `rmdir`，非空即保留」。"
    "**判定正文被改写的既有行恰这三行、另有两行原段内加限定的纯插入；判定对象、编号、核对物零改动**；"
    "单独 commit、只触本文件、禁 force-push）。"
)

T4_OLD = "逐处核对见 §17「v0.3.9 签名对齐」自查表。\n\n## 1. 理解"
T4_NEW = (
    "逐处核对见 §17「v0.3.9 签名对齐」自查表。\n"
    ">\n"
    "> **v0.3.10 的性质**：**三处「本文的断言与已交付代码对不上」的机械更正——判定对象与核对物零改动、不新增任何事实**。"
    "触发点是 MERC-20 的交付回传（评论 `01a0cf5e` §4）与 Leader 的处置（评论 `01a0d42d` §一 ②③⑤、§三-A）；"
    "三处同一形态：本文写作时依据的是既有代码的形状，而能力面落地时代码事实与本文不同。"
    "① **§15.2「实现落点」行**原写作该通道的入参足以兜住整条命令的执行——实际是 `SshUtil` 的 `timeoutMs` 只作用于建连与认证"
    "（`:356` → `getOrCreateSession`），命令执行走 `executeRemoteCommand` 不带超时（`:368`），挂死的脚本能把部署线程永久阻塞。"
    "同一事实本文另一处早已写明：§14.13.3「命令」行为 compose 类兜 `timeout 1200` 时给的就是这个理由，两适配器 `DEPLOY` 的现场注释亦同"
    "（`DockerComposeAdapter:259-261`、`LinuxGsmDockerAdapter:222-223`，两处都逐字写着「`SshUtil` 的 `timeoutMs` 仅作用于建连」）"
    "⇒ **本行是同一事实的第二处落点、v0.3 写作时漏改，这次更正把孤立的一行拉回本文自己的口径，不是新增知识**。"
    "护栏自此落在命令文本上：`timeout <秒> bash <file>`，秒粒度**向上**取整——向下取整会在声明的预算用满前就把脚本杀掉，"
    "属 BR-04 禁止的「主应用推断或覆盖声明」。通道本身（`SshUtil` / `FileAccessService`）一字未动 ⇒ 「本期零通道改造」的结论仍成立，"
    "只是它担保的不是这格。② **§14.14 的 holder** 伪代码把步骤序号当后缀，而 `installSync` 的定稿 SDK 签名是两参、拿不到序号"
    "（序号只作为 §14.6 的 `LogEntry` 字段位存在于呈现层）；实测后缀取进程内自增，而**「后缀两路互不相同」是正确性条件不是风格**："
    "`TaskMutexManager.putIfAbsent` 的防御支 `return existing.equals(taskId)`（`:52`）会让共用同一 holder 的两路都判「占用成功」，"
    "其 `finally` 的 `remove` 又是按值 CAS 删除（`:68`）⇒ 后进入的一路会把先进入那一路正持的键释放掉。"
    "前缀一字未动 ⇒ 本节「与任务中心的边界」行与 §10 V-26 的 holder 判据（只认前缀）取值与判定均不变。"
    "③ **§8.4 的 `.platform-extension/`** 一句原写作无差别的删净，实测粒度是「删平台自建的脚本文件 + 尝试 `rmdir` 该目录、非空即失败并原样保留」；"
    "用 `rmdir` 而非 `rm -rf` 是刻意的——脚本自己往该目录写过的产物不由平台顺手清空。"
    "**登记同类相邻表述四处未改（两类，超出本次点名的三格，按「先报后判」交 Leader，本版只报不改）**："
    "§1 引导句 `:47` 与 §15.1 首行的「默认 30s / 宿主机命令通道默认超时」仍是 `timeoutMs` 的旧读法（同一前提的另外两处），"
    "§7.2 B-13 步骤列与 §8.4「脚本注入」行的命令形状写作裸 `bash <file>`。"
    "写法沿用 v0.3.7 / v0.3.8 已获认可的自律：**本版新增记账文本不复现被更正的三处旧串** ⇒ 裸 grep 即干净。"
    "不引入真实版号 / URL / 目标路径，不新增设计编号；按 v0.3.6 钉死的「对读基准版本」语义不替 PRD 回改锚点。"
    "逐处核对见 §17「v0.3.10 三处代码事实更正」自查表。\n"
    "\n"
    "## 1. 理解"
)

T5_OLD = "`workDir` 下的 `.platform-extension/` 子目录仅放临时脚本，`finally` 删除 |"
T5_NEW = (
    "`workDir` 下的 `.platform-extension/` 子目录仅放平台自建的临时脚本，`finally` 删该脚本文件、再尝试 `rmdir` 该目录"
    "（用 `rmdir` 而非 `rm -rf`：脚本自身往这里写过的产物不由平台顺手清空，目录非空则 `rmdir` 失败、原样保留，"
    "`ExtensionScriptRunner` 的 `deleteQuietly`） |"
)

T6_OLD = 'String holder   = "EXT:" + instanceId + ":" + stepIndex;   // 非任务 ID，见下行「与任务中心的边界」'
T6_NEW = 'String holder   = "EXT:" + instanceId + ":" + seq;  // seq = 进程内自增；非任务 ID，取形依据见下表「holder 后缀的实际取形」行'

T7_OLD = "⇒ 不会把部署中的锁误释放 |\n| 锁粒度 = 共享资源的粒度 |"
T7_NEW = (
    "⇒ 不会把部署中的锁误释放 |\n"
    "| holder 后缀的实际取形（v0.3.10 按已交付代码更正） | 实测形状 = `EXT:<instanceId>:<进程内自增>`"
    "（`PatchInstallServiceImpl.installSync`，后缀取一个 `AtomicLong` 的递增值）。**上面的伪代码原取步骤序号作后缀，不成立**："
    "`installSync` 的定稿 SDK 签名是 `(request, listener)` 两参，序号属调用方的呈现层概念（只作为 §14.6 的 `LogEntry` 字段位存在），"
    "补丁入口拿不到它。**后缀两路互不相同是正确性条件，不是风格**：`TaskMutexManager.putIfAbsent` 的防御支 "
    "`return existing.equals(taskId)`（`:52`）会让共用同一 holder 的两路都判「占用成功」，而 `finally` 里的 `remove` 是按值 CAS 删除（`:68`）"
    "⇒ 后进入的一路会把先进入那一路正持的键释放掉，互斥形同虚设；进程内自增即满足「两路不同」，无需把序号穿进 SDK。**前缀一字未动** "
    "⇒ 上行的「不受 `removeByTaskId` 驱动」、本表「等待预算取 600 s」行、§10 V-26 的 holder 判据（只认前缀）取值与判定均不变 |\n"
    "| 锁粒度 = 共享资源的粒度 |"
)

T8_OLD = (
    "| 实现落点 | `FileAccessService.executeCommand(hostId, command, timeoutMs)` **已支持显式超时**（`:254`），"
    "本期**零通道改造** | 这正是行 3「容器通道本期不改」不阻塞 OP-04 的原因 |"
)
T8_NEW = (
    "| 实现落点（v0.3.10 按代码事实更正） | 执行通道 = `FileAccessService.executeCommand(hostId, command, timeoutMs)`（`:254`），"
    "但该入参**只兜住建连与认证**（`SshUtil:356` → `getOrCreateSession`）；命令执行走 `executeRemoteCommand`，**不带超时**（`SshUtil:368`）"
    "⇒ 它不构成脚本的耗时护栏。护栏在**命令文本**里：`timeout <秒> bash <file>`（远端 GNU `timeout` 发 SIGTERM、超时退出码 124），"
    "与两类适配器 `DEPLOY` 已在用的形状同形（`DockerComposeAdapter:259-261`、`LinuxGsmDockerAdapter:222-223`，"
    "两处的现场注释都逐字写着「`SshUtil` 的 `timeoutMs` 仅作用于建连，命令执行无超时」） | "
    "通道（`SshUtil` / `FileAccessService`）一字未动 ⇒ 本期仍**零通道改造**，「行 3 容器通道本期不改不阻塞 OP-04」的结论不变；"
    "只是这格担保的是「不加新通道」，不担保「脚本被兜住」，后者自此由命令形状负责 |\n"
    "| `timeoutMs` → 远端 `timeout` 的秒粒度（v0.3.10 细化） | 远端 `timeout` 只有秒的粒度 ⇒ **向上**取整"
    "（`ceil(timeoutMs / 1000)`，下界 1 s）：1400 ms → `timeout 2`。**超时识别按远端实际被杀的时刻比**，不按声明的毫秒数比："
    "`exitCode == 124` 且 `elapsedMs >= 秒数 × 1000` 才判超时，判超时则 `exitCode` 归 `null` | "
    "① 向下取整会在声明的预算还没用满前就把脚本杀掉（1400 ms → 1 s），这是主应用替插件推断并覆盖声明的一种，BR-04 禁止；"
    "向上的代价是预算最多被放宽不到 1 s，而本表「下限」行已把可声明预算钉在 `>= 1_000 ms`——下界 1 s 另防 `timeout 0` 被 GNU `timeout` 读成不限时。"
    "② 若按声明毫秒数比，一次真超时会被读成普通的非零退出 `124` ⇒ 破掉 §14.6 规则 4 要求的「超时与非零退出可靠区分」"
    "（超时 `exitCode == null`，原因段才填得对） |"
)

PHASE1 = [(T1_OLD, T1_NEW), (T2_OLD, T2_NEW), (T3_OLD, T3_NEW), (T4_OLD, T4_NEW),
          (T5_OLD, T5_NEW), (T6_OLD, T6_NEW), (T7_OLD, T7_NEW), (T8_OLD, T8_NEW)]

# —— phase 2：记账区（§17 自查块 + §18 行）。含 <TBD-…> 令牌，实测后由 fill-v0310.py 就地替换 ——
T9_OLD = "\n## 18. 修订记录\n"
T9_NEW = (
    "\n"
    "**v0.3.10 自查（三处代码事实更正 + 两行原段内加限定，全部可复跑；核对脚本与完整输出随回传评论附上）。"
    "本表 `:NNN` 一律指父提交 `bf0681c`（v0.3.9）的行号**：本版在 §0 性质注处插入 2 行、§14.14 表内插入 1 行、"
    "§15.2 表内插入 1 行、本表插入 18 行、§18 插入 1 行 ⇒ `:319`→`:321`、`:882`→`:884`、`:946`→`:949`；"
    "脚本一律按行首形态锚定、不按行号锚定，偏移不影响任何一条判据。\n"
    "\n"
    "| 自查项 | 方法 | 结果 |\n"
    "| --- | --- | --- |\n"
    "| **判定正文被改写的既有行恰三行**（§8.4 / §14.14 / §15.2），元数据三格另计 | "
    "整文件多重集差 `Counter(父全部行) − Counter(本版全部行)` 求出被改写行全集，再逐行按行首形态归节 | "
    "被改写既有行恰 6 行 = 元数据「版本」「状态」「版本链」3 + 上述 3；落在 §1–§16 的**恰 3 行** |\n"
    "| **无第 9 处改动**（闭合式全量证明，不靠抽样） | 把本批 10 处授权替换按序施加到父提交文本，与本版全文逐字节比 | "
    "逐字节相等 ⇒ 本版没有任何一处改动落在授权清单之外 |\n"
    "| 三处都是**原段内单段替换**、不夹带判据 | "
    "整行等式断言 `旧行.replace(旧段, 新段) == 新行`（三行各由自己那一段决定）；§14.14 另断言前缀 `String holder   = \"EXT:\" + instanceId + \":\"` 逐字未动 | "
    "三行各差恰一段；前缀未动 ⇒ 更正只落在后缀与注释 |\n"
    "| 两枚新增行是**纯插入**、且在原表内、不新开判据 | "
    "① §14.14 新行落在「与任务中心的边界」与「锁粒度」两行之间；② §15.2 新行落在「实现落点」行之后、`### 15.3` 之前；两表列数一致性另计 | "
    "插入 2 行；两行的主语都是既有事实的取形与粒度，未新增任何一条判定 |\n"
    "| 被更正的三处旧串**全文归零**（记账文本不复现旧串 ⇒ 裸 grep 即干净） | 对三个旧串各数本版与父提交命中 | "
    "本版 0 / 0 / 0，父提交各 1 |\n"
    "| **判定对象与核对物零改动**（12 行相邻判据逐字未动） | "
    "按行首前缀锚定逐行比对两版：§10 V-26 holder 判据行、§14.14「与任务中心的边界」与「等待预算取 600 s」两行、§14.13.3「命令」行、"
    "§7.2 B-04 行、§8.2「每宿主机互斥」行、§4 `TaskMutexManager` 零改动行、§14.6 规则 4，以及本版**只报不改**的四处相邻滞后行"
    "（§1 `:47`、§15.1 首行、§7.2 B-13、§8.4 脚本注入行） | 12 行各 1→1 行、内容逐字相等 |\n"
    "| 编号零改动 | 两版各抽 `V-/B-/AC-/T-/F-/BR-/FR-/RISK-/REV-/SUG-/OP-/KPI-/G-/D-P-/D-N-/L-/N1…N5` 集合做对称差 | "
    "对称差为空（179 / 179） |\n"
    "| 记账区不外泄到判定正文 | 全量扫两版 §1–§16 的 7–8 位十六进制形态字面与 `MERC-` 系 Issue 标识符 | "
    "两集合各自完全相同（本版三行更正只引「文件:行号」，评论号只进 §0 / 本表 / §18） |\n"
    "| 表格结构 | 剥 code-span 后按「以竖线开头的连续行成块」统计表数、列数一致性、缺分隔行 | "
    "表数 73 → 74（本表）；列数不一致行 = 0；缺分隔行 = 0 |\n"
    "| 禁编造复扫（与 v0.2…v0.3.9 同口径） | 公网 URL 字面量、`x.y.z` 形态串（剥 code-span 后）、真实版号片段各扫一遍并与父提交做差 | "
    "URL 0 / 0；`x.y.z` 集合不变；本版新增版本号字面量只含 `v0.3.10` |\n"
    "| **尺子本身不空转**（反向对照） | 同一脚本 `--ref bf0681c`，把父提交当候选再跑一遍「本版新文本」断言 | "
    "26 PASS / 12 FAIL / exit 1——「恰三行改写」「旧串归零」「新串到位」「无第 9 处改动」四组在旧 ref 上全部不成立 |\n"
    "| 引用的代码事实**逐条对实物核**，不采信任何自述 | `carrier-v0310.py`：对 `SshUtil:356` / `:368`、`FileAccessService:254`、"
    "`TaskMutexManager:52` / `:68`、`DockerComposeAdapter:259-261`、`LinuxGsmDockerAdapter:222-223` 各取该行内容断言其形态；"
    "再对两处新落点的命令形状与清理粒度取交付分支实物断言 | 17 条全 PASS（代码事实与本文两版无关，故新旧两侧同判、不计入反向对照的 FAIL 数） |\n"
    "| 边界 | `git diff --name-only bf0681c <本版>`；`git log --oneline bf0681c..<本版>`；"
    "`git merge-base --is-ancestor bf0681c <本版>`；push 未用 `--force` / `--force-with-lease` | "
    "仅命中 `docs/design/MERC-3/design.md`；恰一个提交；新 commit 续在 `bf0681c` 之后**同一分支**（`agent/architect/merc-3`）"
    "⇒ 快进、不覆盖、不 force-push；未碰 `prd.md`、`docs/ui/MERC-3/*`、功能代码 |\n"
    "\n"
    "## 18. 修订记录\n"
)

T10_OLD = "只触 `docs/design/MERC-3/design.md` |"
T10_NEW = (
    "只触 `docs/design/MERC-3/design.md` |\n"
    "| v0.3.10 | 2026-09-25 | Spec（按 Leader 处置评论 `01a0d42d` §三-A 落文，非 @Architect 新判定） | "
    "**三处代码事实更正批（MERC-20 交付回读、零轮次记账、不占评审轮次）**："
    "① §15.2「实现落点」行的前提被代码事实推翻——`timeoutMs` 只兜住建连与认证（`SshUtil:356`）、命令执行无超时（`SshUtil:368`）"
    "⇒ 脚本耗时的护栏自此写明在命令文本上（`timeout <秒> bash <file>`），秒粒度**向上**取整（向下取整即主应用覆盖声明，触 BR-04）；"
    "② §14.14 的 holder 后缀由「步骤序号」更正为「进程内自增」（定稿 SDK 签名拿不到序号；且后缀须两路互不相同，"
    "否则 `TaskMutexManager:52` 的防御支配上 `:68` 的按值 CAS 删除会让互斥形同虚设）；"
    "③ §8.4 的 `.platform-extension/` 收口粒度细化为「删自建脚本文件 + 尝试 `rmdir`，非空即保留」。"
    "**判定正文被改写的既有行恰这三行、另有两行原段内加限定的纯插入；判定对象、编号、核对物零改动**；"
    "另登记同类相邻滞后**四处只报不改**（§1 `:47`、§15.1 首行、§7.2 B-13、§8.4 脚本注入行）交 Leader 判。"
    "单独 commit、只触本文件、快进、不 force-push。逐处核对见 §17「v0.3.10」自查表。 |"
)

PHASE2 = [(T9_OLD, T9_NEW), (T10_OLD, T10_NEW)]
ALLX = PHASE1 + PHASE2


def load():
    with open(DOC, "rb") as f:
        raw = f.read().decode("utf-8")
    assert "\r\n" not in raw or True
    return raw.replace("\r\n", "\n")


def save(text):
    with open(DOC, "wb") as f:
        f.write(text.encode("utf-8"))


def blob(ref):
    import subprocess
    return subprocess.run(["git", "show", f"{ref}:{DOC}"],
                          capture_output=True, check=True).stdout.decode("utf-8")


def main():
    which = ALLX if "--phase2" in sys.argv else PHASE1
    if "--check-only" in sys.argv:
        t = load()
        for old, new in which:
            print(f"count_old={t.count(old)}  {old[:44]!r}")
        return
    if "--rebuild" in sys.argv:
        text = blob(OLD_REF)
        for i, (o, n) in enumerate(which, 1):
            assert text.count(o) == 1, f"T{i} 旧串命中 {text.count(o)} 次"
            text = text.replace(o, n, 1)
        save(text)
        print(f"rebuilt from {OLD_REF} + {len(which)} authorized replacements")
        return
    text = load()
    base = len(PHASE1) if "--phase2" in sys.argv else 0
    for i, (old, new) in enumerate(which, 1 + base):
        n = text.count(old)
        assert n == 1, f"T{i}: 旧串命中 {n} 次（须恰 1 次）: {old[:80]!r}"
        text = text.replace(old, new, 1)
    save(text)
    print(f"applied {len(which)} replacements -> {DOC}")


if __name__ == "__main__":
    main()
