#!/usr/bin/env bash
set -euo pipefail

# WClient website launcher
# Run this in your Codespaces/terminal to serve the site and get a public URL.

PORT=8080
WEB_DIR="$(cd "$(dirname "$0")" && pwd)"
CLOUDFLARED="/tmp/cloudflared"
SERVER_PID=""
TUNNEL_PID=""

cleanup() {
    echo "[stop] Stopping server and tunnel..."
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null || true
    exit 0
}

trap cleanup INT TERM EXIT

# Dependency checks
for dep in python3 curl; do
    command -v "$dep" >/dev/null 2>&1 || { echo "Error: $dep is required but not installed." >&2; exit 1; }
done

# Kill any stale instances tied to this port
pkill -f "python3 -m http.server $PORT" 2>/dev/null || true
sleep 1

echo "[1/4] Starting local server on port $PORT..."
cd "$WEB_DIR"
python3 -m http.server "$PORT" > /tmp/wclient-server.log 2>&1 &
SERVER_PID=$!

# Wait for server
READY=0
for i in {1..15}; do
    if curl -sI "http://localhost:$PORT/" | grep -q "200"; then
        READY=1
        break
    fi
    sleep 1
done

if [ "$READY" -ne 1 ]; then
    echo "Error: Local server did not start on port $PORT." >&2
    exit 1
fi

echo "[2/4] Local server is ready: http://localhost:$PORT"

# Download cloudflared if needed
if [ ! -x "$CLOUDFLARED" ]; then
    echo "[3/4] Downloading cloudflared..."
    curl -L -o "$CLOUDFLARED" "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
    chmod +x "$CLOUDFLARED"
fi

# Run tunnel in background and print the public URL
echo "[4/4] Starting public tunnel (Cloudflare)..."
$CLOUDFLARED tunnel --url "http://localhost:$PORT" > /tmp/wclient-tunnel.log 2>&1 &
TUNNEL_PID=$!

# Wait a moment then display the URL
sleep 6
PUBLIC_URL=$(grep -oP 'https://[a-zA-Z0-9.-]+\.trycloudflare\.com' /tmp/wclient-tunnel.log | head -1 || true)
if [ -n "$PUBLIC_URL" ]; then
    echo "Public URL: $PUBLIC_URL"
else
    echo "Tunnel is starting. You can get the URL from: cat /tmp/wclient-tunnel.log"
fi

# Keep script alive so the tunnel remains active
wait "$TUNNEL_PID"
