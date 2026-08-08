#!/bin/bash
# ============================================
# Game Platform Manager - Frontend Status Script (Linux)
# Description: Check frontend server status
# Usage: ./status.sh
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

echo "=========================================="
echo "Game Platform Manager - Frontend Status"
echo "=========================================="
echo ""

# Check if PID file exists
if [ ! -f "$PID_FILE" ]; then
    echo -e "Status: ${RED}STOPPED${NC}"
    echo "PID File: Not found"
    echo ""
    echo "Service is not running."
    echo ""
    echo "=========================================="
    exit 0
fi

# Read PID from file
TARGET_PID=$(cat "$PID_FILE")

# Check if process is running
if ! ps -p "$TARGET_PID" > /dev/null 2>&1; then
    echo -e "Status: ${YELLOW}STOPPED (Stale PID file)${NC}"
    echo "PID File: $TARGET_PID (process not running)"
    echo ""
    echo "Service is not running. Cleaning up PID file..."
    rm -f "$PID_FILE"
    echo ""
    echo "=========================================="
    exit 0
fi

# Get process information
echo -e "Status: ${GREEN}RUNNING${NC}"
echo "PID: $TARGET_PID"
echo ""

# Get detailed process info
PROCESS_INFO=$(ps -p "$TARGET_PID" -o pid,ppid,user,%cpu,%mem,vsz,rss,stat,start,time,comm --no-headers 2>/dev/null)

if [ -n "$PROCESS_INFO" ]; then
    echo "Process Information:"
    echo "--------------------"
    echo "PID    PPID   USER     %CPU %MEM    VSZ      RSS  STAT  STARTED     TIME     COMMAND"
    echo "$PROCESS_INFO"
    echo ""
fi

# Get process command line
CMDLINE=$(cat /proc/$TARGET_PID/cmdline 2>/dev/null | tr '\0' ' ')
if [ -n "$CMDLINE" ]; then
    echo "Command Line:"
    echo "-------------"
    echo "$CMDLINE"
    echo ""
fi

# Check port usage
echo "Port Usage:"
echo "-----------"

# Check if lsof is available
if command -v lsof &> /dev/null; then
    PORT_INFO=$(lsof -i :5173 -i :4173 2>/dev/null | grep LISTEN || true)
    if [ -n "$PORT_INFO" ]; then
        echo "$PORT_INFO"
    else
        echo "No frontend ports (5173, 4173) are currently in use"
    fi
elif command -v netstat &> /dev/null; then
    PORT_INFO=$(netstat -tlnp 2>/dev/null | grep -E ':(5173|4173)' || true)
    if [ -n "$PORT_INFO" ]; then
        echo "$PORT_INFO"
    else
        echo "No frontend ports (5173, 4173) are currently in use"
    fi
else
    echo "Unable to check port usage (lsof/netstat not available)"
fi

echo ""

# Check recent log entries
LOG_FILE="$LOG_DIR/frontend.log"
if [ -f "$LOG_FILE" ]; then
    echo "Recent Log Entries:"
    echo "-------------------"
    tail -n 5 "$LOG_FILE"
    echo ""
fi

# Check nginx status if running
if pgrep -x "nginx" > /dev/null; then
    echo "nginx Status:"
    echo "-------------"
    pgrep -a nginx
    echo ""
fi

echo "=========================================="
