#!/usr/bin/env python3
"""Regression tests for vpk_analyze.py, built on Python stdlib only."""

import importlib.util
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[3] / "main" / "resources" / "tooling" / "vpk_analyze.py"
SPEC = importlib.util.spec_from_file_location("vpk_analyze", SCRIPT)
VPK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VPK)


def mission_text() -> bytes:
    return b'''"mission"
{
  "displaytitle" "Test Campaign"
  "coop" {
    "1" { "map" "test_map_1" "displayname" "Chapter 1" }
  }
  "versus"
  {
    "1" { "map" "test_map_1" "displayname" "Chapter 1" }
  }
}
'''


def make_vpk(path: Path, version: int, preload: bool, tail_bytes: int = 0,
             archive_index: int = 0xFFFF) -> None:
    text = mission_text()
    inline = text if preload else b""
    tree_size = len(b"txt\0missions\0test\0") + 18 + len(inline) + 3
    header_size = 12 + (16 if version >= 2 else 0)
    with path.open("wb") as f:
        f.write(struct.pack("<III", 0x55AA1234, version, tree_size))
        if version >= 2:
            f.write(struct.pack("<IIII", 0, 0, 0, 0))
        f.write(b"txt\0missions\0test\0")
        f.write(struct.pack("<IHHIIH", 0, len(inline), archive_index, 0,
                           0 if preload else len(text), 0))
        f.write(inline)
        f.write(b"\0\0\0")
        if not preload:
            f.write(text)
        # A sparse tail proves header-only parsing scales to large VPK files.
        if tail_bytes:
            f.seek(tail_bytes, 2)
            f.write(b"\0")


class VpkAnalyzeTest(unittest.TestCase):
    def test_inline_mission_v2_and_real_mode_syntax(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "inline.vpk"
            make_vpk(path, 2, True)
            with path.open("rb") as f:
                _, version, tree_size = struct.unpack("<III", f.read(12))
                self.assertEqual(2, version)
                f.seek(28)
                tree = f.read(tree_size)
            missions, refs = VPK.parse_tree(tree, 0, len(tree))
            self.assertEqual(1, len(missions))
            self.assertEqual([], refs)
            parsed = VPK.parse_mission(missions[0])
            self.assertEqual("Test Campaign", parsed["title"])
            self.assertEqual(["coop", "versus"], parsed["chapters"][0]["modes"])

    def test_cli_inline_mission_v2(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "inline.vpk"
            make_vpk(path, 2, True)
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(path)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            payload = json.loads(result.stdout)
            self.assertEqual("Test Campaign", payload["title"])
            self.assertEqual(["test_map_1"], [c["code"] for c in payload["chapters"]])

    def test_data_section_mission_and_large_tail_is_not_read(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "data.vpk"
            make_vpk(path, 1, False, tail_bytes=512 * 1024 * 1024)
            with path.open("rb") as f:
                head = f.read(12)
            _, version, tree_size = struct.unpack("<III", head)
            with path.open("rb") as f:
                f.seek(12)
                tree = f.read(tree_size)
            missions, refs = VPK.parse_tree(tree, 0, len(tree))
            self.assertEqual([], missions)
            self.assertEqual([(0xFFFF, 0, len(mission_text()))], refs)
            parsed = VPK.parse_mission(VPK.read_data_section(path, 12, tree_size, refs)[0])
            self.assertEqual("Test Campaign", parsed["title"])
            self.assertGreater(path.stat().st_size, 512 * 1024 * 1024)

    def test_data_section_uses_mounted_target_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            target_root = Path(tmp) / "target-root"
            vpk_path = target_root / "l4d2" / "left4dead2" / "addons" / "data.vpk"
            vpk_path.parent.mkdir(parents=True)
            make_vpk(vpk_path, 1, False)
            with vpk_path.open("rb") as f:
                _, _, tree_size = struct.unpack("<III", f.read(12))
                f.seek(12)
                tree = f.read(tree_size)
            _, refs = VPK.parse_tree(tree, 0, len(tree))

            with patch.dict(os.environ, {VPK.TARGET_ROOT_ENV: str(target_root)}):
                parsed = VPK.parse_mission(
                    VPK.read_data_section(
                        "/l4d2/left4dead2/addons/data.vpk",
                        12,
                        tree_size,
                        refs,
                    )[0]
                )

            self.assertEqual("Test Campaign", parsed["title"])
            self.assertEqual(["test_map_1"], [c["code"] for c in parsed["chapters"]])

    def test_data_section_accepts_0x7fff_self_archive_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "data-7fff.vpk"
            make_vpk(path, 1, False, archive_index=0x7FFF)
            with path.open("rb") as f:
                _, _, tree_size = struct.unpack("<III", f.read(12))
                f.seek(12)
                tree = f.read(tree_size)
            _, refs = VPK.parse_tree(tree, 0, len(tree))
            self.assertEqual([(0x7FFF, 0, len(mission_text()))], refs)
            parsed = VPK.parse_mission(
                VPK.read_data_section(path, 12, tree_size, refs)[0]
            )
            self.assertEqual("Test Campaign", parsed["title"])


if __name__ == "__main__":
    unittest.main()
