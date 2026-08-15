#!/bin/bash
#
# L4D2 插件内置资源包下载脚本
#
# 背景：builtin-plugins 目录下的插件包（约 118MB）
# 已移出 git 跟踪（见 .gitignore），改为从 GitHub Release 资产下载。
# 构建 plugin-l4d2 前需先执行本脚本。
#
# 用法：
#   1. 准备资产包：在 GitHub 仓库 Releases 创建一个 release（tag 如 assets-v1），
#      上传打包后的 l4d2-plugin-assets.tar.gz（内容为 plugin-l4d2-core/
#      src/main/resources/builtin-plugins/ 目录）
#   2. ./scripts/download-plugin-assets.sh
#
# 环境变量：
#   ASSET_RELEASE_TAG  要下载的 release tag（默认 assets-v1）
#   ASSET_URL          直接指定资产下载 URL（优先级高于 tag）
#

set -e

REPO="${GITHUB_REPOSITORY:-hakiGithub/game_platform_manager}"
TAG="${ASSET_RELEASE_TAG:-assets-v1}"
TARGET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/plugin-l4d2-core/src/main/resources"
ASSET_NAME="l4d2-plugin-assets.tar.gz"

if [ -n "${ASSET_URL}" ]; then
  URL="${ASSET_URL}"
else
  URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET_NAME}"
fi

echo "下载插件资源包: ${URL}"
echo "目标目录: ${TARGET_DIR}"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

if command -v curl >/dev/null 2>&1; then
  curl -fL -o "${WORK}/${ASSET_NAME}" "${URL}"
elif command -v wget >/dev/null 2>&1; then
  wget -O "${WORK}/${ASSET_NAME}" "${URL}"
else
  echo "错误: 需要 curl 或 wget" >&2
  exit 1
fi

tar -xzf "${WORK}/${ASSET_NAME}" -C "${TARGET_DIR}"
echo "完成: builtin-plugins/ 已就位"
