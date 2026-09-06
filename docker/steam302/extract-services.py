#!/usr/bin/env python3
"""逐开关生成 Caddyfile，提取 服务开关 → 域名清单 映射。

用法（在解包了 Steamcommunity 302 的目录所在机器上运行）：
  python3 extract-services.py <S302目录> <输出JSON路径>

原理：将 S302_rules.ini 的 enabled 与 S302.ini 的功能键都关到只剩一个键，
短启动 steamcommunity_302.cli 让它生成 Caddyfile，再解析站点块域名。
仅用于离线生成静态映射，运行时平台不执行此脚本。
"""
import base64
import json
import os
import re
import shutil
import subprocess
import sys

# [Rules] enabled 列表的完整键序（与 S302_rules.ini 保持一致）
ALL_KEYS = [
    "Steam_store", "Steam_store_unlock", "Steam_community", "Steam_API",
    "Steam_API_unlock", "Steam_community_unlock", "steamchat", "steamchat_unlock",
    "baishan2ali", "workshop_fix", "imgfix", "imgfix_fastly",
    "Steam_cloud_google", "steam_update", "Steam_broadcast_redir",
    "Steam_broadcast_redir_unlock", "EA_CloudSync", "EA_Download_Akamai",
    "recaptcha", "discord", "twitch", "modio", "minecraft", "github", "vercel",
    "huggingface", "huggingface_download", "artstation", "pinterest",
    "blockbench", "fandom_imgfix", "imgur", "megaionz", "onedrive", "jsdelivr",
    "googleapis_ajax", "greasyfork", "docker", "chrome_translate", "Scholar",
    "parsec", "gamespot", "Yandex_disk", "Dropbox", "pixeldrain", "Spotify",
    "Fallout76_respond", "Epic_DL_redir", "Xbox_DL_redir", "Uplay_DL_redir",
    "csgo_demo_redir", "Monster_hunter_wilds", "GDevelop", "Gravatar",
]

CADDYFILE = "steamcommunity_302.caddy.json"
SITE_BLOCK = re.compile(r"(?m)^((?:https?://\S+[ \t]+)*https?://\S+)[ \t]*\{")


def main(src_dir: str, out_path: str) -> None:
    work = "/tmp/s302_extract"
    result = {}
    for key in ALL_KEYS:
        shutil.rmtree(work, ignore_errors=True)
        os.makedirs(work)
        for f in ["steamcommunity_302.cli", "steamcommunity_302.caddy",
                  "S302.ini", "S302_rules.ini", "dns_hosts.txt", "dns_blacklist.txt"]:
            shutil.copy(os.path.join(src_dir, f), work)
        os.chmod(os.path.join(work, "steamcommunity_302.cli"), 0o755)
        os.chmod(os.path.join(work, "steamcommunity_302.caddy"), 0o755)

        rules = open(f"{work}/S302_rules.ini", encoding="utf-8").read()
        rules = re.sub(r"(enabled\s*=\s*)\S+", r"\1" + key, rules, count=1)
        open(f"{work}/S302_rules.ini", "w", encoding="utf-8").write(rules)

        ini = open(f"{work}/S302.ini", encoding="utf-8").read()
        for k in ALL_KEYS:
            ini = re.sub(rf"(?m)^{k}\s*=.*$", f"{k} = 0", ini)
        ini = re.sub(rf"(?m)^({key})\s*=.*$", rf"{key} = 1", ini, count=1)
        ini = re.sub(r"(?m)^Auto_Modify_Hosts\s*=.*$", "Auto_Modify_Hosts            = 0", ini)
        ini = re.sub(r"(?m)^AutoUpdate\s*=.*$", "AutoUpdate                  = 0", ini)
        open(f"{work}/S302.ini", "w", encoding="utf-8").write(ini)

        subprocess.run(["timeout", "6", "./steamcommunity_302.cli"],
                       cwd=work, capture_output=True)
        cf = open(f"{work}/{CADDYFILE}", encoding="utf-8").read()
        domains = set()
        for block in SITE_BLOCK.findall(cf):
            for site in block.split():
                # https://api.github.com:21900 -> api.github.com，剔除通配与端口
                host = site.split("://", 1)[1].split(":")[0].rstrip(".")
                if host and "*" not in host:
                    domains.add(host)
        result[key] = sorted(domains)
        print(f"{key}: {len(result[key])} domains")

    shutil.rmtree(work, ignore_errors=True)
    json.dump(result, open(out_path, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1, sort_keys=True)
    total = sum(len(v) for v in result.values())
    print(f"total {total} domains -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: extract-services.py <S302目录> <输出JSON>")
    main(sys.argv[1], sys.argv[2])
