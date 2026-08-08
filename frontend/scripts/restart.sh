#!/bin/bash
# ============================================
# Game Platform Manager - Frontend Restart Script (Linux)
# Description: Restart frontend server
# Usage: ./restart.sh [dev|prod] [port]
# ============================================

set -e

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOG_DIR="$PROJECT_DIR/logs"

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

# Parse arguments
MODE="${1:-dev}"
PORT="${2:-}"

# Main execution
log "=========================================="
log "Game Platform Manager - Frontend Restart"
log "=========================================="

# Stop the service
log "Stopping frontend server..."
"$SCRIPT_DIR/stop.sh"

# Wait for complete shutdown
sleep 2

# Start the service
log "Starting frontend server..."

# Build command arguments
START_CMD="$SCRIPT_DIR/start.sh"
if [ -n "$MODE" ]; then
    START_CMD="$START_CMD $MODE"
fi
if [ -n "$PORT" ]; then
    START_CMD="$START_CMD $PORT"
fi

$START_CMD

if [ $? -ne 0 ]; then
    log_error "Failed to start frontend server"
    exit 1
fi

log_success "Frontend server restarted successfully"
