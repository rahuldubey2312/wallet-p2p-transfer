#!/usr/bin/env bash
#
# Reproduces the three probes from the exercise against a running instance.
#
#   ./burst.sh                       # defaults to http://localhost:8080
#   ./burst.sh https://your-app.url  # the deployed service
#
# Exits non-zero if any invariant is violated, so it doubles as a smoke test.

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
GET_OR_CREATE_CALLS="${GET_OR_CREATE_CALLS:-25}"
RETRY_STORM_CALLS="${RETRY_STORM_CALLS:-30}"
CONTENTION_CALLS="${CONTENTION_CALLS:-120}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

FAILURES=0

# Bypass any configured proxy for loopback only. Bypassing it wholesale would
# break a deployed https:// target on a network that requires the proxy.
CURL=(curl --silent --show-error --noproxy localhost,127.0.0.1 --max-time 90)

log()  { printf '%s\n' "$*"; }
pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

json_field() {
  # $1 = json, $2 = field. Kept dependency-free on purpose.
  printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1
}

new_token() { printf 'tok-%s-%s' "$(date +%s%N)" "${RANDOM}${RANDOM}"; }

create_wallet() {
  local token="$1"
  "${CURL[@]}" -X POST "${BASE_URL}/wallets" -H "Authorization: Bearer ${token}"
}

balance_of() {
  local wallet="$1"
  local body
  body=$("${CURL[@]}" "${BASE_URL}/wallets/${wallet}" -H "Authorization: Bearer $(new_token)")
  json_field "${body}" balance_paise
}

transfer_body() {
  printf '{"from":"%s","to":"%s","amount_paise":%s,"idempotency_key":"%s"}' "$1" "$2" "$3" "$4"
}

log "Target: ${BASE_URL}"
log ""

# A free-tier host sleeps when idle; wake it before timing anything so a cold
# start is not mistaken for a failure.
log "Waking service (free-tier cold start can take up to 60s)..."
for attempt in $(seq 1 30); do
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${BASE_URL}/health" || true)
  if [[ "${code}" == "200" ]]; then
    log "Service is up after ${attempt} attempt(s)."
    break
  fi
  sleep 3
done
if [[ "${code:-000}" != "200" ]]; then
  log "Service did not become healthy at ${BASE_URL}/health (last status ${code:-none})."
  exit 1
fi
log ""

# ---------------------------------------------------------------------------
log "PROBE 1  Concurrent get-or-create: ${GET_OR_CREATE_CALLS} simultaneous POST /wallets for one brand-new user"
# ---------------------------------------------------------------------------
TOKEN_NEW="$(new_token)"
for i in $(seq 1 "${GET_OR_CREATE_CALLS}"); do
  ( create_wallet "${TOKEN_NEW}" > "${WORK_DIR}/wallet-${i}.json" ) &
done
wait

DISTINCT_WALLETS=$(for f in "${WORK_DIR}"/wallet-*.json; do json_field "$(cat "$f")" id; done | sort -u | grep -c . || true)
log "  distinct wallet ids returned: ${DISTINCT_WALLETS}"
if [[ "${DISTINCT_WALLETS}" == "1" ]]; then
  pass "exactly one wallet for ${GET_OR_CREATE_CALLS} concurrent creates"
else
  fail "expected 1 wallet, got ${DISTINCT_WALLETS}"
fi
log ""

# ---------------------------------------------------------------------------
log "PROBE 2  Idempotent retry storm: ${RETRY_STORM_CALLS} concurrent transfers sharing one idempotency key"
# ---------------------------------------------------------------------------
TOKEN_A="$(new_token)"
TOKEN_B="$(new_token)"
WALLET_A=$(json_field "$(create_wallet "${TOKEN_A}")" id)
WALLET_B=$(json_field "$(create_wallet "${TOKEN_B}")" id)
AMOUNT=25000
KEY="storm-$(date +%s%N)"

A_BEFORE=$(balance_of "${WALLET_A}")
B_BEFORE=$(balance_of "${WALLET_B}")

BODY=$(transfer_body "${WALLET_A}" "${WALLET_B}" "${AMOUNT}" "${KEY}")
for i in $(seq 1 "${RETRY_STORM_CALLS}"); do
  ( "${CURL[@]}" -o "${WORK_DIR}/storm-${i}.json" -w '%{http_code}' \
      -X POST "${BASE_URL}/transfers" \
      -H "Authorization: Bearer ${TOKEN_A}" -H 'Content-Type: application/json' \
      -d "${BODY}" > "${WORK_DIR}/storm-${i}.code" ) &
done
wait

DISTINCT_TRANSFERS=$(for f in "${WORK_DIR}"/storm-*.json; do json_field "$(cat "$f")" id; done | sort -u | grep -c . || true)
DISTINCT_BODIES=$(cat "${WORK_DIR}"/storm-*.json | sort -u | wc -l)
NON_2XX=$(cat "${WORK_DIR}"/storm-*.code | grep -vc '^2' || true)
A_AFTER=$(balance_of "${WALLET_A}")
B_AFTER=$(balance_of "${WALLET_B}")

log "  distinct transfer ids: ${DISTINCT_TRANSFERS}   distinct bodies: ${DISTINCT_BODIES}   non-2xx: ${NON_2XX}"
log "  sender ${A_BEFORE} -> ${A_AFTER}   recipient ${B_BEFORE} -> ${B_AFTER}"

[[ "${DISTINCT_TRANSFERS}" == "1" ]] && pass "one transfer for ${RETRY_STORM_CALLS} retries" \
  || fail "expected 1 transfer, got ${DISTINCT_TRANSFERS}"
[[ "${DISTINCT_BODIES}" == "1" ]] && pass "all responses identical" \
  || fail "expected identical responses, got ${DISTINCT_BODIES} variants"
[[ $((A_BEFORE - A_AFTER)) -eq ${AMOUNT} ]] && pass "debited exactly once" \
  || fail "sender moved by $((A_BEFORE - A_AFTER)), expected ${AMOUNT}"
[[ $((B_AFTER - B_BEFORE)) -eq ${AMOUNT} ]] && pass "credited exactly once" \
  || fail "recipient moved by $((B_AFTER - B_BEFORE)), expected ${AMOUNT}"
log ""

# ---------------------------------------------------------------------------
log "PROBE 3  Conservation under contention: ${CONTENTION_CALLS} concurrent transfers, both directions at once"
# ---------------------------------------------------------------------------
C_BEFORE=$(balance_of "${WALLET_A}")
D_BEFORE=$(balance_of "${WALLET_B}")
TOTAL_BEFORE=$((C_BEFORE + D_BEFORE))

for i in $(seq 1 "${CONTENTION_CALLS}"); do
  (
    if (( i % 2 == 0 )); then
      FROM="${WALLET_A}"; TO="${WALLET_B}"; TOKEN="${TOKEN_A}"
    else
      FROM="${WALLET_B}"; TO="${WALLET_A}"; TOKEN="${TOKEN_B}"
    fi
    "${CURL[@]}" -o /dev/null -w '%{http_code}' \
      -X POST "${BASE_URL}/transfers" \
      -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
      -d "$(transfer_body "${FROM}" "${TO}" $(( (RANDOM % 4000) + 1 )) "contend-$(date +%s%N)-${i}")" \
      > "${WORK_DIR}/contend-${i}.code"
  ) &
done
wait

SERVER_ERRORS=$(cat "${WORK_DIR}"/contend-*.code | grep -c '^5' || true)
C_AFTER=$(balance_of "${WALLET_A}")
D_AFTER=$(balance_of "${WALLET_B}")
TOTAL_AFTER=$((C_AFTER + D_AFTER))

log "  total before: ${TOTAL_BEFORE}   total after: ${TOTAL_AFTER}   5xx responses: ${SERVER_ERRORS}"
log "  balances: ${C_AFTER} and ${D_AFTER}"

[[ "${TOTAL_BEFORE}" -eq "${TOTAL_AFTER}" ]] && pass "conservation: total unchanged" \
  || fail "money changed: ${TOTAL_BEFORE} -> ${TOTAL_AFTER}"
[[ "${C_AFTER}" -ge 0 && "${D_AFTER}" -ge 0 ]] && pass "no negative balance" \
  || fail "a balance went negative"
[[ "${SERVER_ERRORS}" -eq 0 ]] && pass "no 5xx under contention" \
  || fail "${SERVER_ERRORS} server errors under contention"
log ""

# ---------------------------------------------------------------------------
log "Domain counters currently exposed at ${BASE_URL}/metrics"
# ---------------------------------------------------------------------------
"${CURL[@]}" "${BASE_URL}/metrics" | grep -E '^wallet_' | sed 's/^/  /' || true
log ""

if [[ "${FAILURES}" -eq 0 ]]; then
  log "ALL PROBES PASSED"
  exit 0
fi
log "${FAILURES} CHECK(S) FAILED"
exit 1
