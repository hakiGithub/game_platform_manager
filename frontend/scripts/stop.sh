#!/bin/bash
# ============================================
# Game Platform Manager - Frontend Stop Script (Linux)
# Description: Stop frontend server
# Usage: ./stop.sh
# ============================================

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$PROJECT_DIR/.frontend.pid"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

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

# Stop process by PID
stop_process() {
    local TARGET_PID=$1
    local PROCESS_NAME=$2

    if ! ps -p "$TARGET_PID" > /dev/null 2>&1; then
        log "Process $PROCESS_NAME with PID $TARGET_PID is not running"
        return 0
    fi

    log "Stopping $PROCESS_NAME with PID $TARGET_PID..."

    # Try graceful shutdown first (SIGTERM)
    kill "$TARGET_PID" 2>/dev/null || true

    # Wait for process to terminate
    local COUNTER=0
    local MAX_WAIT=10

    while ps -p "$TARGET_PID" > /dev/null 2>&1 && [ $COUNTER -lt $MAX_WAIT ]; do
        sleep 1
        COUNTER=$((COUNTER + 1))
    done

    # If still running, force kill (SIGKILL)
    if ps -p "$TARGET_PID" > /dev/null 2>&1; then
        log_warn "Process did not stop gracefully, forcing termination..."
        kill -9 "$TARGET_PID" 2>/dev/null || true
        sleep 1
    fi

    # Verify process stopped
    if ps -p "$TARGET_PID" > /dev/null 2>&1; then
        log_error "Failed to stop process with PID $TARGET_PID"
        return 1
    else
        log_success "$PROCESS_NAME stopped successfully"
        return 0
    fi
}

# Stop nginx if running
stop_nginx() {
    if pgrep -x "nginx" > /dev/null; then
        log "Stopping nginx..."
        nginx -s stop 2>/dev/null || true

        # Wait and force kill if needed
        sleep 2
        if pgrep -x "nginx" > /dev/null; then
            pkill -9 nginx 2>/dev/null || true
        fi

        log_success "nginx stopped"
    fi
}

# Main execution
log "=========================================="
log "Game Platform Manager - Frontend Stop"
log "=========================================="

# Check if PID file exists
if [ ! -f "$PID_FILE" ]; then
    log "No PID file found. Service may not be running."

    # Try to find and kill node processes related to frontend
    log "Attempting to find and stop any running frontend processes..."

    # Find node processes that might be frontend servers
    local NODE_PIDS=$(pgrep -f "vite|serve" 2>/dev/null || true)

    if [ -n "$NODE_PIDS" ]; then
        for PID in $NODE_PIDS; do
            stop_process "$PID" "frontend process"
        done
    fi

    # Also stop nginx if running
    stop_nginx

    exit 0
fi

# Read PID from file
TARGET_PID=$(cat "$PID_FILE")

# Stop the process
stop_process "$TARGET_PID" "frontend server"

# Clean up PID file
rm -f "$PID_FILE"

# Also stop nginx if it was started
stop_nginx

log_success "Frontend server stopped successfully"
