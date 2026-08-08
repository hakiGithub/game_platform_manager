#!/bin/bash
# ============================================
# Game Platform Manager - Frontend Start Script (Linux)
# Description: Start frontend development server or production server
# Usage: ./start.sh [dev|prod] [port]
# ============================================

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$PROJECT_DIR/.frontend.pid"
DEFAULT_DEV_PORT=5173
DEFAULT_PROD_PORT=4173

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
MODE="${1:-dev}"
PORT="${2:-}"

# Set default port based on mode
if [ -z "$PORT" ]; then
    if [ "$MODE" = "prod" ]; then
        PORT=$DEFAULT_PROD_PORT
    else
        PORT=$DEFAULT_DEV_PORT
    fi
fi

# Create log directory if not exists
mkdir -p "$LOG_DIR"

# Log function
log() {
    local TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')
    local LOG_FILE="$LOG_DIR/frontend.log"
    echo "[$TIMESTAMP] $1" | tee -a "$LOG_FILE"
}

log_success() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')] $1${NC}" | tee -a "$LOG_DIR/frontend.log"
}

log_error() {
    echo -e "${RED}[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1${NC}" | tee -a "$LOG_DIR/frontend.log"
}

log_warn() {
    echo -e "${YELLOW}[$(date '+%Y-%m-%d %H:%M:%S')] WARNING: $1${NC}" | tee -a "$LOG_DIR/frontend.log"
}

# Check if service is already running
check_running() {
    if [ -f "$PID_FILE" ]; then
        local EXISTING_PID=$(cat "$PID_FILE")
        if ps -p "$EXISTING_PID" > /dev/null 2>&1; then
            log "Service is already running with PID $EXISTING_PID"
            return 0
        else
            log "Stale PID file found, cleaning up..."
            rm -f "$PID_FILE"
        fi
    fi
    return 1
}

# Start development server
start_dev() {
    log "Starting development server on port $PORT..."

    cd "$PROJECT_DIR"

    # Check if node_modules exists
    if [ ! -d "node_modules" ]; then
        log "Installing dependencies..."
        npm install
        if [ $? -ne 0 ]; then
            log_error "Failed to install dependencies"
            exit 1
        fi
    fi

    # Start Vite dev server in background
    local LOG_FILE="$LOG_DIR/dev-server.log"

    # Use nohup to run in background
    nohup npm run dev -- --port "$PORT" > "$LOG_FILE" 2>&1 &

    # Get PID
    local NODE_PID=$!

    # Wait for server to start
    sleep 3

    # Verify process is running
    if ps -p "$NODE_PID" > /dev/null 2>&1; then
        echo "$NODE_PID" > "$PID_FILE"
        log_success "Development server started successfully with PID $NODE_PID on port $PORT"
        log "Access URL: http://localhost:$PORT"
    else
        log_error "Failed to start development server. Check $LOG_FILE for details."
        exit 1
    fi
}

# Start production server
start_prod() {
    log "Starting production server on port $PORT..."

    cd "$PROJECT_DIR"

    # Check if dist directory exists
    if [ ! -d "dist" ]; then
        log "Building production files..."
        npm run build
        if [ $? -ne 0 ]; then
            log_error "Failed to build production files"
            exit 1
        fi
    fi

    # Check if serve is installed
    if ! command -v serve &> /dev/null; then
        log "Installing serve package..."
        npm install -g serve
    fi

    # Start static file server
    local LOG_FILE="$LOG_DIR/prod-server.log"

    nohup serve -s dist -l "$PORT" > "$LOG_FILE" 2>&1 &

    local SERVE_PID=$!

    sleep 2

    if ps -p "$SERVE_PID" > /dev/null 2>&1; then
        echo "$SERVE_PID" > "$PID_FILE"
        log_success "Production server started successfully with PID $SERVE_PID on port $PORT"
        log "Access URL: http://localhost:$PORT"
    else
        log_error "Failed to start production server. Check $LOG_FILE for details."
        exit 1
    fi
}

# Start with nginx (alternative production mode)
start_nginx() {
    log "Starting with nginx..."

    # Check if nginx is installed
    if ! command -v nginx &> /dev/null; then
        log_error "nginx is not installed. Please install nginx first."
        exit 1
    fi

    cd "$PROJECT_DIR"

    # Build if dist doesn't exist
    if [ ! -d "dist" ]; then
        log "Building production files..."
        npm run build
        if [ $? -ne 0 ]; then
            log_error "Failed to build production files"
            exit 1
        fi
    fi

    # Create nginx config if not exists
    local NGINX_CONF="$PROJECT_DIR/nginx.conf"
    if [ ! -f "$NGINX_CONF" ]; then
        log_warn "nginx.conf not found. Creating default configuration..."
        cat > "$NGINX_CONF" << 'EOF'
server {
    listen 80;
    server_name localhost;
    root /path/to/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # WebSocket support
    location /ws {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
    }
}
EOF
        log_warn "Please edit $NGINX_CONF with correct paths and restart"
    fi

    # Start nginx
    nginx -c "$NGINX_CONF"
    if [ $? -eq 0 ]; then
        log_success "nginx started successfully"
    else
        log_error "Failed to start nginx"
        exit 1
    fi
}

# Main execution
log "=========================================="
log "Game Platform Manager - Frontend Start"
log "Mode: $MODE"
log "Port: $PORT"
log "=========================================="

if check_running; then
    exit 1
fi

case "$MODE" in
    prod)
        start_prod
        ;;
    nginx)
        start_nginx
        ;;
    dev|*)
        start_dev
        ;;
esac

log "Frontend server started successfully"
