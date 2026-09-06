#!/usr/bin/env bash
# 从本机 Steamcommunity 302 目录组装构建上下文并构建镜像（在 WSL/Linux 内运行）。
# 用法: build.sh [S302目录] [版本tag]
# 产物: gfw-302:<版本>（本地）+ registry.cn-shenzhen.aliyuncs.com/haki_hub/gfw-302:<版本>
# 推送: build.sh [S302目录] [版本tag] --push（需先 docker login 阿里云仓库）
set -euo pipefail

SRC="${1:-/home/haki/gfw/Steamcommunity_302}"
VERSION="${2:-15.0.4}"
PUSH="${3:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CTX="/tmp/steam302-build-context"
REGISTRY="registry.cn-shenzhen.aliyuncs.com/haki_hub/gfw-302"

rm -rf "$CTX"
mkdir -p "$CTX"
for f in steamcommunity_302.cli steamcommunity_302.caddy S302.ini S302_rules.ini S302.hosts; do
    cp "$SRC/$f" "$CTX/"
done
cp "$HERE/Dockerfile" "$HERE/entrypoint.sh" "$CTX/"
# 预置可执行位：Dockerfile 里不再 RUN chmod（传统构建器会把大文件整体复制进新层，镜像翻倍）
chmod +x "$CTX/steamcommunity_302.cli" "$CTX/steamcommunity_302.caddy" "$CTX/entrypoint.sh"

docker build -t "gfw-302:$VERSION" -t "$REGISTRY:$VERSION" "$CTX"
echo "built: gfw-302:$VERSION"

if [ "$PUSH" = "--push" ]; then
    docker push "$REGISTRY:$VERSION"
fi
