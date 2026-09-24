"""v0.3.11 核对：改写集合全量证明 + 判定对象零改动 + 反向对照。

用法:
  python _v0311/verify-v0311.py                        # 候选 = _v0311/design-v0311.md（未提交时）
  python _v0311/verify-v0311.py --ref <commit>         # 候选 = 该提交的 design.md（提交后 / 反向对照）
  python _v0311/verify-v0311.py --ref f3f3703          # 反向对照：把父提交当候选 ⇒ 新文本断言须 FAIL
基线（父提交）恒为 OLD = f3f3703（v0.3.10）。
"""
import importlib
import re
import subprocess
import sys
from collections import Counter

OLD = "f3f3703"
DOC = "docs/design/MERC-3/design.md"
CAND_FILE = "_v0311/design-v0311.md"

sys.path.insert(0, "_v0311")
ap = importlib.import_module("apply-v0311")

# —— 改写行内的被替换段（7 行：正文 4 + 元数据 3）——
REWRITE_LABEL = {
    "§1 引导句 :49": "**命令通道两类，容器那一类不满足要求。**",
    "§15.1 首行 :932": "| 宿主机命令通道",
    "§7.2 B-13 :245": "| B-13 | 脚本执行安全形状",
    "§8.4 脚本注入 :318": "| 脚本注入 | 正文",
    "元数据·版本": "| **版本** |",
    "元数据·状态": "| **状态** |",
    "元数据·版本链": "> 本文按「先落盘再细化」分次提交",
}
REWRITE_OLD = {                      # 与 apply 的 T1…T7 一一对应（顺序即 T 序）
    "§1 引导句 :49": ap.T1_OLD,
    "§15.1 首行 :932": ap.T2_OLD,
    "§7.2 B-13 :245": ap.T3_OLD,
    "§8.4 脚本注入 :318": ap.T4_OLD,
    "元数据·版本": ap.T5_OLD,
    "元数据·状态": ap.T6_OLD,
    "元数据·版本链": ap.T7_OLD,
}
REWRITE_NEW = {
    "§1 引导句 :49": ap.T1_NEW,
    "§15.1 首行 :932": ap.T2_NEW,
    "§7.2 B-13 :245": ap.T3_NEW,
    "§8.4 脚本注入 :318": ap.T4_NEW,
    "元数据·版本": ap.T5_NEW,
    "元数据·状态": ap.T6_NEW,
    "元数据·版本链": ap.T7_NEW,
}
# —— 纯插入的锚点（§0 性质注 / §17 自查块 / §18 行）——
INSERT_OLD = {"§0 性质注": ap.T8_OLD, "§17 自查块": ap.T9_OLD, "§18 行": ap.T10_OLD}
# —— 被更正的旧串：本版全文须归零 ——
ZERO_IN_NEW = {
    "§1 旧读法（默认 30s 当命令超时）": ap.T1_OLD,
    "§15.1 旧行标签": "| 宿主机命令通道默认超时 | 30 s |",
    "B-13 裸形状": ap.T3_OLD,
    "§8.4 裸形状": ap.T4_OLD,
    "元数据旧版号": ap.T5_OLD,
}
# —— §1–§16 正文内新串的到位计数（父 → 本版，精确数、非「≥1」）——
BODY_COUNTS = {
    "`timeout <秒> bash <file>`": (1, 3),
    "bash <file>": (3, 3),
    "SshUtil:356": (1, 3),
    "SshUtil:368": (1, 3),
    "getOrCreateSession": (1, 3),
    "30_000L": (1, 2),
    "F-13": (1, 1),
}
# —— 判定对象与相邻判据：整行逐字未动（含本版只报不改的三处） ——
VERBATIM_ROWS = {
    "§10 V-26（holder 判据）": "| **V-26** | **BR-09 每主机互斥**",
    "§15.2 实现落点（v0.3.10 刚落）": "| 实现落点（v0.3.10 按代码事实更正） |",
    "§15.2 秒粒度（v0.3.10 刚落）": "| `timeoutMs` → 远端 `timeout` 的秒粒度",
    "§15.2 缺省值（同族·只报不改）": "| **缺省值** |",
    "§15.3 首行（同族·只报不改）": "`FileAccessService` 的超时语义是",
    "§12 RISK-D06 行（同族·只报不改）": "| RISK-D06 |",
    "§14.13.3 命令行（同事实的既有正确处）": "| 命令 | `cd <workDir> && COMPOSE_HTTP_TIMEOUT=300 timeout 1200",
    "§14.6 规则 4（超时/非零可区分）": "4. **退出码判定**（v0.3）",
    "§8.4 命令面扩大行（v0.3.10 刚改）": "| 命令面扩大 |",
    "§14.14 holder 后缀取形行（v0.3.10 刚插入）": "| holder 后缀的实际取形",
}
EXPECTED_REWRITTEN = 7                # 被改写的既有行（元数据 3 + 正文 4）
EXPECTED_BODY_REWRITTEN = 4           # 其中落在 §1–§16 的
EXPECTED_REPLACEMENTS = 10            # 本批授权替换总数
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
    return subprocess.run(["git", "show", f"{ref}:{DOC}"], capture_output=True, check=True)\
        .stdout.decode("utf-8").replace("\r\n", "\n")


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
    new = blob(ref) if ref else open(CAND_FILE, encoding="utf-8").read().replace("\r\n", "\n")
    old = blob(OLD)
    ol, nl = old.split("\n"), new.split("\n")
    co, cn = Counter(ol), Counter(nl)
    removed, added = co - cn, cn - co

    # 1. 改写集合（按「行含段」配对，不按整行等值）
    rem_lines = list(removed.elements())
    pair = {k: [l for l in rem_lines if frag in l] for k, frag in REWRITE_OLD.items()}
    sizes = {k: len(v) for k, v in pair.items()}
    chk("被改写的既有行恰授权清单那 7 行（多重集差全量，非抽样）",
        sum(removed.values()) == EXPECTED_REWRITTEN and all(v == 1 for v in sizes.values()),
        f"removed={sum(removed.values())} 配对={sizes}")
    chk("每处旧串在父提交恰 1 次（正文 4 + 元数据 3）",
        all(old.count(c) == 1 for c in REWRITE_OLD.values()),
        str({k: old.count(c) for k, c in REWRITE_OLD.items()}))
    matched = {id(l) for v in pair.values() for l in v}
    chk("未被点名的既有行 100% 逐字保留（全量差之外无第三方改动）",
        all(id(l) in matched for l in rem_lines),
        f"第三方改写行 {len([l for l in rem_lines if id(l) not in matched])} 行")
    chk("改写行的身份与配对锚点一致（正文 4 + 元数据 3）",
        all(len([l for l in rem_lines if l.startswith(REWRITE_LABEL[k])]) == 1 for k in REWRITE_LABEL),
        str({k: len([l for l in rem_lines if l.startswith(REWRITE_LABEL[k])]) for k in REWRITE_LABEL}))
    body_removed = [l for l in rem_lines if "\n" + l in "\n" + body(new) or any(
        l.startswith(p) for p in ("**命令通道两类", "| 宿主机命令通道", "| B-13 |", "| 脚本注入 |"))]
    chk("落在 §1–§16 判定正文的改写行恰 4（§1 / §15.1 / §7.2 B-13 / §8.4）",
        len(body_removed) == EXPECTED_BODY_REWRITTEN,
        f"{len(body_removed)}: {[l[:26] for l in body_removed]}")
    chk("纯插入行只增不改（added − removed == 净插入且 ≥ 0）",
        sum(added.values()) - sum(removed.values()) >= 0,
        f"added={sum(added.values())} removed={sum(removed.values())} 净插入={sum(added.values()) - sum(removed.values())}")
    chk("§1–§16 判定正文行数两版相同 ⇒ 正文零新增行（未新开任何判据）",
        len(body(old).split("\n")) == len(body(new).split("\n")),
        f"父 {len(body(old).split(chr(10)))} 行 / 本版 {len(body(new).split(chr(10)))} 行")
    chk("三处插入锚点在父提交各恰 1 次、且其原文在本版逐字保留",
        all(old.count(a) == 1 and a.split("\n")[0] in new for a in INSERT_OLD.values()),
        str({k: old.count(a) for k, a in INSERT_OLD.items()}))

    # 2. 四处单段替换：整行等式断言（旧行 replace(旧段, 新段) == 新行）
    for k in ("§1 引导句 :49", "§15.1 首行 :932", "§7.2 B-13 :245", "§8.4 脚本注入 :318"):
        o = [l for l in ol if l.startswith(REWRITE_LABEL[k])]
        n = [l for l in nl if l.startswith(REWRITE_LABEL[k])]
        chk(f"原段内单段替换、不夹带判据：{k}",
            len(o) == 1 and len(n) == 1 and o[0].replace(REWRITE_OLD[k], REWRITE_NEW[k]) == n[0],
            f"{len(o)}→{len(n)} 行")
    b13 = "命令文本里不出现脚本正文（RISK-08 缓解）；未校验通过不在宿主机留文件"
    chk("B-13 的核对物列逐字未动（RISK-08 判据不变）",
        all(l.endswith("| " + b13 + " |") for l in ol + nl if l.startswith("| B-13 | 脚本执行安全形状")),
        "两版 B-13 行同尾")
    src15 = "`plugin/.../service/FileAccessService.java:264`（`executeCommand(hostId, command)` 重载的字面量 `30_000L`）"
    chk("§15.1 那行的取值 30 s 与出处列逐字未动（只改标签与该锚点可当何用）",
        all("| 30 s | " + src15 in l for l in ol + nl if l.startswith("| 宿主机命令通道")),
        "两版同含「30 s | 出处」")

    # 3. 裸 grep 干净 / 新串到位（精确计数）
    for s, frag in ZERO_IN_NEW.items():
        chk(f"被更正的旧串全文归零：{s}", new.count(frag) == 0 and old.count(frag) == 1,
            f"本版 {new.count(frag)} 次 / 父提交 {old.count(frag)} 次")
    for s, (eo, en) in BODY_COUNTS.items():
        chk(f"§1–§16 新串计数（父→本版）：{s}",
            body(old).count(s) == eo and body(new).count(s) == en,
            f"父 {body(old).count(s)}（期望 {eo}）/ 本版 {body(new).count(s)}（期望 {en}）")
    chk("正文里每一处 `bash <file>` 都带 `timeout <秒>` 前缀（形状收口的闭合式）",
        body(new).count("bash <file>") == body(new).count("`timeout <秒> bash <file>`"),
        f"{body(new).count('bash <file>')} vs {body(new).count('`timeout <秒> bash <file>`')}")

    # 4. 判定对象零改动：整文件 == 父提交 + 仅授权的 10 处替换（闭合式全量证明）
    rebuilt = old
    for i, (o, n) in enumerate(ap.ALLX, 1):
        assert rebuilt.count(o) == 1, f"T{i} 旧串在父提交不恰 1 次"
        rebuilt = rebuilt.replace(o, n, 1)
    chk(f"**无第 {EXPECTED_REPLACEMENTS + 1} 处改动**：本版全文 == 父提交 + 恰 {EXPECTED_REPLACEMENTS} 处授权替换（逐字节）",
        rebuilt == new, f"父 {len(ol)} 行 / 本版 {len(nl)} 行 / 重建 {len(rebuilt.split(chr(10)))} 行")
    for name, prefix in VERBATIM_ROWS.items():
        o = [l for l in ol if l.startswith(prefix)]
        n = [l for l in nl if l.startswith(prefix)]
        chk(f"逐字未动：{name}", len(o) == len(n) and o == n, f"{len(o)}→{len(n)} 行")

    # 5. 编号 / 锚点 / 结构 / 禁编造
    io_, in_ = set(ID_RE.findall(old)), set(ID_RE.findall(new))
    chk("设计编号对称差为空（不新增、不消失）", io_ == in_,
        f"父 {len(io_)} / 本版 {len(in_)}；+{sorted(in_ - io_)} -{sorted(io_ - in_)}")
    ho, hn = set(HEX_RE.findall(body(old))), set(HEX_RE.findall(body(new)))
    chk("§1–§16 的 commit/评论 形态字面集合不变（记账区不外泄）", ho == hn, f"+{sorted(hn - ho)} -{sorted(ho - hn)}")
    chk("MERC- 系 Issue 标识符不入判定正文",
        set(re.findall(r"MERC-\d+", body(new))) == set(re.findall(r"MERC-\d+", body(old))),
        str(sorted(set(re.findall(r"MERC-\d+", body(new))))))

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
    tc = lambda s: len(re.findall(r"^\| --- ", s, re.M))
    bo, bn = bad_cols(old), bad_cols(new)
    want_t = 1 if "**v0.3.11 自查" in new else 0
    chk("表格数：本版 = 父 + 1（只多本批 §17 自查表一张）",
        tc(new) == tc(old) + want_t, f"{tc(old)} → {tc(new)}（期望 +{want_t}）")
    chk("列数不一致行 = 0", bn[0] == 0, f"父 {bo[0]} / 本版 {bn[0]}（表 {bo[2]}→{bn[2]} 张、行 {bo[1]}→{bn[1]}）")
    chk("缺分隔行的表 = 0", bn[3] == 0, f"父 {bo[3]} / 本版 {bn[3]}")
    uo, un = set(URL_RE.findall(old)), set(URL_RE.findall(new))
    chk("无新增 URL 字面量（禁编造）", len(un) == len(uo) == 0, f"父 {len(uo)} / 本版 {len(un)}")
    xo, xn = set(XYZ_RE.findall(strip_codespan(old))), set(XYZ_RE.findall(strip_codespan(new)))
    chk("x.y.z 形态串集合不变（剥 code-span 后）", xo == xn, f"+{sorted(xn - xo)} -{sorted(xo - xn)}")
    vko = set(re.findall(r"v\d+\.\d+(?:\.\d+)?", new)) - set(re.findall(r"v\d+\.\d+(?:\.\d+)?", old))
    chk("新增版本号字面量只含本版号", vko <= {"v0.3.11"}, f"新增 {sorted(vko)}")
    chk("本版四行更正不提名牌夹具（无真实版号 / URL / 目标路径 / 游戏码）",
        not any(x in "".join([REWRITE_NEW[k] for k in ("§1 引导句 :49", "§15.1 首行 :932", "§7.2 B-13 :245", "§8.4 脚本注入 :318")])
                for x in ("dnf_tw", "dnf-tw", "http", ".zip")), "四处更正串已扫")

    # 6. 边界（提交级：--ref 给出候选提交时优先；否则退到工作树级）
    if ref and ref != OLD:
        g = lambda *a: subprocess.run(["git"] + list(a), capture_output=True, text=True).stdout.split()
        names = g("diff", "--name-only", OLD, ref)
        ncom = g("rev-list", "--count", f"{OLD}..{ref}")
        anc = subprocess.run(["git", "merge-base", "--is-ancestor", OLD, ref]).returncode == 0
        chk(f"边界（提交级）：`{OLD[:7]}..{ref[:7]}` 只触 design.md、恰 1 个提交、快进",
            set(names) == {DOC} and ncom == ["1"] and anc, f"{names} / 提交数 {ncom} / ancestor={anc}")

    for st, name, detail in results:
        print(f"[{st}] {name}" + (f"  — {detail}" if detail else ""))
    print(f"\n== {'反向对照' if ref else '本版核对'} ref={ref or CAND_FILE}  PASS {P} / FAIL {F} ==")
    sys.exit(1 if F else 0)


if __name__ == "__main__":
    main()
