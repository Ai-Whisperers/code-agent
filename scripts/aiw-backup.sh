#!/bin/bash
# =============================================================================
# AIW Code Agent — postgres backup (Phase 6.3)
# =============================================================================
# Writes a gzipped pg_dump to /opt/aiw-backups/ daily via cron.
# Keeps 14 days; anything older is pruned.
#
# Install:
#   (crontab -l; echo '0 3 * * * /opt/aiw-code-agent/scripts/aiw-backup.sh >> /var/log/aiw-backup.log 2>&1') | crontab -
#
# Manual run:
#   /opt/aiw-code-agent/scripts/aiw-backup.sh

set -euo pipefail

BACKUP_DIR="${AIW_BACKUP_DIR:-/opt/aiw-backups}"
mkdir -p "$BACKUP_DIR"

DATE=$(date +%Y-%m-%d)
OUT="$BACKUP_DIR/code-agent-${DATE}.sql.gz"

CTID=$(docker ps -q --filter label=com.docker.swarm.service.name=aiw-code-agent_postgres)
if [ -z "$CTID" ]; then
  echo "$(date -Iseconds) ERROR: aiw-code-agent_postgres container not running" >&2
  exit 1
fi

docker exec "$CTID" pg_dump -U aiw_code_agent -d aiw_code_agent | gzip > "$OUT"
echo "$(date -Iseconds) backup ok: $OUT ($(du -h "$OUT" | cut -f1))"

# Prune older than 14 days
find "$BACKUP_DIR" -name "code-agent-*.sql.gz" -mtime +14 -delete
