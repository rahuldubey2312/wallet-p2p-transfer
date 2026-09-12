#!/usr/bin/env bash
#
# Walks every endpoint and every documented failure mode, asserting the status
# each one is supposed to return.
#
#   ./verify-api.sh                       # defaults to http://localhost:8080
#   ./verify-api.sh https://your-app.url  # the deployed service
#
# Complements burst.sh: this checks the contract, burst.sh checks the
# invariants under concurrency. Exits non-zero if any check fails.

set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
CURL=(curl --silent --show-error --noproxy localhost,127.0.0.1 --max-time 90)

PASS=0
FAIL=0
LAST_BODY=""

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
head_line() { printf '\n------------------------------------------------------------\n %s\n------------------------------------------------------------\n' "$*"; }

json_field() {
  printf '%s' "$1" | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1
}

# check <label> <expected-status> <curl args...>
check() {
  local label="$1" expect="$2"; shift 2
  local out status body
  out=$("${CURL[@]}" -w '\n__STATUS__%{http_code}' "$@")
  status="${out##*__STATUS__}"
  body="${out%$'\n'__STATUS__*}"
  LAST_BODY="$body"

  bold "${label}"
  if [[ "${status}" == "${expect}" ]]; then
    printf '  status %s as expected   PASS\n' "${status}"
    PASS=$((PASS + 1))
  else
    printf '  status %s but expected %s   FAIL\n' "${status}" "${expect}"
    FAIL=$((FAIL + 1))
  fi
  [[ -n "${body}" ]] && printf '  %s\n' "$(printf '%s' "${body}" | head -c 500)"
  return 0
}

assert_equal() {
  local label="$1" actual="$2" expected="$3"
  if [[ "${actual}" == "${expected}" ]]; then
    printf '  %s   PASS\n' "${label}"
    PASS=$((PASS + 1))
  else
    printf '  %s   FAIL (got %s, wanted %s)\n' "${label}" "${actual}" "${expected}"
    FAIL=$((FAIL + 1))
  fi
}

register() {
  local name="$1" extra="${2:-}"
  local body
  body=$("${CURL[@]}" -X POST "${BASE_URL}/users" -H 'Content-Type: application/json' \
    -d "{\"display_name\":\"${name} $(date +%s%N)\"${extra}}")
  json_field "${body}" token
}

printf '============================================================\n'
printf ' API VERIFICATION\n target: %s\n' "${BASE_URL}"
printf '============================================================\n'

# A free instance sleeps when idle; wake it before judging anything.
printf '\nWaking service (a free-tier cold start can take up to 60s)...\n'
for attempt in $(seq 1 30); do
  code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${BASE_URL}/health" || true)
  [[ "${code}" == "200" ]] && { printf 'up after %s attempt(s)\n' "${attempt}"; break; }
  sleep 3
done
if [[ "${code:-000}" != "200" ]]; then
  printf 'Service never became healthy at %s/health (last status %s)\n' "${BASE_URL}" "${code:-none}"
  exit 1
fi

head_line "1. PUBLIC ENDPOINTS (no token)"
check "GET /        service index" 200 "${BASE_URL}/"
check "GET /health  liveness and readiness" 200 "${BASE_URL}/health"
check "GET /metrics Prometheus exposition" 200 -o /dev/null "${BASE_URL}/metrics"

head_line "2. REGISTRATION ISSUES A CREDENTIAL"
EMAIL="verify.$(date +%s%N)@example.com"
check "POST /users  full details" 201 -X POST "${BASE_URL}/users" \
  -H 'Content-Type: application/json' \
  -d "{\"display_name\":\"Alice Verify\",\"email\":\"${EMAIL}\",\"phone\":\"+91 9876543210\"}"
ALICE_TOKEN=$(json_field "${LAST_BODY}" token)
ALICE_USER=$(json_field "${LAST_BODY}" id)
[[ "${ALICE_TOKEN}" == wlt_* ]] \
  && { printf '  token is service-issued and prefixed   PASS\n'; PASS=$((PASS + 1)); } \
  || { printf '  token missing or malformed   FAIL\n'; FAIL=$((FAIL + 1)); }

check "POST /users  reusing that email is a conflict" 409 -X POST "${BASE_URL}/users" \
  -H 'Content-Type: application/json' \
  -d "{\"display_name\":\"Impostor\",\"email\":\"${EMAIL}\"}"
check "POST /users  display_name is required" 400 -X POST "${BASE_URL}/users" \
  -H 'Content-Type: application/json' -d '{"email":"nobody@example.com"}'
check "POST /users  email must look like one" 400 -X POST "${BASE_URL}/users" \
  -H 'Content-Type: application/json' -d '{"display_name":"Bad","email":"not-an-email"}'

BOB_TOKEN=$(register "Bob Verify")

head_line "3. WALLETS, AND THE OWNER THEY MAP TO"
check "POST /wallets  first call creates" 200 -X POST "${BASE_URL}/wallets" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
ALICE_WALLET=$(json_field "${LAST_BODY}" id)
assert_equal "wallet is attributed to its owner" "$(json_field "${LAST_BODY}" user_id)" "${ALICE_USER}"

check "POST /wallets  second call returns the same wallet" 200 -X POST "${BASE_URL}/wallets" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
assert_equal "get-or-create is idempotent" "$(json_field "${LAST_BODY}" id)" "${ALICE_WALLET}"

check "POST /wallets  for Bob" 200 -X POST "${BASE_URL}/wallets" \
  -H "Authorization: Bearer ${BOB_TOKEN}"
BOB_WALLET=$(json_field "${LAST_BODY}" id)

check "GET /wallets/{id}  balance" 200 "${BASE_URL}/wallets/${ALICE_WALLET}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
OPENING=$(json_field "${LAST_BODY}" balance_paise)

check "GET /wallets/{unknown}" 404 "${BASE_URL}/wallets/$(cat /proc/sys/kernel/random/uuid)" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"

head_line "4. TRANSFER"
KEY="verify-$(date +%s%N)"
BODY="{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":25000,\"idempotency_key\":\"${KEY}\"}"
check "POST /transfers  25000 paise, Alice to Bob" 201 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: verify-api-transfer' -d "${BODY}"
TRANSFER_ID=$(json_field "${LAST_BODY}" id)
FIRST_BODY="${LAST_BODY}"

check "GET /transfers/{id}  status" 200 "${BASE_URL}/transfers/${TRANSFER_ID}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
check "GET /transfers/{unknown}" 404 "${BASE_URL}/transfers/$(cat /proc/sys/kernel/random/uuid)" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"

check "GET /wallets/{alice}  debited once" 200 "${BASE_URL}/wallets/${ALICE_WALLET}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
assert_equal "sender balance fell by exactly the amount" \
  "$(json_field "${LAST_BODY}" balance_paise)" "$((OPENING - 25000))"

head_line "5. EXACTLY ONCE"
check "POST /transfers  same key and body replays" 201 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' -d "${BODY}"
assert_equal "replayed body is identical to the original" "${LAST_BODY}" "${FIRST_BODY}"

REPLAY_HEADER=$("${CURL[@]}" -o /dev/null -D- -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' -d "${BODY}" \
  | tr -d '\r' | sed -n 's/^[Ii]dempotent-[Rr]eplay: //p')
assert_equal "replay is flagged in the response header" "${REPLAY_HEADER}" "true"

check "POST /transfers  same key, different amount is a conflict" 409 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":99999,\"idempotency_key\":\"${KEY}\"}"
check "GET /wallets/{alice}  the conflict debited nothing" 200 "${BASE_URL}/wallets/${ALICE_WALLET}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
assert_equal "balance unchanged by the 409" \
  "$(json_field "${LAST_BODY}" balance_paise)" "$((OPENING - 25000))"

head_line "6. NO OVERDRAFT"
check "POST /transfers  more than the balance is declined" 422 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":99999999,\"idempotency_key\":\"od-$(date +%s%N)\"}"
check "GET /wallets/{alice}  nothing moved" 200 "${BASE_URL}/wallets/${ALICE_WALLET}" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
assert_equal "balance unchanged by the decline" \
  "$(json_field "${LAST_BODY}" balance_paise)" "$((OPENING - 25000))"

head_line "7. USER PROFILE AND TRANSACTION HISTORY"
check "GET /users/me  profile, wallet and recent activity" 200 "${BASE_URL}/users/me" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
check "GET /users/me/transactions  sender sees a DEBIT" 200 "${BASE_URL}/users/me/transactions" \
  -H "Authorization: Bearer ${ALICE_TOKEN}"
printf '%s' "${LAST_BODY}" | grep -q 'DEBIT' \
  && { printf '  sender side is a DEBIT   PASS\n'; PASS=$((PASS + 1)); } \
  || { printf '  sender side is not a DEBIT   FAIL\n'; FAIL=$((FAIL + 1)); }

check "GET /users/me/transactions  recipient sees a CREDIT" 200 "${BASE_URL}/users/me/transactions" \
  -H "Authorization: Bearer ${BOB_TOKEN}"
printf '%s' "${LAST_BODY}" | grep -q 'CREDIT' \
  && { printf '  recipient side is a CREDIT   PASS\n'; PASS=$((PASS + 1)); } \
  || { printf '  recipient side is not a CREDIT   FAIL\n'; FAIL=$((FAIL + 1)); }

check "GET /users/me/transactions?limit=1  paging" 200 \
  "${BASE_URL}/users/me/transactions?limit=1&offset=0" -H "Authorization: Bearer ${ALICE_TOKEN}"

head_line "8. AUTHENTICATION AND AUTHORISATION"
check "POST /wallets   no token" 401 -X POST "${BASE_URL}/wallets"
check "POST /wallets   a token that was never issued" 401 -X POST "${BASE_URL}/wallets" \
  -H 'Authorization: Bearer wlt_this_token_does_not_exist'
check "POST /transfers debiting a wallet the caller does not own" 403 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${BOB_WALLET}\",\"to\":\"${ALICE_WALLET}\",\"amount_paise\":100,\"idempotency_key\":\"steal-$(date +%s%N)\"}"

head_line "9. VALIDATION: EVERY REJECTION A CLEAN 4xx"
check "from equals to" 400 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${ALICE_WALLET}\",\"amount_paise\":100,\"idempotency_key\":\"self-$(date +%s%N)\"}"
check "amount of zero" 400 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":0,\"idempotency_key\":\"zero-$(date +%s%N)\"}"
check "negative amount" 400 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":-500,\"idempotency_key\":\"neg-$(date +%s%N)\"}"
check "missing idempotency_key" 400 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"${ALICE_WALLET}\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":100}"
check "malformed JSON" 400 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' -d '{not json'
check "unknown source wallet" 404 -X POST "${BASE_URL}/transfers" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"from\":\"$(cat /proc/sys/kernel/random/uuid)\",\"to\":\"${BOB_WALLET}\",\"amount_paise\":100,\"idempotency_key\":\"nf-$(date +%s%N)\"}"

head_line "10. OBSERVABILITY"
CORRELATION=$("${CURL[@]}" -o /dev/null -D- -X POST "${BASE_URL}/wallets" \
  -H "Authorization: Bearer ${ALICE_TOKEN}" -H 'X-Correlation-Id: my-own-trace-id-123' \
  | tr -d '\r' | sed -n 's/^[Xx]-[Cc]orrelation-[Ii]d: //p')
assert_equal "an inbound correlation id is echoed back" "${CORRELATION}" "my-own-trace-id-123"

# Fetched once into a file: piping curl straight into `grep -q` makes grep
# exit on the first match and SIGPIPE curl, which reports a write failure and
# would fail this check even when the metric is present.
METRICS_FILE="$(mktemp)"
trap 'rm -f "${METRICS_FILE}"' EXIT
"${CURL[@]}" -o "${METRICS_FILE}" "${BASE_URL}/metrics"

printf '\n  domain counters:\n'
grep -E '^wallet_' "${METRICS_FILE}" | sed 's/^/    /'

printf '\n  p99 latency (request timer):\n'
grep '^http_server_requests_seconds{' "${METRICS_FILE}" | grep 'quantile="0.99"' \
  | head -3 | sed 's/^/    /'

if grep '^http_server_requests_seconds{' "${METRICS_FILE}" | grep -q 'quantile="0.99"'; then
  printf '  p99 is published   PASS\n'; PASS=$((PASS + 1))
else
  printf '  p99 is missing   FAIL\n'; FAIL=$((FAIL + 1))
fi

printf '\n============================================================\n'
printf ' RESULT: %d passed, %d failed\n' "${PASS}" "${FAIL}"
printf '============================================================\n'
[[ "${FAIL}" -eq 0 ]]
