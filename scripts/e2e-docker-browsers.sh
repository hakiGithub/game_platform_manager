#!/usr/bin/env bash
#
# 在 WSL 内安装 Playwright 需要的 Chromium（一次性）。
#
# 为什么需要：WSL ~/.cache/ms-playwright 里预置的是 chromium-1208，
# 而 frontend/package.json 的 @playwright/test 1.61 需要 1228，
# 版本不匹配直接报 "Executable doesn't exist"。
#
# 实测：直连 cdn.playwright.dev 极慢（>20 分钟无进展），
# 用 npmmirror 镜像源后 27 秒完成。默认走镜像源，可用环境变量覆盖。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export NVM_DIR="${HOME}/.nvm"
# shellcheck disable=SC1091
[ -s "${NVM_DIR}/nvm.sh" ] && . "${NVM_DIR}/nvm.sh"
nvm use 24 >/dev/null 2>&1

export PLAYWRIGHT_DOWNLOAD_HOST="${PLAYWRIGHT_DOWNLOAD_HOST:-https://cdn.npmmirror.com/binaries/playwright}"

cd "${REPO_ROOT}/frontend" || exit 1
echo "[e2e-docker] node: $(node -v)"
echo "[e2e-docker] 镜像源: ${PLAYWRIGHT_DOWNLOAD_HOST}"
echo "[e2e-docker] 安装 Chromium（约 115 MB，一次性）..."
exec npx playwright install chromium
