# Wallet & P2P Transfer — write-up

## Data model

Three tables, in PostgreSQL 16, created by Flyway at startup.

| Table | Columns | Why it looks like this |
|---|---|---|
| `wallets` | `id` uuid pk, `user_id` text **unique**, `balance_paise` bigint `CHECK (>= 0)`, `created_at` | The unique constraint on `user_id` is the whole of race-free get-or-create. The check constraint means even a future bug cannot persist a negative balance. |
| `transfers` | `id` uuid pk, `from_wallet_id`, `to_wallet_id`, `amount_paise` bigint `CHECK (> 0)`, `status`, `created_at`, `CHECK (from <> to)` | Declined attempts are rows too, so a retry replays the decline instead of re-attempting it. |
| `idempotency_keys` | **pk `(user_id, idempotency_key)`**, `request_hash`, `transfer_id` fk, `created_at` | The primary key is the single point of exactly-once enforcement. |

Money is `bigint` paise throughout, wrapped in a `Money` value object in the
domain. There is no floating point and no rupee decimal anywhere in the money
path.

The original response body is deliberately **not** stored. It is fully derived
from the transfer row, so a replay re-renders that row and is byte-identical by
construction, with no second source of truth to drift.

## The simplest-correct mechanism for conservation and no-overdraft

One `READ COMMITTED` transaction, in this order:

1. `INSERT` the idempotency claim.
2. `SELECT ... FOR UPDATE` both wallets **in ascending wallet id order**.
3. `UPDATE wallets SET balance_paise = balance_paise - :amt WHERE id = :from AND balance_paise >= :amt`, treating zero rows affected as declined.
4. Credit the destination, insert the transfer row, commit.

Step 3 is why there is no overdraft: the guard is in the `WHERE` clause, so the
check and the write are one atomic statement and application code never does
read-then-write. Step 4 in the same transaction is why money is conserved —
the debit and the credit are the same commit, so no interleaving can observe or
persist one without the other.

**Deadlock.** Two transfers touching the same pair in opposite directions are
the classic deadlock: A→B locks A then wants B, while B→A locks B then wants A.
Sorting the two wallet ids and locking lowest-first means both transactions
request the same lock first regardless of direction, so one simply waits. This
is asserted by a test that fires 120 concurrent transfers in both directions
between the same two wallets and requires zero 5xx responses.

Strictly, the conditional debit alone prevents overdraft, and the ordered locks
alone prevent deadlock. Both are kept: the ordered locks make the pair of
updates contend predictably, and the conditional debit keeps the no-overdraft
guarantee true even if the locking were later changed.

### Heavier alternatives rejected

| Alternative | Why not |
|---|---|
| `SERIALIZABLE` isolation | Correct, but pushes the problem into serialization failures that every caller must then retry, adding a retry loop and unpredictable tail latency to buy a guarantee that two row locks already give. |
| Read balance, check in Java, then write | Wrong, not merely heavy. Two requests can both read a sufficient balance before either writes. |
| Distributed lock (Redis, advisory locks) | A second system to run, keep available and reason about, to re-implement what the row lock already provides inside the transaction that has to commit anyway. |
| Queue / single-writer per wallet | Serialises unrelated transfers, adds a broker to the free-tier footprint, and moves exactly-once from one commit into a delivery-semantics problem. |
| Two-phase commit | There is only one resource manager. |
| JPA entity dirty-checking for balances | Hides the conditional `UPDATE` that is the correctness argument behind a flush; the code would no longer say what it guarantees. |

## Where idempotency lives

Uniqueness is enforced by the **primary key `(user_id, idempotency_key)`** in
Postgres — not by an application-side check, which would race. The claim is
inserted in the **same transaction** as the debit and credit, so the key and
the money commit together or roll back together.

Claiming happens *first* in the transaction, before any balance is touched. A
concurrent duplicate then blocks on the unique index rather than doing the
work and discarding it.

**Replay.** When the claim conflicts, Postgres has already made the loser wait
until the winner committed, so the winner's row is guaranteed visible. That
conflict also aborts the loser's transaction, which is why the replay is read
in a fresh transaction afterwards — the retry loop in `TransferService` exists
for exactly that reason, not to paper over contention. The stored
`transfer_id` is re-rendered into the same response, and an
`Idempotent-Replay: true` header marks it. The header, not a body field, so the
replayed body stays byte-identical to the original.

**Same key, different body.** The claim stores a SHA-256 fingerprint of
`(from, to, amount_paise)`. A replay whose fingerprint differs returns **409**
and does not debit again.

**Failures do not burn the key.** A request rejected for an unknown wallet or a
forbidden debit throws, which rolls back the claim, so the key is reusable — it
had no effect. An insufficient-funds decline is not an exception: it is a real
business outcome, so it commits, and retrying that key replays the decline.

## Consistency versus availability

This is money, so the service is **CP**: strongly consistent, and unavailable
if Postgres is unavailable. Every transfer is a single synchronous commit
against one primary. If the database cannot be reached the request fails
loudly; nothing is queued for later and no balance is served as though a
transfer had succeeded.

What was consciously given up: writes during a database outage, and horizontal
write scaling beyond one primary. Both are the right trade here. A wallet that
is briefly unavailable is an inconvenience; a wallet that double-spends under
partition is an incident. Reads are equally strict — balances come from the
primary, so a reviewer summing balances immediately after a burst sees the
committed truth rather than replica lag.

The honest limit: correctness holds under concurrency and process restart, but
this is a single-region, single-primary deployment. Surviving the loss of that
primary would need a replica and a failover story, which the free tier does not
provide and the exercise does not require.

## AI: directed versus decided

> Reviewed and endorsed by me; please read this as my own account.

**Directed** — my call, AI implemented: Java 21 and Spring Boot for the stack;
Gradle rather than Maven; the build order (domain objects first, then APIs,
then persistence wiring, then containerisation); the instruction to apply SOLID
and to keep the layering explicit; the requirement that it actually be deployed
and observable rather than runnable locally; and the instruction to verify
current library versions from the registry instead of relying on the model's
recollection. That last one mattered: the first draft pinned Spring Boot
3.5.16, which reached end of life in June 2026, and checking moved us to 4.1.1.

**Decided** — AI proposed, I reviewed and accepted: the specific combination of
ordered `FOR UPDATE` plus conditional debit; claiming the idempotency key first
within the transaction and reading the replay in a second one; deriving the
user id as a hash of the bearer token so the credential never lands in logs or
the database; deriving the replay response from the transfer row rather than
storing a response body; seeding new wallets with an opening balance;
Spring Boot's native ECS structured logging instead of a third-party encoder;
and the Alpine runtime image using BusyBox `wget` for the healthcheck.

**Caught by testing rather than by reading:** p99 latency was configured but
never actually published. Enabling `percentiles-histogram` alongside
`percentiles` causes Micrometer's Prometheus registry to emit bucket series
*instead of* quantile series, so `/metrics` carried no p99 at all. Only
measuring the endpoint's real output across four configurations — rather than
trusting that the properties were set — exposed it. Also, Micrometer silently renamed
`wallet_transfers_created_total` to `wallet_transfers_total`, because
OpenMetrics reserves a `_created` suffix — the counter was renamed to
`wallet_transfers_completed_total`. Separately, Spring Boot disables metrics
exporters inside tests, so `/metrics` returned 404 under test while working in
production; the test now enables the exporter and asserts the endpoint rather
than assuming it.

## Capacity and cost

**₹0.** Render free web service plus a free managed Postgres; no card. Local
development uses Docker Compose, which costs nothing. No Redis, no broker, no
paid tier.

**What it bears.** Transfers are a handful of milliseconds each. The Hikari
pool is capped at 10 connections against a free-tier Postgres that permits few,
and Tomcat at 100 threads, so a large burst queues rather than exhausting the
database. In the verified run, 120 concurrent bidirectional transfers between
two wallets completed with zero 5xx and the total conserved exactly.

**Where it falls over.** Contention is the limit, not throughput: every
transfer between the same pair serialises on those two row locks, so hot-pair
throughput is bounded by lock hold time rather than by CPU. Unrelated wallet
pairs proceed in parallel. Past roughly the pool size in genuinely concurrent
hot-pair transfers, latency grows as requests queue for a connection; past the
30-second connection timeout they would fail rather than corrupt. The free
instance also sleeps when idle, so the first request after a quiet period pays
a 30–60 second cold start — `burst.sh` waits for `/health` before measuring.
