#!/usr/bin/env bash
# Better Battle launcher for macOS / Linux. Same commands as bb.cmd:
#   ./bb.sh [start|stop|restart|logs|status|seed [guild]|snapshot|update|reset]
set -euo pipefail
cd "$(dirname "$0")"
URL="http://localhost:4000"
CMD="${1:-start}"

if ! command -v docker >/dev/null; then
  echo "Docker is not installed: https://www.docker.com/products/docker-desktop/"; exit 1
fi
if ! docker info >/dev/null 2>&1; then
  [[ "$(uname)" == "Darwin" ]] && open -a Docker 2>/dev/null || true
  echo "Waiting for Docker to start..."
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 3; done
  docker info >/dev/null 2>&1 || { echo "Docker is not running. Start it and try again."; exit 1; }
fi

if [[ ! -f .env ]]; then
  echo "First-time setup: no .env file yet."
  read -rp "Paste your Hypixel API key: " KEY
  PW=$(LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 32)
  printf 'DB_PASSWORD=%s\nHYPIXEL_API_KEY=%s\nHYPIXEL_RATE=280\n' "$PW" "$KEY" > .env
  echo "Saved .env with a random database password."
fi
grep -q '^HYPIXEL_API_KEY=.\+' .env || { echo ".env has no HYPIXEL_API_KEY"; exit 1; }
grep -q '^DB_PASSWORD=.\+' .env || echo "DB_PASSWORD=$(LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c 32)" >> .env

case "$CMD" in
  start)
    docker compose up -d --build
    echo "Waiting for the site to come up..."
    for _ in $(seq 1 60); do
      if curl -sf -o /dev/null "$URL/bb/v1/health"; then
        echo "Better Battle is running at $URL"
        (command -v open >/dev/null && open "$URL") || (command -v xdg-open >/dev/null && xdg-open "$URL") || true
        exit 0
      fi
      sleep 2
    done
    echo "The app did not answer after 2 minutes. Logs:"; docker compose logs --tail 60 app; exit 1 ;;
  stop)     docker compose stop ;;
  restart)  docker compose up -d --force-recreate app ;;
  logs|log) docker compose logs -f --tail 100 app ;;
  status)   docker compose ps ;;
  seed)     docker compose exec app npm run seed-guild -- "${2:-Aspiring}" ;;
  snapshot) docker compose exec app npm run snapshot ;;
  update)   docker compose up -d --build ;;
  reset)
    read -rp "This deletes ALL data. Type DELETE to confirm: " A
    [[ "$A" == "DELETE" ]] && docker compose down -v && echo "All data deleted." || echo "Cancelled." ;;
  *) echo "Unknown command: $CMD"; exit 1 ;;
esac
