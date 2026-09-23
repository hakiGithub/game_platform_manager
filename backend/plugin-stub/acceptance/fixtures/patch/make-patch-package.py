#!/usr/bin/env python3
"""构建验收用受控补丁包（design.md §3.3 组 K / PRD §8.6「验收夹具的内容边界」）。

产物 stub-version-marker.zip 是桩插件版本目录里 PATCH 步骤的落位来源，
只服务框架验证，不是任何游戏的真实资料（BR-15 ①②）。

字节级确定性：固定 date_time、不写 extra 字段、按 NAME 排序入包 ⇒
重复构建得到同一个 sha256，桩插件声明里的摘要才不会与包体脱钩。
"""
import hashlib
import zipfile
from pathlib import Path

OUT = Path(__file__).parent / "stub-version-marker.zip"
FIXED_DATE = (2026, 1, 1, 0, 0, 0)

ENTRIES = {
    "payload/version.txt": "stub-version=2.0.0\n",
    "payload/sub/config.txt": "stub-config=acceptance-only\n",
    "payload/notes.md": "# 本文件不在 includePattern=*.txt 内，用于核对产物筛选生效\n",
}


def main() -> None:
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for name in sorted(ENTRIES):
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, ENTRIES[name])
    digest = hashlib.sha256(OUT.read_bytes()).hexdigest()
    print(f"{OUT.name}  bytes={OUT.stat().st_size}  sha256={digest}")


if __name__ == "__main__":
    main()
