#!/usr/bin/env bash
set -euo pipefail

# WClient website launcher
# Run this in your Codespaces/terminal to serve the site and get a public URL.

PORT=8080
WEB_DIR="$(cd "$(dirname "$0")" && pwd)"
CLOUDFLARED="/tmp/cloudflared"

# Kill previous instances (if any)
pkill -f "python3 -m http.server $PORT" 2>/dev/null || true
pkill -f "$CLOUDFLARED tunnel" 2>/dev/null || true
sleep 1

echo "[1/4] Starting local server on port $PORT..."
cd "$WEB_DIR"
python3 -m http.server "$PORT" > /tmp/wclient-server.log 2>&1 &
SERVER_PID=$!

# Ensure server is stopped when this script exits
trap 'echo "[stop] Stopping server (PID $SERVER_PID) and tunnel..."; kill $SERVER_PID 2>/dev/null || true; pkill -f "$CLOUDFLARED tunnel" 2>/dev/null || true; exit 0' INT TERM EXIT

# Wait for server
for i in {1..15}; do
    if curl -sI "http://localhost:$PORT/" | grep -q "200"; then
        echo "[2/4] Local server is ready: http://localhost:$PORT"
        break
    fi
    sleep 1
done

# Download cloudflared if needed
if [ ! -x "$CLOUDFLARED" ]; then
    echo "[3/4] Downloading cloudflared..."
    curl -L -o "$CLOUDFLARED" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
    chmod +x "$CLOUDFLARED"
fi

# Run tunnel and print the public URL
echo "[4/4] Starting public tunnel (Cloudflare)..."
$CLOUDFLARED tunnel --url "http://localhost:$PORT" 2>&1 | tee /tmp/wclient-tunnel.log
