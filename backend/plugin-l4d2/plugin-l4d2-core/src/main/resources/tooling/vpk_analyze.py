#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L4D2 VPK map metadata parser.

The host distributes this plugin-owned script into the instance context and
returns stdout unchanged. The plugin interprets the resulting JSON.
"""
import json
import os
import re
import struct
import sys

VPK_SIGNATURE = 0x55AA1234
HEADER_SIZE = 12
TARGET_ROOT_ENV = "GP_TOOL_TARGET_ROOT"
SELF_ARCHIVE_INDEXES = {0x7FFF, 0xFFFF}
KV_PATTERN = re.compile(r'"([^"]+)"\s+"([^"]+)"')
GAME_MODES = {"coop", "survival", "halftank", "brawler", "versus", "scavenge", "realism"}


def target_path(path):
    root = os.environ.get(TARGET_ROOT_ENV)
    if not root or not path.startswith("/"):
        return path
    return os.path.join(root, path.lstrip("/"))


def read_null_terminated(buf, pos):
    end = buf.find(b"\x00", pos)
    if end == -1:
        raise ValueError("unterminated string in tree")
    return buf[pos:end].decode("utf-8", errors="replace"), end + 1


def parse_tree(buf, pos, end):
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
                    mission_refs.append((archive_index, offset, size))
                pos += preload_bytes
    return missions, mission_refs


def read_data_section(path, tree_start, tree_size, mission_refs):
    out = []
    if not mission_refs:
        return out
    with open(target_path(path), "rb") as f:
        f.seek(0, 2)
        file_len = f.tell()
        for archive_index, offset, size in mission_refs:
            if archive_index not in SELF_ARCHIVE_INDEXES:
                continue
            start = tree_start + tree_size + offset
            if start + size > file_len:
                continue
            f.seek(start)
            out.append(f.read(size).decode("utf-8", errors="replace"))
    return out


def parse_mission(text):
    campaign = {"title": None, "chapters": []}
    chapter_map = {}
    in_game_mode = False
    awaiting_mode_brace = False
    pending_mode = None
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

        mode_text = lower.replace("{", " ").strip().strip('"').strip("'")
        if not in_game_mode and mode_text in GAME_MODES and (
                lower.startswith('"') or lower.startswith("'") or "{" in line):
            in_game_mode = True
            awaiting_mode_brace = "{" not in line
            brace_level = line.count("{") - line.count("}")
            pending_mode = mode_text
            current_mode = mode_text if brace_level > 0 else None
            if brace_level > 0:
                pending_mode = None
            continue

        if awaiting_mode_brace:
            if "{" in line:
                awaiting_mode_brace = False
                brace_level = line.count("{") - line.count("}")
                current_mode = pending_mode
            else:
                continue

        for m in KV_PATTERN.finditer(line):
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
                    elif current_mode not in chapter["modes"]:
                        chapter["modes"].append(current_mode)
                    temp_map_name = None

        if in_game_mode:
            brace_level += line.count("{") - line.count("}")
            if brace_level <= 0:
                in_game_mode = False
                awaiting_mode_brace = False
                pending_mode = None
                current_mode = None
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
        with open(target_path(path), "rb") as f:
            head = f.read(HEADER_SIZE)
            if len(head) < HEADER_SIZE:
                print("file too small", file=sys.stderr)
                sys.exit(3)
            signature, version, tree_size = struct.unpack_from("<III", head, 0)
            if signature != VPK_SIGNATURE:
                print("not a VPK file (magic mismatch)", file=sys.stderr)
                sys.exit(2)
            tree_start = HEADER_SIZE + (16 if version >= 2 else 0)
            f.seek(tree_start)
            tree = f.read(tree_size)
    except OSError as e:
        print("read failed: %s" % e, file=sys.stderr)
        sys.exit(3)

    missions, mission_refs = parse_tree(tree, 0, len(tree))
    missions.extend(read_data_section(path, tree_start, tree_size, mission_refs))
    merged = None
    for text in missions:
        try:
            campaign = parse_mission(text)
        except Exception as e:
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
