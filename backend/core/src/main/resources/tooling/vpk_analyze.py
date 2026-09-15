#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VPK 地图分析脚本（ADR-0027，由平台分发到主机临时目录执行）。

只读取 VPK 文件头部（header + 目录树，mission 内容内联在 preload 区），
不读取文件数据区——400MB 的 VPK 只读前几百 KB。

用法: python3 vpk_analyze.py <file.vpk>
输出: 单行 JSON {"title": "...", "chapters": [{"code","title","modes":[...]}]}
退出码: 0=解析成功; 2=VPK 有效但无 missions（非 L4D2 地图）; 3=非 VPK/解析失败

解析逻辑与插件侧 Java VpkParser 保持一致（v2 头部额外跳过 16 字节扩展字段）。
"""
import json
import re
import struct
import sys

VPK_SIGNATURE = 0x55AA1234
HEADER_SIZE = 12
KV_PATTERN = re.compile(r'"([^"]+)"\s+"([^"]+)"')
GAME_MODES = {"coop", "survival", "halftank", "brawler", "versus", "scavenge", "realism"}


def read_null_terminated(buf, pos):
    end = buf.find(b"\x00", pos)
    if end == -1:
        raise ValueError("unterminated string in tree")
    return buf[pos:end].decode("utf-8", errors="replace"), end + 1


def parse_tree(buf, pos, end):
    """遍历 VPK 目录树。

    返回 (missions_preload, mission_refs)：
    - missions_preload: preload 内联的 mission 文本列表
    - mission_refs: 内容在数据区的 mission 条目 [(archive_index, offset, size)]
      （不少作者 VPK 的 mission txt 不内联，需要按偏移补读数据区）
    """
    missions = []
    mission_refs = []
    while pos < end:
        ext, pos = read_null_terminated(buf, pos)
        if not ext:
            break
        while pos < end:
            path, pos = read_null_terminated(buf, pos)
            if not path:
                break
            while pos < end:
                filename, pos = read_null_terminated(buf, pos)
                if not filename:
                    break
                # crc(u32) preloadBytes(u16) archiveIndex(u16) offset(u32) size(u32) terminator(u16)
                if end - pos < 18:
                    return missions, mission_refs
                crc, preload_bytes, archive_index, offset, size, term = struct.unpack_from(
                    "<IHHIIH", buf, pos)
                pos += 18
                full = (path + "/" + filename + "." + ext).lower()
                is_mission = "missions/" in full and full.endswith(".txt")
                if preload_bytes > 0:
                    preload = buf[pos:pos + preload_bytes]
                    if is_mission:
                        missions.append(preload.decode("utf-8", errors="replace"))
                elif is_mission and size > 0:
                    # 0x7FFF = 数据在本文件内（自包含 VPK）
                    mission_refs.append((archive_index, offset, size))
                pos += preload_bytes
    return missions, mission_refs


def read_data_section(path, header_size, tree_size, mission_refs):
    """按数据区偏移读取 mission 内容（自包含 VPK：数据区紧随目录树）。"""
    out = []
    if not mission_refs:
        return out
    with open(path, "rb") as f:
        f.seek(0, 2)
        file_len = f.tell()
        for archive_index, offset, size in mission_refs:
            if archive_index != 0x7FFF:
                continue  # 多卷归档的数据在 archive_NNN.vpk，暂不支持
            start = header_size + tree_size + offset
            if start + size > file_len:
                continue
            f.seek(start)
            out.append(f.read(size).decode("utf-8", errors="replace"))
    return out


def parse_mission(text):
    """逐行解析 mission（Valve KeyValues），返回 {title, chapters:[...]}。移植自 Java parseMissionFile。"""
    campaign = {"title": None, "chapters": []}
    chapter_map = {}
    in_game_mode = False
    brace_level = 0
    temp_map_name = None
    current_mode = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("//"):
            continue
        idx = line.find("//")
        if idx != -1:
            line = line[:idx].strip()
        if not line:
            continue
        lower = line.lower()

        if not in_game_mode and lower.strip('"') in GAME_MODES and lower.startswith('"'):
            in_game_mode = True
            brace_level = 0
            current_mode = lower.strip('"')
            continue

        if in_game_mode:
            for ch in line:
                if ch == "{":
                    brace_level += 1
                elif ch == "}":
                    brace_level -= 1
            if brace_level <= 0:
                in_game_mode = False
                current_mode = None
                continue

        m = KV_PATTERN.search(line)
        if not m:
            continue
        key = m.group(1).lower()
        value = m.group(2)

        if key == "displaytitle" and campaign["title"] is None:
            campaign["title"] = value

        if in_game_mode and current_mode:
            if key == "map":
                temp_map_name = value
            if key == "displayname" and temp_map_name:
                chapter = chapter_map.get(temp_map_name)
                if chapter is None:
                    chapter = {"code": temp_map_name, "title": value, "modes": [current_mode]}
                    campaign["chapters"].append(chapter)
                    chapter_map[temp_map_name] = chapter
                else:
                    if current_mode not in chapter["modes"]:
                        chapter["modes"].append(current_mode)
                temp_map_name = None

    return campaign


def merge(a, b):
    if a["title"] is None:
        a["title"] = b["title"]
    by_code = {c["code"]: c for c in a["chapters"]}
    for ch in b["chapters"]:
        exist = by_code.get(ch["code"])
        if exist is None:
            a["chapters"].append(ch)
            by_code[ch["code"]] = ch
        else:
            for mode in ch["modes"]:
                if mode not in exist["modes"]:
                    exist["modes"].append(mode)
            if not exist.get("title"):
                exist["title"] = ch.get("title")
    return a


def main():
    if len(sys.argv) < 2:
        print("usage: vpk_analyze.py <file.vpk>", file=sys.stderr)
        sys.exit(3)
    path = sys.argv[1]
    try:
        with open(path, "rb") as f:
            head = f.read(HEADER_SIZE)
            if len(head) < HEADER_SIZE:
                print("file too small", file=sys.stderr)
                sys.exit(3)
            signature, version, tree_size = struct.unpack_from("<III", head, 0)
            if signature != VPK_SIGNATURE:
                print("not a VPK file (magic mismatch)", file=sys.stderr)
                sys.exit(2)
            # v2 头部含 4 个额外 u32（fileData/MD5/signature section 大小），目录树从 28 字节起
            tree_start = HEADER_SIZE + (16 if version >= 2 else 0)
            f.seek(tree_start)
            tree = f.read(tree_size)
    except OSError as e:
        print("read failed: %s" % e, file=sys.stderr)
        sys.exit(3)

    missions, mission_refs = parse_tree(tree, 0, len(tree))
    data_start = tree_start
    missions.extend(read_data_section(path, data_start, tree_size, mission_refs))
    merged = None
    for text in missions:
        try:
            campaign = parse_mission(text)
        except Exception as e:  # 单个 mission 损坏不阻塞其余
            print("mission parse warning: %s" % e, file=sys.stderr)
            continue
        if campaign["chapters"] or campaign["title"]:
            merged = campaign if merged is None else merge(merged, campaign)

    if merged is None:
        print("no valid mission found in VPK", file=sys.stderr)
        sys.exit(2)

    json.dump(merged, sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
