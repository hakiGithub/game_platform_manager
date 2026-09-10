#!/usr/bin/env bash
#
# deploy-image.sh —— 平台 Docker 镜像部署到指定主机（ADR-0012 插件挂载语义）
#
# 功能：
#   1. 同步源码上下文到目标主机（tar 管道，.dockerignore 同款排除清单）
#   2. 目标机构建镜像（game-platform-{backend,frontend}:<时间戳> + latest）
#   3. 同步插件 jar 到挂载目录（镜像不含插件，挂载注入——ADR-0012 决策 2）
#   4. docker compose up（docker-compose.deploy.yml，挂载目录参数化）
#   5. 镜像保留策略：目标机只保留最近 KEEP 个版本（默认 2），更早的 rmi
#   6. 部署后自动验证：容器 healthy / 前端可达 / 登录 API / 插件加载
#
# 用法：
#   scripts/deploy-image.sh                                 # 目标 = 本机 WSL（wsl.exe 直通）
#   scripts/deploy-image.sh --host wsl                      # 同上
#   scripts/deploy-image.sh --host ssh://user@192.168.1.10  # 通用 SSH 主机
#   scripts/deploy-image.sh --keep 2 --port 8081            # 可选项
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

TARGET="wsl"                 # wsl | ssh://user@host
KEEP=2                       # 目标机保留的版本化镜像个数（不含 latest）
FRONTEND_PORT=8081
DEPLOY_DIR_NAME="gpm-deploy" # 目标机 HOME 下的部署目录名

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) TARGET="$2"; shift 2 ;;
    --keep) KEEP="$2"; shift 2 ;;
    --db) DB_MODE="$2"; shift 2 ;;
    --port) FRONTEND_PORT="$2"; shift 2 ;;
    --deploy-dir) DEPLOY_DIR_NAME="$2"; shift 2 ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

IS_WSL=0
if [[ "$TARGET" == "wsl" ]]; then
  IS_WSL=1
elif [[ "$TARGET" != ssh://* ]]; then
  echo "--host 需为 wsl 或 ssh://user@host"; exit 1
fi
DB_MODE="${DB_MODE:-sqlite}"
case "$DB_MODE" in sqlite|mysql) ;; *) echo "--db 需为 sqlite 或 mysql"; exit 1 ;; esac

SSH_HOST="${TARGET#ssh://}"

# 在目标机执行命令（脚本经 stdin 传入，规避多层引号转义）
run_target_script() {
  if [[ "$IS_WSL" -eq 1 ]]; then
    wsl.exe -e bash -s
  else
    ssh "$SSH_HOST" bash -s
  fi
}

log() { echo "[deploy-image] $*"; }

COMPOSE_FILES="-f docker-compose.deploy.yml"
if [ "${DB_MODE:-sqlite}" = "mysql" ]; then
  COMPOSE_FILES="$COMPOSE_FILES -f docker-compose.mysql.deploy.yml --profile mysql"
fi

# ========== 0. 预检 ==========
command -v curl >/dev/null || { echo "缺少 curl"; exit 1; }
if [[ ! -f "$REPO_DIR/backend/plugins/plugin-l4d2-core-1.0.0-SNAPSHOT.jar" ]]; then
  log "警告: backend/plugins/ 下未发现 l4d2 插件 jar，部署后插件列表将为空"
fi

# ========== 1. 版本与目标机目录 ==========
VERSION=$(date +%Y%m%d-%H%M%S)
DEPLOY_DIR=$(run_target_script <<'EOF' | tr -d '\r'
echo "$HOME/gpm-deploy"
EOF
)
log "目标=$TARGET 版本=$VERSION 部署目录=$DEPLOY_DIR 保留最近=${KEEP} 版"

# ========== 2. 同步源码上下文（构建用；tar 管道直传，排除依赖产物与运行时数据） ==========
log "同步源码上下文..."
SYNC_EXCLUDES=(
  --exclude='backend/data' --exclude='backend/logs' --exclude='backend/plugins' \
  --exclude='backend/backups' --exclude='backend/storage' --exclude='backend/temp' \
  --exclude='backend/games' --exclude='backend/target' \
  --exclude='frontend/node_modules' --exclude='frontend/dist' \
  --exclude='frontend/coverage' --exclude='frontend/test-results' \
  --exclude='frontend/e2e/.tmp' --exclude='frontend/e2e/.artifacts' \
  --exclude='frontend/e2e/.report' --exclude='frontend/e2e/.env.local' \
  --exclude='.git' --exclude='.scratch' --exclude='.trae' --exclude='docs' \
  --exclude='*.log' --exclude='*.pid'
)
SYNC_FILES=(docker backend frontend .dockerignore)

if [[ "$IS_WSL" -eq 1 ]]; then
  tar -czf - -C "$REPO_DIR" "${SYNC_EXCLUDES[@]}" "${SYNC_FILES[@]}" \
    | wsl.exe -e bash -c "mkdir -p '$DEPLOY_DIR/src' && tar -xzf - -C '$DEPLOY_DIR/src'"
else
  tar -czf - -C "$REPO_DIR" "${SYNC_EXCLUDES[@]}" "${SYNC_FILES[@]}" \
    | ssh "$SSH_HOST" "mkdir -p '$DEPLOY_DIR/src' && tar -xzf - -C '$DEPLOY_DIR/src'"
fi
log "源码上下文同步完成"

# ========== 3. 准备挂载目录 + 同步插件 jar（ADR-0012 决策 2） ==========
run_target_script <<EOF
mkdir -p '$DEPLOY_DIR/data' '$DEPLOY_DIR/plugins' '$DEPLOY_DIR/backups' \
         '$DEPLOY_DIR/storage' '$DEPLOY_DIR/temp' '$DEPLOY_DIR/games' '$DEPLOY_DIR/logs'
EOF
log "同步插件 jar 到 $DEPLOY_DIR/plugins ..."
if [[ "$IS_WSL" -eq 1 ]]; then
  wsl.exe -e bash -c "cp -a /mnt/d/program/ai/game_platform_manger/backend/plugins/*.jar '$DEPLOY_DIR/plugins/' 2>/dev/null || echo '  (无插件 jar 可同步)'"
else
  for jar in "$REPO_DIR"/backend/plugins/*.jar; do
    [[ -e "$jar" ]] || continue
    cat "$jar" | ssh "$SSH_HOST" "cat > '$DEPLOY_DIR/plugins/$(basename "$jar")'"
  done
fi

# ========== 4. 目标机构建镜像（时间戳 tag + latest） ==========
log "构建镜像 game-platform-{backend,frontend}:$VERSION ..."
run_target_script <<EOF
cd '$DEPLOY_DIR/src/docker'
docker build -f Dockerfile.backend  -t game-platform-backend:$VERSION  -t game-platform-backend:latest  ../backend
docker build -f Dockerfile.frontend -t game-platform-frontend:$VERSION -t game-platform-frontend:latest ..
EOF

# ========== 5. 写 .env 并启动 ==========
# 探测目标机 compose 形态（v2 插件优先，回退 v1 独立二进制）
COMPOSE_CMD=$(run_target_script <<'EOF'
docker compose version >/dev/null 2>&1 && echo 'docker compose' || echo 'docker-compose'
EOF
)
COMPOSE_CMD=$(echo "$COMPOSE_CMD" | tr -d '' | tail -1)
run_target_script <<EOF
cat > '$DEPLOY_DIR/src/docker/.env' <<ENV
IMAGE_TAG=$VERSION
GPM_DEPLOY_DIR=$DEPLOY_DIR
FRONTEND_PORT=$FRONTEND_PORT
ENV
cd '$DEPLOY_DIR/src/docker'
# 先 down 再 up：compose v1 对新版 Docker 已有容器做 in-place 重建会撞
# ContainerConfig KeyError（bind mount 数据不受影响）
$COMPOSE_CMD -f docker-compose.deploy.yml down 2>/dev/null || true
$COMPOSE_FILES $COMPOSE_CMD up -d
$COMPOSE_FILES $COMPOSE_CMD ps
EOF

# ========== 6. 镜像保留策略：按版本 tag 只保留最近 KEEP 个 ==========
log "镜像保留策略：每组保留最近 $KEEP 个版本（latest 不计）"
run_target_script <<EOF
for repo in game-platform-backend game-platform-frontend; do
  tags=\$(docker images --format '{{.Tag}}' "\$repo" | grep -v latest | grep -v '<none>' | sort -r)
  echo "\$tags" | tail -n +$((KEEP + 1)) | while read -r t; do
    [ -n "\$t" ] && docker rmi "\$repo:\$t" 2>/dev/null || true
  done
done
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^game-platform-(backend|frontend)' | sort
EOF

# ========== 7. 部署后自动验证 ==========
log "验证部署（backend 健康检查最长 ~60s）..."
VERIFY_OK=1
ok()  { echo "  ✓ $1"; }
bad() { echo "  ✗ $1"; VERIFY_OK=0; }

# MySQL 模式：等待数据库 healthy（首个启动含 schema/data 初始化）
if [ "$DB_MODE" = "mysql" ]; then
  for i in $(seq 1 24); do
    H=$(run_target_script <<'EOF'
docker ps --filter "label=com.docker.compose.service=mysql" --format '{{.Status}}' | grep -o 'healthy|starting|unhealthy' | head -1
EOF
)
    [ "$H" = "healthy" ] && break
    log "  MySQL 状态: ${H:-unknown}，等待..."
    sleep 10
  done
  [ "$H" = "healthy" ] || { echo "  ✗ MySQL 未就绪"; exit 1; }
  ok "MySQL healthy"
fi

# 7.1 容器 healthy（backend 镜像自带 HEALTHCHECK，start-period 60s）
for i in $(seq 1 12); do
  HEALTH=$(run_target_script <<'EOF'
docker ps --filter "label=com.docker.compose.service=backend" \
  --format '{{.Status}}' | grep -o 'healthy\|starting\|unhealthy' | head -1
EOF
)
  [[ "$HEALTH" == "healthy" ]] && break
  log "  backend 健康状态: ${HEALTH:-unknown}，等待..."
  sleep 10
done
[[ "$HEALTH" == "healthy" ]] && ok "backend 容器 healthy" || bad "backend 容器未就绪（$HEALTH）"

# 7.2 前端可达（目标机内 curl nginx）
CODE=$(run_target_script <<EOF
curl -s -o /dev/null -w '%{http_code}' -m 5 http://localhost:$FRONTEND_PORT/
EOF
)
[[ "$CODE" == "200" ]] && ok "前端 http://localhost:$FRONTEND_PORT/ 可达" || bad "前端不可达 (http=$CODE)"

# 7.3 登录 API（经 nginx 8081 透传）
BODY=$(run_target_script <<EOF
curl -s -m 5 -X POST http://localhost:$FRONTEND_PORT/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
EOF
)
echo "$BODY" | grep -q '"code":200' && ok "登录 API 成功" || bad "登录 API 异常: $(echo "$BODY" | head -c 120)"

# 7.4 插件加载（l4d2 STARTED，验证插件目录挂载生效；接口需登录）
AUTH=$(run_target_script <<EOF
curl -s -m 5 -X POST http://localhost:$FRONTEND_PORT/api/auth/login   -H 'Content-Type: application/json'   -d '{"username":"admin","password":"admin123"}'
EOF
)
TOKEN=$(echo "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{try{console.log(JSON.parse(s).data.token||'')}catch{console.log('')}}")
PLUGINS=$(run_target_script <<EOF
curl -s -m 5 -H 'Authorization: Bearer $TOKEN' http://localhost:$FRONTEND_PORT/api/pf4j/plugins
EOF
)
echo "$PLUGINS" | grep -q 'l4d2' && echo "$PLUGINS" | grep -q 'STARTED' \
  && ok "插件 l4d2 已加载（STARTED）—— 插件目录挂载生效" \
  || bad "插件 l4d2 未加载"

echo "=========================================="
if [[ "$VERIFY_OK" -eq 1 ]]; then
  log "部署验证全部通过 ✓  访问: http://localhost:$FRONTEND_PORT/（admin/admin123）"
  exit 0
fi
log "部署验证存在失败项（目标机日志: $DEPLOY_DIR/logs/application.log）"
exit 1
