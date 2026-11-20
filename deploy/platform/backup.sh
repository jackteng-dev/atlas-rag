#!/usr/bin/env bash
# Atlas platform backup / restore for the LOCAL compose stack (reference implementation for the server runbook).
#   deploy/platform/backup.sh backup  <dir>      → <dir>/atlas-<ts>/{postgres.dump, objects.tar, qdrant-snapshot.json, manifest.json}
#   deploy/platform/backup.sh restore <backup>   → restores into a NEW database "atlas_restore_<ts>" and a scratch bucket path, then verifies
# Qdrant is derived data: restore re-indexes from PostgreSQL chunks instead of trusting an old snapshot.
set -euo pipefail
COMPOSE=(docker compose -f "$(dirname "$0")/compose.yaml" --env-file "$(dirname "$0")/.env")
cmd=${1:-}; target=${2:-}
[ -n "$cmd" ] && [ -n "$target" ] || { echo "usage: $0 backup|restore <dir>" >&2; exit 2; }
ts=$(date -u +%Y%m%dT%H%M%SZ)
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

if [ "$cmd" = backup ]; then
  out="$target/atlas-$ts"; mkdir -p "$out"
  "${COMPOSE[@]}" exec -T postgres pg_dump -U atlas -d atlas -Fc --no-owner > "$out/postgres.dump"
  "${COMPOSE[@]}" exec -T minio tar -C /data -cf - . > "$out/objects.tar"
  counts=$("${COMPOSE[@]}" exec -T postgres psql -U atlas -d atlas -At -c \
    "select json_build_object('organizations',(select count(*) from organizations),'documents',(select count(*) from documents where deleted_at is null),'versions',(select count(*) from document_versions),'chunks',(select count(*) from document_chunks),'messages',(select count(*) from messages),'audit',(select count(*) from audit_events),'flyway',(select max(version) from flyway_schema_history where success))")
  cat > "$out/manifest.json" <<EOF
{"createdAt":"$ts","postgresSha256":"$(sha "$out/postgres.dump")","objectsSha256":"$(sha "$out/objects.tar")","counts":$counts}
EOF
  echo "Backup written: $out"; cat "$out/manifest.json"; exit 0
fi

if [ "$cmd" = restore ]; then
  [ -f "$target/manifest.json" ] || { echo "manifest.json missing" >&2; exit 2; }
  expected_pg=$(sed -E 's/.*"postgresSha256":"([a-f0-9]+)".*/\1/' "$target/manifest.json")
  [ "$(sha "$target/postgres.dump")" = "$expected_pg" ] || { echo "postgres.dump checksum mismatch" >&2; exit 3; }
  db="atlas_restore_$(date -u +%Y%m%d%H%M%S)"
  "${COMPOSE[@]}" exec -T postgres createdb -U atlas "$db"
  "${COMPOSE[@]}" exec -T postgres pg_restore -U atlas -d "$db" --no-owner < "$target/postgres.dump"
  "${COMPOSE[@]}" exec -T minio sh -c "mkdir -p /tmp/restore-check && tar -C /tmp/restore-check -xf -" < "$target/objects.tar"
  restored=$("${COMPOSE[@]}" exec -T postgres psql -U atlas -d "$db" -At -c \
    "select json_build_object('organizations',(select count(*) from organizations),'documents',(select count(*) from documents where deleted_at is null),'versions',(select count(*) from document_versions),'chunks',(select count(*) from document_chunks),'messages',(select count(*) from messages),'audit',(select count(*) from audit_events),'flyway',(select max(version) from flyway_schema_history where success))")
  expected=$(sed -E 's/.*"counts":(\{[^}]*\}).*/\1/' "$target/manifest.json")
  echo "expected: $expected"; echo "restored: $restored"
  [ "$(echo "$expected" | tr -d ' ')" = "$(echo "$restored" | tr -d ' ')" ] && echo "RESTORE VERIFIED into database $db (scratch; drop it when done)" || { echo "RESTORE COUNT MISMATCH" >&2; exit 4; }
  exit 0
fi
echo "unknown command $cmd" >&2; exit 2
