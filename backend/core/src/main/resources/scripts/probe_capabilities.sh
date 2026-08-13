#!/bin/sh
# 宿主机能力探测脚本（ADR-0006 决策 3）
# 返回格式契约：JSON 一行，字段 osType/hostname/arch/currentUser/tools{...}/tmpFreeKb
# 仅在宿主机执行；容器内不执行探测。

has_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    echo "true"
  else
    echo "false"
  fi
}

os_type="linux"
hostname=$(hostname 2>/dev/null || echo "")
arch=$(uname -m 2>/dev/null || echo "")
current_user=$(whoami 2>/dev/null || echo "")

curl=$(has_cmd curl)
wget=$(has_cmd wget)
tar=$(has_cmd tar)
gzip=$(has_cmd gzip)
bzip2=$(has_cmd bzip2)
xz=$(has_cmd xz)
unzip=$(has_cmd unzip)
bsdtar=$(has_cmd bsdtar)
sha256sum=$(has_cmd sha256sum)
shasum=$(has_cmd shasum)
rsync=$(has_cmd rsync)

tmp_free_kb=$(df -Pk /tmp 2>/dev/null | awk 'NR==2 {print $4}')

cat <<EOF
{
  "osType": "${os_type}",
  "hostname": "${hostname}",
  "arch": "${arch}",
  "currentUser": "${current_user}",
  "tools": {
    "curl": ${curl},
    "wget": ${wget},
    "tar": ${tar},
    "gzip": ${gzip},
    "bzip2": ${bzip2},
    "xz": ${xz},
    "unzip": ${unzip},
    "bsdtar": ${bsdtar},
    "sha256sum": ${sha256sum},
    "shasum": ${shasum},
    "rsync": ${rsync}
  },
  "tmpFreeKb": ${tmp_free_kb}
}
EOF
