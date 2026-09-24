"""v0.3.10 载体核对：本批写进 design.md 的每一条代码事实，逐行对着实物核（不采信任何自述）。

mode="line" → 该行号上必须含全部 needles；mode="file" → 全文必须含每个 needle。
对 main / release 头 / MERC-20 交付头三个 ref 各核（新增类只在交付头上存在）。
"""
import subprocess
import sys

REFS = {"main": "3c5df3e",
        "release": "origin/release/MERC-8-extension-steps-dnf-tw",
        "merc20": "origin/agent/backenddev/merc-20"}
C = "backend/core/src/main/java/com/gameplatform/"
SP = "backend/plugin/src/main/java/com/gameplatform/plugin/"
SDK = "backend/plugin/src/main/java/com/gameplatform/plugin/service/"

# (label, path, refs, mode, line, needles)
FACTS = [
    ("SshUtil:356 建连吃 timeoutMs", f"{C}util/SshUtil.java", "mrl", "line", 356, ["getOrCreateSession", "timeoutMs"]),
    ("SshUtil:368 命令执行不吃超时", f"{C}util/SshUtil.java", "mrl", "line", 368, ["executeRemoteCommand"]),
    ("FileAccessService:254 三参重载在位", f"{SDK}FileAccessService.java", "mrl", "line", 254, ["executeCommand(Long hostId, String command, long timeoutMs)"]),
    ("TaskMutexManager:52 同值即判占用成功", f"{C}task/TaskMutexManager.java", "mrl", "line", 52, ["return existing.equals(taskId);"]),
    ("TaskMutexManager:68 按值 CAS 删除", f"{C}task/TaskMutexManager.java", "mrl", "line", 68, ["mutexMap.remove(mutexKey, taskId);"]),
    ("DockerComposeAdapter:260 现场已用 shell timeout", f"{C}adapter/DockerComposeAdapter.java", "mrl", "line", 260, ["timeout 1200"]),
    ("LinuxGsmDockerAdapter:223 现场已用 shell timeout", f"{C}adapter/LinuxGsmDockerAdapter.java", "mrl", "line", 223, ["timeout 1200"]),
    ("compose 现场注释写明「仅作用于建连」", f"{C}adapter/DockerComposeAdapter.java", "mrl", "file", None, ["仅作用于建连"]),
    ("lgsm 现场注释写明同一事实", f"{C}adapter/LinuxGsmDockerAdapter.java", "mrl", "file", None, ["仅作用于建连"]),
    ("护栏 = timeout <秒> bash <file>", f"{C}deploy/ExtensionScriptRunner.java", "2", "file", None, ['"timeout " + shellSeconds + " bash "']),
    ("秒粒度向上取整 + 下界 1 s", f"{C}deploy/ExtensionScriptRunner.java", "2", "file", None, ["(timeoutMs + 999L) / 1000L", "Math.max(1L"]),
    ("超时按远端被杀时刻识别（124 ∧ elapsed）", f"{C}deploy/ExtensionScriptRunner.java", "2", "line", 103,
     ["result.getExitCode() == SHELL_TIMEOUT_EXIT_CODE", "shellSeconds * 1000L"]),
    ("清理 = 删自建脚本文件", f"{C}deploy/ExtensionScriptRunner.java", "2", "file", None, ["deleteFile(hostId, remotePath)"]),
    ("清理 = 再尝试 rmdir（非 rm -rf）", f"{C}deploy/ExtensionScriptRunner.java", "2", "file", None, ['"rmdir " + shellQuote(remoteDir)']),
    ("holder = EXT:<instanceId>:<进程内自增>", f"{C}patch/PatchInstallServiceImpl.java", "2", "line", 123,
     ['MUTEX_HOLDER_PREFIX + request.getInstanceId() + ":" + syncSequence.incrementAndGet()']),
    ("holder 前缀仍是 EXT:", f"{C}patch/PatchInstallServiceImpl.java", "2", "file", None, ['MUTEX_HOLDER_PREFIX = "EXT:"']),
    ("installSync 定稿签名两参（无步骤序号位）", f"{SP}patch/PatchInstallService.java", "2", "file",
     None, ["void installSync(PatchInstallRequest request, PatchInstallProgressListener listener)"]),
]


def show(ref, path):
    r = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True)
    if r.returncode:
        return None
    return r.stdout.decode("utf-8").replace("\r\n", "\n").split("\n")


P, F = 0, 0
for label, path, which, mode, ln, needles in FACTS:
    refs = [("m" in which and REFS["main"]), ("r" in which and REFS["release"]), ("2" in which and REFS["merc20"])]
    refs = [r for r in refs if r]
    ok, detail = True, []
    for ref in refs:
        lines = show(ref, path)
        if lines is None:
            ok = False
            detail.append(f"{ref.split('/')[-1][:14]}:无文件")
            continue
        if mode == "line":
            hit = ln <= len(lines) and all(n in lines[ln - 1] for n in needles)
        else:
            blob = "\n".join(lines)
            hit = all(n in blob for n in needles)
        ok &= hit
        detail.append(f"{ref.split('/')[-1][:14]}={'ok' if hit else 'MISS'}")
    P += 1 if ok else 0
    F += 0 if ok else 1
    print(f"[{'PASS' if ok else 'FAIL'}] {label}  — {'; '.join(detail)}")

print(f"\n== carrier 核对  PASS {P} / FAIL {F} ==")
sys.exit(1 if F else 0)
