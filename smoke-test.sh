#!/usr/bin/env bash
# Smoke test against a running router with real kev and Ollama.
#   ROUTER_API_KEY=geheim ./smoke-test.sh [http://localhost:8080]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
KEY="${ROUTER_API_KEY:?ROUTER_API_KEY must be set}"
H=(-H "X-API-Key: $KEY")
J=(-H "Content-Type: application/json")

step() { printf '\n=== %s ===\n' "$1"; }
field() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)"; }

step "Health"
curl -fsS "$BASE/q/health/ready"; echo

step "1. D0, anspruchsvoll -> erwartet CLOUD (wenn kev sicher genug ist)"
R=$(curl -fsS "${H[@]}" "${J[@]}" "$BASE/route" \
  -d '{"prompt":"Vergleiche die Vor- und Nachteile von Kubernetes und Docker Swarm für ein KMU mit 20 Mitarbeitenden und gib eine begründete Empfehlung."}')
echo "$R"
ID=$(echo "$R" | field "['id']")

step "1b. Feedback OK -> Antwort"
curl -fsS "${H[@]}" "${J[@]}" "$BASE/requests/$ID/feedback" -d '{"feedback":"OK"}' | head -c 600; echo

step "2. D2 (Gesundheit) -> erwartet lokal, dann NOK"
R=$(curl -fsS "${H[@]}" "${J[@]}" "$BASE/route" \
  -d '{"prompt":"Übersetze ins Französische: Regula Jost leidet an Diabetes Typ 2 und braucht regelmässige Kontrolltermine."}')
echo "$R"
ID2=$(echo "$R" | field "['id']")
curl -fsS "${H[@]}" "${J[@]}" "$BASE/requests/$ID2/feedback" -d '{"feedback":"NOK"}'; echo

step "3. dryRun mit Anhang"
TMP=$(mktemp /tmp/anhang.XXXXXX.txt)
echo "Offerte an Rigi Logistik AG: Pauschalpreis CHF 65'500, maximale Marge 4 %." > "$TMP"
curl -fsS "${H[@]}" -F "prompt=Fasse den Anhang zusammen." -F "dryRun=true" -F "files=@$TMP;type=text/plain" "$BASE/route"; echo
rm -f "$TMP"

step "Logs: letzte 3 Anfragen"
curl -fsS "${H[@]}" "$BASE/logs/latest/3"; echo

step "Ohne API-Key -> 401"
curl -s -o /dev/null -w "%{http_code}\n" "${J[@]}" "$BASE/route" -d '{"prompt":"x"}'
