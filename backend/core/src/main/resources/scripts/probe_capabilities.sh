#!/bin/sh
# 宿主机能力探测脚本（ADR-0006 决策 3，ADR-0021 扩展）
# 返回格式契约：JSON 一行，字段 osType/hostname/arch/currentUser/tools{...}/tmpFreeKb
#               /packageManager/docker/sudoNopasswd（ADR-0021 新增后四者为可选，缺失容错）
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
unrar=$(has_cmd unrar)
seven_zip=$(has_cmd 7z)

tmp_free_kb=$(df -Pk /tmp 2>/dev/null | awk 'NR==2 {print $4}')

# 包管理器探测（ADR-0021 决策 2）：按优先级取第一个存在的
package_manager=""
for pm in apt dnf yum apk pacman zypper; do
  if command -v "$pm" >/dev/null 2>&1; then
    package_manager="$pm"
    break
  fi
done

# Docker 可用性（ADR-0021 决策 6）：命令存在且守护进程可访问
docker_available="false"
if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then
    docker_available="true"
  fi
fi

# 提权能力（ADR-0021 决策 4）：root 或免密 sudo
sudo_nopasswd="false"
if [ "$(id -u 2>/dev/null)" = "0" ]; then
  sudo_nopasswd="true"
elif sudo -n true >/dev/null 2>&1; then
  sudo_nopasswd="true"
fi

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
    "rsync": ${rsync},
    "unrar": ${unrar},
    "7z": ${seven_zip}
  },
  "tmpFreeKb": ${tmp_free_kb},
  "packageManager": "${package_manager}",
  "docker": ${docker_available},
  "sudoNopasswd": ${sudo_nopasswd}
}
EOF
