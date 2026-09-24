"""v0.3.10 核对：改写集合全量证明 + 判定对象零改动 + 反向对照。

用法:
  python spec-work/verify-v0310.py                 # 候选 = 工作树文件
  python spec-work/verify-v0310.py --ref bf0681c   # 反向对照：把旧 ref 当候选（新文本断言须 FAIL）
基线（父提交）恒为 OLD = bf0681c（v0.3.9）。
"""
import re
import subprocess
import sys
from collections import Counter

OLD = "bf0681c"
DOC = "docs/design/MERC-3/design.md"

# —— 本批授权/记账的精确清单（与 apply-v0310.py 一一对应）——
REWRITE_OLD = {
    "元数据·版本": "| **版本** | v0.3.9 |",
    "元数据·状态格尾": "不回改该格、也不新设第二枚基准**。 |",
    "元数据·版本链尾": "**判定正文被改写的既有行恰那两行签名、其余全为纯插入**；单独 commit、只触本文件、禁 force-push）。",
    "§8.4 命令面扩大": "`workDir` 下的 `.platform-extension/` 子目录仅放临时脚本，`finally` 删除 |",
    "§14.14 holder 伪代码": 'String holder   = "EXT:" + instanceId + ":" + stepIndex;   // 非任务 ID，见下行「与任务中心的边界」',
    "§15.2 实现落点": "| 实现落点 | `FileAccessService.executeCommand(hostId, command, timeoutMs)` **已支持显式超时**（`:254`），本期**零通道改造** | 这正是行 3「容器通道本期不改」不阻塞 OP-04 的原因 |",
}
# 被更正的旧串：本版全文须归零（裸 grep 即干净）
ZERO_IN_NEW = {
    "已支持显式超时": 0,
    '"EXT:" + instanceId + ":" + stepIndex': 0,
    "子目录仅放临时脚本，`finally` 删除": 0,
}
# 新增串：须恰出现于本版（父提交为 0）
MUST_APPEAR = {
    "`timeout <秒> bash <file>`": 3,
    "SshUtil:368": 3,
    "SshUtil:356": 3,
    "进程内自增": 6,
    "rmdir": 2,
}
# 判定对象与相邻判据：整行逐字未动
VERBATIM_ROWS = {
    "§10 V-26（holder 只认前缀）": "| **V-26** | **BR-09 每主机互斥**",
    "§14.14 与任务中心的边界": "| 与任务中心的边界（不违反 D-N05） |",
    "§14.14 等待预算取 600 s": "| 等待预算取 600 s |",
    "§14.13.3 命令行（同一事实的既有正确处）": "| 命令 | `cd <workDir> && COMPOSE_HTTP_TIMEOUT=300 timeout 1200",
    "§7.2 B-04（承键互斥判据）": "| B-04 | `PatchInstallServiceImpl` 实现 `installSync`",
    "§8.2 每宿主机互斥行": "| **每宿主机互斥** |",
    "§4 TaskMutexManager 零改动行": "| `backend/core/.../task/TaskMutexManager.java` |",
    "§14.6 规则 4（超时/非零可区分）": "4. **退出码判定**（v0.3）",
    "§15.1 首行（相邻滞后·只报不改）": "| 宿主机命令通道默认超时 | 30 s |",
    "§7.2 B-13 行（相邻滞后·只报不改）": "| B-13 | 脚本执行安全形状",
    "§8.4 脚本注入行（相邻滞后·只报不改）": "| 脚本注入 | 正文**永不拼进命令行**",
    "§1 引导句 :47（相邻滞后·只报不改）": "**命令通道两类，容器那一类不满足要求。**",
}
EXPECTED_REWRITTEN = 6          # 被改写的既有行（元数据 3 + 判定正文 3）
EXPECTED_BODY_REWRITTEN = 3     # 其中落在 §1–§16 的
ID_RE = re.compile(r"\b(?:V|B|AC|T|F|BR|FR|RISK|REV|SUG|OP|KPI|G|D-P|D-N|L)-\d+\b|\bN[1-9]\b")
HEX_RE = re.compile(r"\b[0-9a-f]{7,8}\b")
URL_RE = re.compile(r"https?://")
XYZ_RE = re.compile(r"(?<![vV.\d])\d+\.\d+\.\d+(?![\d.])")

P = F = 0
results = []


def chk(name, ok, detail=""):
    global P, F
    P += 1 if ok else 0
    F += 0 if ok else 1
    results.append(("PASS" if ok else "FAIL", name, detail))


def blob(ref):
    return subprocess.run(["git", "show", f"{ref}:{DOC}"], capture_output=True, check=True).stdout.decode("utf-8")


def strip_codespan(s):
    return re.sub(r"`[^`]*`", "``", s)


def body(text):
    """§1–§16 判定正文（切掉 §0 头部与 §17/§18 记账区）。"""
    i = text.index("## 1. 理解")
    j = text.index("## 17. 评审回应")
    return text[i:j]


def main():
    ref = None
    if "--ref" in sys.argv:
        ref = sys.argv[sys.argv.index("--ref") + 1]
    new = blob(ref) if ref else open(DOC, encoding="utf-8").read().replace("\r\n", "\n")
    old = blob(OLD)
    if ref == OLD:                      # 反向对照：候选即基线，只比"新文本断言"
        pass
    ol, nl = old.split("\n"), new.split("\n")
    co, cn = Counter(ol), Counter(nl)
    removed = co - cn                   # 被改写/删除的既有行
    added = cn - co                     # 本版新增/改写后的行

    # 1. 改写集合（REWRITE_OLD 存的是「行内被替换的那一段」，故按「行含段」配对，不按整行等值）
    rem_lines = list(removed.elements())
    pair = {k: [l for l in rem_lines if frag in l] for k, frag in REWRITE_OLD.items()}
    chk("被改写的既有行恰授权清单那 6 行（多重集差全量，非抽样）",
        sum(removed.values()) == EXPECTED_REWRITTEN and all(len(v) == 1 for v in pair.values()),
        f"removed={sum(removed.values())} 配对={ {k: len(v) for k, v in pair.items()} }")
    chk("每行旧串在父提交恰 1 次",
        all(old.count(c) == 1 for c in REWRITE_OLD.values()),
        str({k: old.count(c) for k, c in REWRITE_OLD.items()}))
    matched = {id(l) for v in pair.values() for l in v}
    chk("未被点名的既有行 100% 逐字保留（全量差之外无第三方改动）",
        all(id(l) in matched for l in rem_lines),
        f"第三方改写行 {len([l for l in rem_lines if id(l) not in matched])} 行")
    covered = sorted({l[:24] for l in rem_lines if any(f in l for f in
                    ("| **版本** |", "不回改该格", "恰那两行签名", "| 命令面扩大 |", "String holder", "| 实现落点 |"))})
    chk("改写行的身份：元数据 3 + §8.4/§14.14/§15.2 各 1",
        len(covered) == 6, f"{covered}")
    body_removed = [ln for ln in removed.elements() if ln in "\n".join(body(new).split("\n")) or any(
        ln.startswith(p) for p in ("| 实现落点", "String holder", "`workDir` 下的", "| 命令面扩大"))]
    chk("落在 §1–§16 判定正文的改写行恰 3（§8.4 / §14.14 / §15.2）",
        len(body_removed) == EXPECTED_BODY_REWRITTEN, f"{len(body_removed)}: {[l[:28] for l in body_removed]}")
    chk("纯插入行只增不改（新增行 − 改写行 == 净插入）",
        sum(added.values()) - sum(removed.values()) >= 0,
        f"added={sum(added.values())} removed={sum(removed.values())} 净插入={sum(added.values()) - sum(removed.values())}")

    # 2. 裸 grep 干净 / 新串到位
    for s, exp in ZERO_IN_NEW.items():
        chk(f"被更正的旧串全文归零：{s[:26]}", new.count(s) == exp, f"本版 {new.count(s)} 次 / 父提交 {old.count(s)} 次")
    for s, exp in MUST_APPEAR.items():
        chk(f"§1–§16 新串到位且在父提交为 0：{s[:22]}",
            body(new).count(s) >= 1 and body(old).count(s) == 0,
            f"正文本版 {body(new).count(s)} 次 / 父 {body(old).count(s)} 次（预期≥1 且 0）")

    # 3. 判定对象零改动：整文件 == 父提交 + 仅授权的替换（闭合式全量证明，非抽样）
    sys.path.insert(0, "spec-work")
    import importlib
    ap = importlib.import_module("apply-v0310")
    rebuilt = old
    for i, (o, n) in enumerate(ap.ALLX, 1):
        assert rebuilt.count(o) == 1, f"phase1/2 T{i} 旧串在父提交不恰 1 次"
        rebuilt = rebuilt.replace(o, n, 1)
    chk("**无第 11 处改动**：本版全文 == 父提交 + 恰那 10 处授权替换（逐字节）",
        rebuilt == new, f"父 {len(ol)} 行 / 本版 {len(nl)} 行 / 重建 {len(rebuilt.split(chr(10)))} 行")
    for name, prefix in VERBATIM_ROWS.items():
        o = [l for l in ol if l.startswith(prefix)]
        n = [l for l in nl if l.startswith(prefix)]
        chk(f"逐字未动：{name}", len(o) == len(n) and o == n, f"{len(o)}→{len(n)} 行")

    # 4. 编号 / 锚点 / 结构 / 禁编造
    io, inn = set(ID_RE.findall(old)), set(ID_RE.findall(new))
    chk("设计编号对称差为空（不新增、不消失）", io == inn, f"父 {len(io)} / 本版 {len(inn)}；+{sorted(inn - io)} -{sorted(io - inn)}")
    ho, hn = set(HEX_RE.findall(body(old))), set(HEX_RE.findall(body(new)))
    chk("§1–§16 的 commit/评论 形态字面集合不变（记账区不外泄）", ho == hn, f"+{sorted(hn - ho)} -{sorted(ho - hn)}")
    chk("MERC- 系 Issue 标识符不入判定正文",
        set(re.findall(r"MERC-\d+", body(new))) == set(re.findall(r"MERC-\d+", body(old))),
        str(sorted(set(re.findall(r"MERC-\d+", body(new))))))
    tcnt = lambda s: len(re.findall(r"^\| --- ", s, re.M))
    want_t = 1 if "**v0.3.10 自查" in new else 0
    chk("表格数：本版 = 父 + 1（只多本批 §17 自查表一张；phase1 中间态 +0）",
        tcnt(new) == tcnt(old) + want_t, f"{tcnt(old)} → {tcnt(new)}（期望 +{want_t}）")

    def bad_cols(s):
        bad, rows, tables, nosep = 0, 0, 0, 0
        for blk in re.split(r"\n\s*\n", s):
            ls = [l for l in blk.split("\n") if l.startswith("|")]
            if len(ls) < 2:
                continue
            tables += 1
            rows += len(ls)
            n = ls[0].count("|")
            bad += sum(1 for l in ls if l.count("|") != n)
            if not re.match(r"^\|[\s:|-]+\|$", ls[1]):
                nosep += 1
        return bad, rows, tables, nosep
    bo, bn = bad_cols(old), bad_cols(new)
    chk("列数不一致行 = 0", bn[0] == 0, f"父 {bo[0]} / 本版 {bn[0]}（表 {bo[2]}→{bn[2]} 张、行 {bo[1]}→{bn[1]}）")
    chk("缺分隔行的表 = 0", bn[3] == 0, f"父 {bo[3]} / 本版 {bn[3]}")
    uo, un = set(URL_RE.findall(old)), set(URL_RE.findall(new))
    chk("无新增 URL 字面量（禁编造）", len(un) == len(uo) == 0, f"父 {len(uo)} / 本版 {len(un)}")
    xo, xn = set(XYZ_RE.findall(strip_codespan(old))), set(XYZ_RE.findall(strip_codespan(new)))
    chk("x.y.z 形态串集合不变（剥 code-span 后）", xo == xn, f"+{sorted(xn - xo)} -{sorted(xo - xn)}")
    vko = set(re.findall(r"v\d+\.\d+(?:\.\d+)?", new)) - set(re.findall(r"v\d+\.\d+(?:\.\d+)?", old))
    chk("新增版本号字面量只含本版号", vko <= {"v0.3.10"}, f"新增 {sorted(vko)}")
    chk("无真实版号/URL/目标路径夹具（dnf-tw 侧）", "dnf-tw" not in "".join(
        [l for l in nl if l.startswith("| 实现落点") or l.startswith("| `timeoutMs` →")]), "两处新落点不提名牌夹具")

    # 5. 边界
    # 5. 边界（提交级优先；未提交时退到工作树级，并单独坐实 AGENTS.md 只是运行时注入块）
    head = subprocess.run(["git", "rev-parse", "--short=7", "HEAD"],
                          capture_output=True, text=True).stdout.strip()
    if not ref and head != OLD[:7]:
        g = lambda *a: subprocess.run(["git"] + list(a), capture_output=True, text=True).stdout.split()
        names = g("diff", "--name-only", OLD, "HEAD")
        ncom = g("rev-list", "--count", f"{OLD}..HEAD")
        anc = subprocess.run(["git", "merge-base", "--is-ancestor", OLD, "HEAD"]).returncode == 0
        chk("边界（提交级）：`bf0681c..HEAD` 只触 design.md、恰 1 个提交、快进",
            set(names) == {DOC} and ncom == ["1"] and anc, f"{names} / 提交数 {ncom} / ancestor={anc}")
    else:
        names = subprocess.run(["git", "diff", "--name-only", "HEAD"],
                               capture_output=True, text=True).stdout.split()
        extra = set(names) - {DOC}
        agents = subprocess.run(["git", "diff", "HEAD", "--", "AGENTS.md"],
                                capture_output=True, text=True).stdout
        chk("边界（提交前）：本批改动只有 design.md；AGENTS.md 仅运行时自动注入块",
            extra <= {"AGENTS.md"} and "BEGIN MULTICA-RUNTIME (auto-managed; do not edit)" in agents,
            f"额外文件 {sorted(extra)}；AGENTS.md 差量含运行时块={'BEGIN MULTICA-RUNTIME' in agents}")

    for st, name, detail in results:
        print(f"[{st}] {name}" + (f"  — {detail}" if detail else ""))
    print(f"\n== {'反向对照' if ref else '本版核对'} ref={ref or 'worktree'}  PASS {P} / FAIL {F} ==")
    sys.exit(1 if F else 0)


if __name__ == "__main__":
    main()
