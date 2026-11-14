#!/usr/bin/env bash
# Better Battle launcher for macOS / Linux. Same commands as bb.cmd:
#   ./bb.sh [start|stop|restart|logs|status|seed [guild]|snapshot|update|backup|restore <file>|backup-daily [off]|reset]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_PATH="$SCRIPT_DIR/$(basename "$0")"
cd "$SCRIPT_DIR"
URL="http://localhost:4000"
CMD="${1:-start}"

backup_daily() {
  local marker="# Better Battle backup"
  if [[ "${1:-}" == "off" ]]; then
    if ! command -v crontab >/dev/null; then
      echo "crontab is not installed; remove the Better Battle backup line manually."
      return 1
    fi
    local current filtered
    current="$(crontab -l 2>/dev/null || true)"
    if ! printf '%s\n' "$current" | grep -Fq "$marker"; then
      echo "No Better Battle backup schedule found."
      return 0
    fi
    filtered="$(printf '%s\n' "$current" | grep -Fv "$marker" || true)"
    if ! printf '%s\n' "$filtered" | crontab -; then
      echo "Could not remove the Better Battle backup schedule." >&2
      return 1
    fi
    echo "Daily backup schedule removed."
    return 0
  fi
  printf 'Add this line to your crontab with crontab -e:\n0 4 * * * cd %q && %q backup %s\n' "$SCRIPT_DIR" "$SCRIPT_PATH" "$marker"
}

if [[ "$CMD" == "backup-daily" ]]; then
  backup_daily "${2:-}"
  exit
fi

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

check_database() {
  if ! docker compose exec -T db pg_isready -U bb -d betterbattle >/dev/null 2>&1 </dev/null; then
    echo "The database container is not running or is not ready. Run bb start first." >&2
    return 1
  fi
}

rotate_backups() {
  local -a dumps
  shopt -s nullglob
  dumps=(backups/*.dump)
  shopt -u nullglob
  if (( ${#dumps[@]} > 14 )); then
    while IFS= read -r old; do
      rm -f "$old"
    done < <(ls -t "${dumps[@]}" | tail -n +15)
  fi
}

backup_database() {
  check_database || return 1
  mkdir -p backups
  local stamp destination size
  stamp="$(date +%Y%m%d-%H%M%S)"
  destination="backups/betterbattle-${stamp}.dump"
  if ! docker compose exec -T db pg_dump -U bb -d betterbattle -Fc -f /tmp/bb.dump; then
    docker compose exec -T db rm -f /tmp/bb.dump >/dev/null 2>&1 || true
    echo "Database backup failed." >&2
    return 1
  fi
  if ! docker compose cp db:/tmp/bb.dump "$destination"; then
    docker compose exec -T db rm -f /tmp/bb.dump >/dev/null 2>&1 || true
    echo "Could not copy the database dump to $destination." >&2
    return 1
  fi
  if ! docker compose exec -T db rm -f /tmp/bb.dump; then
    echo "Warning: backup was copied, but /tmp/bb.dump could not be removed from the database container." >&2
  fi
  rotate_backups
  size="$(wc -c < "$destination" | tr -d '[:space:]')"
  BACKUP_PATH="$destination"
  printf 'Backup saved to %s (%s bytes).\n' "$destination" "$size"
}

restore_failure() {
  echo "$1" >&2
  docker compose exec -T db rm -f /tmp/restore.dump >/dev/null 2>&1 || true
  if ! docker compose start app; then
    echo "The app could not be restarted; run bb start." >&2
  fi
}

restore_database() {
  local source="${1:-}" safety_backup
  if [[ -z "$source" ]]; then
    echo "Usage: bb restore <file>" >&2
    return 1
  fi
  if [[ ! -f "$source" ]]; then
    echo "Backup file not found: $source" >&2
    return 1
  fi
  check_database || return 1
  read -rp "WARNING: This replaces all current database data. Type RESTORE to continue: " answer
  if [[ "$answer" != "RESTORE" ]]; then
    echo "Cancelled."
    return 0
  fi
  if ! docker compose stop app; then
    echo "Could not stop the app; restore cancelled." >&2
    return 1
  fi
  if ! docker compose cp "$source" db:/tmp/restore.dump; then
    restore_failure "Could not copy the backup into the database container."
    return 1
  fi
  if ! docker compose exec -T db pg_restore -l /tmp/restore.dump >/dev/null; then
    restore_failure "The backup file is invalid; the current database was not changed."
    return 1
  fi
  if ! backup_database; then
    restore_failure "Could not create a safety backup; the current database was not changed."
    return 1
  fi
  safety_backup="$BACKUP_PATH"
  printf 'Safety backup of the current data: %s\n' "$safety_backup"
  if ! docker compose exec -T db psql -U bb -d postgres -v ON_ERROR_STOP=1 \
    -c "DROP DATABASE betterbattle WITH (FORCE)" -c "CREATE DATABASE betterbattle"; then
    restore_failure "Could not recreate the betterbattle database."
    return 1
  fi
  if ! docker compose exec -T db psql -U bb -d betterbattle -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS timescaledb" -c "SELECT timescaledb_pre_restore()"; then
    restore_failure "TimescaleDB pre-restore setup failed."
    return 1
  fi
  local pg_restore_status=0
  if docker compose exec -T db pg_restore -U bb -d betterbattle --no-owner /tmp/restore.dump; then
    :
  else
    pg_restore_status=$?
    echo "pg_restore exited with status $pg_restore_status; continuing to timescaledb_post_restore(). Review its output above." >&2
  fi
  if ! docker compose exec -T db psql -U bb -d betterbattle -v ON_ERROR_STOP=1 \
    -c "SELECT timescaledb_post_restore()"; then
    restore_failure "TimescaleDB post-restore setup failed."
    return 1
  fi
  if ! docker compose exec -T db rm -f /tmp/restore.dump; then
    restore_failure "Could not remove /tmp/restore.dump from the database container."
    return 1
  fi
  if ! docker compose start app; then
    echo "The database was restored, but the app could not be restarted. Run bb start." >&2
    return 1
  fi
  if (( pg_restore_status != 0 )); then
    echo "Restore ran timescaledb_post_restore(), but pg_restore reported errors; review the output above." >&2
    return 1
  fi
  echo "Database restore completed."
}

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
  backup)   backup_database ;;
  restore)  restore_database "${2:-}" ;;
  reset)
    read -rp "This deletes ALL data. Type DELETE to confirm: " A
    [[ "$A" == "DELETE" ]] && docker compose down -v && echo "All data deleted." || echo "Cancelled." ;;
  *) echo "Unknown command: $CMD"; exit 1 ;;
esac
