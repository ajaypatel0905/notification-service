#!/usr/bin/env bash
# Drives the service end to end against the `local` profile (embedded Postgres + seeded tenants).
# Usage: ./mvnw spring-boot:run -Dspring-boot.run.profiles=local   (in another terminal)
#        scripts/demo.sh
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
ADMIN="X-API-Key: local-admin-key"
ACME="X-API-Key: acme-tenant-key-000000"
GLOBEX="X-API-Key: globex-tenant-key-0000"
J="Content-Type: application/json"

jqp() { python3 -c 'import sys,json; d=json.load(sys.stdin); print(json.dumps(d, indent=2))'; }
field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }
step() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }

read -r -d '' PY_TENANTS <<'EOP' || true
import sys, json
for t in json.load(sys.stdin):
    print(f"  {t['slug']:8} {t['status']:9} {t['effectiveRateLimitPerSecond']}/s burst {t['effectiveRateLimitBurst']}")
EOP
read -r -d '' PY_TEMPLATES <<'EOP' || true
import sys, json
for t in json.load(sys.stdin):
    print(f"  {t['code']:14} {t['channel']:6} v{t['version']} vars={t['placeholders']}")
EOP
read -r -d '' PY_DETAIL <<'EOP' || true
import sys, json
d = json.load(sys.stdin); n = d["notification"]
print(f"  {n['channel']:6} {n['status']:9} attempts={n['attemptCount']} err={n.get('lastError')}")
for a in d["attempts"]:
    print(f"      attempt {a['attemptNo']}: {a['outcome']} {a.get('errorCode') or ''} ({a['durationMs']}ms)")
for e in d["events"]:
    print(f"      event   {e['type']:16} {e.get('fromStatus') or '-':10} -> {e.get('toStatus') or '-':10} {e.get('detail') or ''}")
EOP

step "Health"
curl -sf "$BASE/actuator/health"; echo

step "Platform admin: tenants and global limits"
curl -sf -H "$ADMIN" "$BASE/api/v1/admin/tenants" | python3 -c "$PY_TENANTS"
curl -sf -H "$ADMIN" "$BASE/api/v1/admin/settings" | jqp

step "RBAC: tenant key on an admin route is 403, no key is 401"
curl -s -o /dev/null -w "  tenant->admin: %{http_code}\n" -H "$ACME" "$BASE/api/v1/admin/tenants"
curl -s -o /dev/null -w "  no key:        %{http_code}\n" "$BASE/api/v1/templates"

step "Templates: placeholders and a strict render preview"
curl -sf -H "$ACME" "$BASE/api/v1/templates" | python3 -c "$PY_TEMPLATES"
printf '  missing variable -> '; curl -s -H "$ACME" -H "$J" -X POST "$BASE/api/v1/templates/welcome/EMAIL/render" -d '{"variables":{"company":"Acme"}}' | field '["detail"]'

step "Send a templated email with an idempotency key (202), then repeat it (200, same id)"
BODY='{"channel":"EMAIL","recipient":"jane@example.com","templateCode":"welcome","variables":{"company":"Acme","user":{"name":"Jane"},"plan":"Pro"}}'
R1=$(curl -s -H "$ACME" -H "$J" -H "Idempotency-Key: demo-welcome-jane" -X POST "$BASE/api/v1/notifications" -d "$BODY" -w '\n%{http_code}')
R2=$(curl -s -H "$ACME" -H "$J" -H "Idempotency-Key: demo-welcome-jane" -X POST "$BASE/api/v1/notifications" -d "$BODY" -w '\n%{http_code}')
ID_EMAIL=$(printf '%s' "$R1" | head -n1 | field '["id"]')
echo "  first : $(printf '%s' "$R1" | tail -n1)  id=$ID_EMAIL"
echo "  second: $(printf '%s' "$R2" | tail -n1)  id=$(printf '%s' "$R2" | head -n1 | field '["id"]')"

step "Steerable failures: SMS that times out twice, push that hard-bounces, in-app that lands in an inbox"
ID_SMS=$(curl -s -H "$ACME" -H "$J" -X POST "$BASE/api/v1/notifications" -d '{"channel":"SMS","recipient":"+919999+transient2","templateCode":"otp","variables":{"code":"482913","company":"Acme","minutes":10}}' | field '["id"]')
ID_PUSH=$(curl -s -H "$ACME" -H "$J" -X POST "$BASE/api/v1/notifications" -d '{"channel":"PUSH","recipient":"device-token+bounce","subject":"Order 42 shipped","body":"Your order is on its way"}' | field '["id"]')
ID_INAPP=$(curl -s -H "$ACME" -H "$J" -X POST "$BASE/api/v1/notifications" -d '{"channel":"IN_APP","recipient":"user-42","templateCode":"announcement","variables":{"title":"New feature","body":"Dark mode is here"}}' | field '["id"]')

step "Schedule one for later and cancel it"
LATER=$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ"))')
ID_SCHED=$(curl -s -H "$ACME" -H "$J" -X POST "$BASE/api/v1/notifications" -d "{\"channel\":\"EMAIL\",\"recipient\":\"later@example.com\",\"subject\":\"Reminder\",\"body\":\"See you soon\",\"scheduledAt\":\"$LATER\"}" | field '["id"]')
printf '  scheduled -> '; curl -s -H "$ACME" "$BASE/api/v1/notifications/$ID_SCHED" | field '["notification"]["status"]'
printf '  cancel    -> '; curl -s -H "$ACME" -X POST "$BASE/api/v1/notifications/$ID_SCHED/cancel" | field '["status"]'

step "Rate limiting and fairness: globex (5/s) floods 40 SMS while acme sends 5; watch who finishes first"
python3 - "$BASE" <<'PY'
import json, sys, urllib.request
base = sys.argv[1]
def post(key, body):
    req = urllib.request.Request(base + "/api/v1/notifications/batch", data=json.dumps(body).encode(),
                                 headers={"X-API-Key": key, "Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))
g = post("globex-tenant-key-0000", {"notifications": [{"channel": "SMS", "recipient": f"+91800000{i:04d}", "body": f"flood {i}"} for i in range(40)]})
a = post("acme-tenant-key-000000", {"notifications": [{"channel": "SMS", "recipient": f"+91700000{i:04d}", "body": f"otp {i}"} for i in range(5)]})
print(f"  globex accepted {g['accepted']}, acme accepted {a['accepted']}")
PY
sleep 4
for who in "$GLOBEX" "$ACME"; do
  curl -s -H "$who" "$BASE/api/v1/notifications/status-counts" | python3 -c 'import sys,json; print("  ", json.load(sys.stdin))'
done
echo "  (globex is still draining at 5/s while acme's 5 went straight through; run again in a few seconds to see globex finish)"

step "Wait for the earlier sends to settle"
sleep 3
for id in $ID_EMAIL $ID_SMS $ID_PUSH $ID_INAPP; do
  curl -s -H "$ACME" "$BASE/api/v1/notifications/$id" | python3 -c "$PY_DETAIL"
done

step "In-app inbox for user-42"
curl -s -H "$ACME" "$BASE/api/v1/inbox/user-42" | jqp

step "Tenant report (last 24h)"
curl -s -H "$ACME" "$BASE/api/v1/reports/deliveries" | jqp

step "Dispatcher stats (admin)"
curl -s -H "$ADMIN" "$BASE/api/v1/admin/dispatch/stats" | jqp

step "Swagger UI: $BASE/swagger-ui.html"
