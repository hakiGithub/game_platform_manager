"""v0.3.11 载体核对：本批写进 design.md 的每一条代码事实，逐行对着实物核（不采信任何自述）。

mode="line" → 该行号上必须含全部 needles；mode="file" → 全文必须含每个 needle。
对 main / release 头 / MERC-20 交付头三个 ref 各核（交付类只在后两者存在）。

用法: python _v0311/carrier-v0311.py
"""
import subprocess
import sys

REFS = {"m": "3c5df3e",
        "r": "origin/release/MERC-8-extension-steps-dnf-tw",
        "2": "origin/agent/backenddev/merc-20"}
C = "backend/core/src/main/java/com/gameplatform/"
SDK = "backend/plugin/src/main/java/com/gameplatform/plugin/service/"

# (label, path, refs, mode, line, needles)
FACTS = [
    # — 本批第一族：§1 引导句 :49 与 §15.1 首行 :932 更正所依据的代码事实 —
    ("FileAccessService:254 三参重载声明（无默认值语义）", f"{SDK}FileAccessService.java", "mr", "line", 254,
     ["CommandResult executeCommand(Long hostId, String command, long timeoutMs);"]),
    ("FileAccessService:263 两参 default 重载在位", f"{SDK}FileAccessService.java", "mr", "line", 263,
     ["default CommandResult executeCommand(Long hostId, String command)"]),
    ("FileAccessService:264 字面量 30_000L（§15.1 首行引用的那枚值）", f"{SDK}FileAccessService.java", "mr", "line", 264,
     ["return executeCommand(hostId, command, 30_000L);"]),
    ("SshUtil:356 timeoutMs 流向 getOrCreateSession", f"{C}util/SshUtil.java", "mr", "line", 356,
     ["getOrCreateSession", "timeoutMs"]),
    ("SshUtil:135 getOrCreateSession 签名收 timeoutMs", f"{C}util/SshUtil.java", "mr", "line", 135, ["long timeoutMs"]),
    ("SshUtil:152 该值只喂 connect（建连与认证）", f"{C}util/SshUtil.java", "mr", "line", 152,
     ["connect(getSharedClient()", "timeoutMs"]),
    ("SshUtil:368 executeRemoteCommand 不吃超时参数", f"{C}util/SshUtil.java", "mr", "line", 368,
     ["executeRemoteCommand(command, stdout, stderr"]),
    ("SshUtil:368 该行不含任何 timeout 实参", f"{C}util/SshUtil.java", "mr", "negline", 368, ["imeout"]),
    # — 第二族：§7.2 B-13 :245 与 §8.4 :318 补齐 timeout 前缀所依据的既有形状 —
    ("DockerComposeAdapter:260 仓内同形先例（timeout 1200 + 命令文本）", f"{C}adapter/DockerComposeAdapter.java", "mr", "line", 260,
     ["timeout 1200"]),
    ("DockerComposeAdapter:257 现场注释逐字写明同一事实", f"{C}adapter/DockerComposeAdapter.java", "mr", "line", 257,
     ["timeoutMs 仅作用于建连", "命令执行无超时"]),
    ("LinuxGsmDockerAdapter:223 仓内同形先例（timeout 1200）", f"{C}adapter/LinuxGsmDockerAdapter.java", "mr", "line", 223,
     ["timeout 1200"]),
    ("LinuxGsmDockerAdapter:221 现场注释逐字写明同一事实", f"{C}adapter/LinuxGsmDockerAdapter.java", "mr", "line", 221,
     ["仅作用于建连", "命令执行无超时"]),
    ("已交付护栏 = timeout <秒> bash <file>", f"{C}deploy/ExtensionScriptRunner.java", "r2", "line", 96,
     ['"timeout " + shellSeconds + " bash "']),
    ("交付实现自带「而不是裸 bash <file>」的说明", f"{C}deploy/ExtensionScriptRunner.java", "r2", "line", 34,
     ["而不是裸"]),
    ("秒粒度向上取整 + 下界 1 s", f"{C}deploy/ExtensionScriptRunner.java", "r2", "line", 175,
     ["Math.max(1L", "(timeoutMs + 999L) / 1000L"]),
    ("超时按远端被杀时刻识别（124 ∧ elapsed）", f"{C}deploy/ExtensionScriptRunner.java", "r2", "line", 103,
     ["getExitCode() == SHELL_TIMEOUT_EXIT_CODE", "shellSeconds * 1000L"]),
    ("SIGTERM 不保证远端进程已终止（§15.3 结论仍成立的实物依据）", f"{C}deploy/ExtensionScriptRunner.java", "r2", "line", 30,
     ["SIGTERM"]),
    # — §15.1 表内与本行相邻的其余锚点（本批未动，逐条复核它们仍与实物对得上） —
    ("PatchInstallExecutor:54 补丁链路 SSH_TIMEOUT_MS = 600_000L", f"{C}patch/PatchInstallExecutor.java", "mr", "line", 54,
     ["SSH_TIMEOUT_MS = 600_000L"]),
    ("DeployTaskHandler:50 任务中心 30 min", f"{C}task/DeployTaskHandler.java", "mr", "line", 50,
     ["DEFAULT_TIMEOUT_MS = 30 * 60 * 1000L"]),
    # 本文的行号引注一律钉在 main（判定基线）上；release 头因 MERC-20 的 +53 行插入而同一条码下移，
    # 故 :728 只核 main，另核 release / 交付头上的实际落点 :781 是否同一语句（防「行号漂了 ⇒ 事实变了」的误读）。
    ("DockerComposeAdapter:728（main）compose exec 60 s 硬编码 = 本文引注", f"{C}adapter/DockerComposeAdapter.java", "m", "line", 728, ["60000"]),
    ("同一语句在 release / 交付头下移到 :781（行号漂移、事实未变）", f"{C}adapter/DockerComposeAdapter.java", "r2", "line", 781, ["60000"]),
    ("DockerAdapter:476 docker exec 60 s 硬编码", f"{C}adapter/DockerAdapter.java", "mr", "line", 476, ["60000"]),
]


def show(ref, path):
    r = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True)
    if r.returncode:
        return None
    return r.stdout.decode("utf-8").replace("\r\n", "\n").split("\n")


P = F = 0
for label, path, which, mode, ln, needles in FACTS:
    ok, detail = True, []
    for tag in which:
        ref = REFS[tag]
        lines = show(ref, path)
        if lines is None:
            ok = False
            detail.append(f"{tag}:无文件")
            continue
        if mode in ("line", "negline"):
            row = lines[ln - 1] if ln <= len(lines) else ""
            if mode == "line":
                hit = all(n in row for n in needles)
            else:
                hit = row and all(n not in row for n in needles)
            if not hit:
                ok = False
                detail.append(f"{tag}:{ln} 行不符 → {row.strip()[:90]!r}")
        else:
            txt = "\n".join(lines)
            for n in needles:
                if n not in txt:
                    ok = False
                    detail.append(f"{tag}:全文缺 {n!r}")
    P += 1 if ok else 0
    F += 0 if ok else 1
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + (f"  — {'; '.join(detail)}" if detail else ""))

print(f"\n== 载体核对  PASS {P} / FAIL {F}  （{len(FACTS)} 条代码事实 × main/release/交付头）==")
sys.exit(1 if F else 0)
