# FinFlow — Banking Ledger API

FinFlow is a backend banking system built with Spring Boot. It handles user wallets, money movements (deposit, withdraw, transfer), a payment gateway integration, and a full double-entry ledger — all secured with JWT authentication.

A minimal vanilla JS frontend is included and served directly from the app at `http://localhost:8080`.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 22 |
| Framework | Spring Boot 4 |
| Database | PostgreSQL |
| Cache | Redis |
| Messaging | Apache Kafka |
| Build | Gradle (multi-module) |
| Auth | JWT (via lib-commons) |
| Frontend | Vanilla JS + Bootstrap 5 |

---

## Getting Started

### Prerequisites
- Java 22
- PostgreSQL running on `localhost:5432`
  - Database: `postgres`
  - Username / Password: `postgres`
- Redis running on `localhost:6379`
  ```bash
  docker run -d -p 6379:6379 --name finflow-redis redis:latest
  ```
- Kafka running on `localhost:9092`
  ```bash
  docker run -d -p 9092:9092 apache/kafka:latest
  ```
- lib-commons installed locally (see below)

### Install lib-commons

FinFlow depends on a sibling library for JWT auth and JPA auditing. Install it once before building:

```bash
cd ../lib-commons
./mvnw -q install -DskipTests
```

### Run the app

```bash
./gradlew :service:bootRun
```

The API and frontend are both available at `http://localhost:8080`.

### Build & test

```bash
./gradlew build       # build all modules and run tests
./gradlew test        # run tests only
```

---

## Project Structure

FinFlow is split into Gradle submodules. Each module owns one domain area. Dependencies only flow downward — never upward.

```
module-wallet        User, Wallet
      ↑
module-auth          Registration, login adapter
      ↑
module-transaction   Transaction, PaymentOrder, IdempotencyRecord
      ↑
module-ledger        LedgerEntry, TransactionService, PaymentService
      ↑
service              FinFlowApplication (bootstrap only)
```

---

## API Reference

All responses are wrapped in a standard envelope:

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

### Authentication

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/users/register` | No | Register a new user |
| POST | `/auth/token` | No | Login — returns a JWT string |
| GET | `/users/me` | Yes | Get your own profile |
| GET | `/users/{username}` | Yes | Get a user by username |

**Login request:**
```json
{ "username": "alice", "password": "password123" }
```
Returns a plain JWT string (not JSON). Pass it as `Authorization: Bearer <token>` on all subsequent requests.

---

### Wallet

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/wallets` | Yes | Create a wallet |
| GET | `/wallets/me` | Yes | Get your wallet |
| GET | `/wallets/{walletId}` | Yes | Get wallet by ID |
| PUT | `/wallets/{walletId}/freeze` | Yes | Freeze wallet |
| PUT | `/wallets/{walletId}/unfreeze` | Yes | Unfreeze wallet |

**Create wallet request:**
```json
{ "currency": "INR" }
```

Supported currencies: `INR`, `USD`, `EUR`, `GBP`.

Wallet reads are cached in Redis (`wallet:{walletId}`, 30 min TTL) — a GET populates the cache on a miss, and any mutating operation (deposit, withdraw, transfer, freeze, unfreeze) evicts the affected wallet's cache entry so the next read is fresh.

---

### Transactions

All write operations require an `Idempotency-Key` header (UUID). Send the same key to safely retry a request — the original response is returned without creating a duplicate. Idempotency records are checked against Redis first (fast path) and fall back to Postgres, backfilling Redis on a hit — Redis is a hard dependency here, not a best-effort cache.

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/wallets/{id}/deposit` | Yes | Direct deposit (internal) |
| POST | `/wallets/{id}/withdraw` | Yes | Withdraw money |
| POST | `/wallets/{id}/transfer` | Yes | Transfer to another user |

**Deposit / Withdraw request:**
```json
{
  "amount": 1000.00,
  "description": "Salary credit"
}
```

**Transfer request:**
```json
{
  "destinationUsername": "bob",
  "amount": 500.00,
  "description": "Rent"
}
```

Every transaction gets a human-readable reference in the format:
```
DEP-20260704-1A2B3C4D
WDR-20260704-1A2B3C4D
TRF-20260704-1A2B3C4D
```

---

### Transaction History

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/wallets/{id}/transactions/search` | Yes | History for a wallet |
| POST | `/users/me/transactions/search` | Yes | History for the logged-in user |

**Request body (all fields optional):**
```json
{
  "status": "COMPLETED",
  "type": "DEPOSIT",
  "from": "2026-01-01",
  "to": "2026-12-31",
  "page": 0,
  "size": 10
}
```

Filter options:
- `status`: `PENDING`, `COMPLETED`, `FAILED`
- `type`: `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`

---

### Payments

The payment flow is two steps: initiate → webhook. This simulates a real payment gateway where money only enters the system after the gateway confirms success. The webhook itself is just an acknowledgement — deposit and failure handling happen asynchronously via Kafka (see [Async Payment Processing](#async-payment-processing-kafka--transactional-outbox) below).

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/payments/initiate` | Yes | Create a payment order |
| POST | `/payments/webhook` | No | Gateway callback (success or failure) |

**Step 1 — Initiate payment** (requires `Idempotency-Key` header):
```json
{
  "walletId": "uuid-of-your-wallet",
  "amount": 500.00,
  "paymentMethod": "UPI"
}
```
Payment methods: `UPI`, `CARD`, `NET_BANKING`

Response includes a `paymentOrderId` and `paymentReference` (e.g. `PAY-20260704-1A2B3C4D`).

**Step 2 — Webhook callback:**
```json
{
  "paymentOrderId": "uuid-from-step-1",
  "status": "SUCCESS",
  "gatewayPaymentId": "GW-TXN-ABC123"
}
```

The webhook validates the order, updates its status, **creates a `PENDING` transaction right away**, and returns an ack immediately — it does not wait for the deposit:
```json
{
  "paymentOrderId": "uuid-from-step-1",
  "paymentReference": "PAY-20260704-1A2B3C4D",
  "status": "SUCCESS"
}
```

- `SUCCESS` → a `PAYMENT_COMPLETED` event is queued for publishing; a consumer completes the same `PENDING` transaction (deposit + wallet credit) moments later
- `FAILED` → a `PAYMENT_FAILED` event is queued; a consumer resolves the same `PENDING` transaction to `FAILED`, wallet unchanged

Because the `PENDING` transaction is written synchronously inside the webhook request (before anything touches Kafka), it's visible in transaction history immediately — even if the async pipeline below is degraded or Kafka is unreachable. See [How Money Moves](#how-money-moves-two-phase-commit).

Poll `GET /wallets/{walletId}` or the transaction history endpoints to see the result once the async pipeline finishes processing.

---

## Async Payment Processing (Kafka + Transactional Outbox)

The webhook never talks to Kafka directly. It writes an `OutboxEvent` row (`PENDING`, in the same DB transaction as the `PaymentOrder`/`Transaction` update) instead of calling `KafkaTemplate.send(...)` inline — this avoids the classic dual-write problem where a DB commit succeeds but the Kafka send is lost, or vice versa. A separate scheduled `OutboxPublisher` (`@Scheduled(fixedDelay = 1000)`) does the actual publishing:

1. Locks a batch of due `PENDING` rows with `SELECT ... FOR UPDATE SKIP LOCKED` (safe for multiple app instances — each grabs a disjoint batch instead of double-publishing).
2. Sends each to Kafka. On success → `PUBLISHED`. On failure → `retryCount++` and `nextRetryAt` backs off exponentially (`2^retryCount` seconds), retried again once due. After 5 failures, the event is marked `FAILED` (a poison message won't block the batch forever — but since the `Transaction` behind it was already created as `PENDING` in step 1, the money movement itself is never silently lost, just stuck for manual follow-up).

Each Kafka topic has its own consumer group, so a redelivered message is safe to reprocess — the deposit itself is idempotent (keyed by `paymentOrderId`), and each event carries the `transactionId` of the `PENDING` row created by the webhook so consumers resolve that same row instead of creating a new one.

| Topic | Published by | Consumer group | Consumer does |
|---|---|---|---|
| `payment-completed` | outbox publisher, after webhook `SUCCESS` | `finflow-deposit-group` | Completes the pending deposit transaction, credits the wallet, then queues `wallet-credited` |
| `payment-failed` | outbox publisher, after webhook `FAILED` | `finflow-failure-group` | Resolves the pending transaction to `FAILED` |
| `wallet-credited` | outbox publisher, after a successful deposit | `finflow-notify-group` | Logs the credit (no further business logic) |

```
webhook (SUCCESS) ─▶ PENDING transaction + outbox row ─▶ [OutboxPublisher] ─▶ payment-completed ─▶ [deposit consumer] ─▶ outbox row ─▶ [OutboxPublisher] ─▶ wallet-credited ─▶ [log consumer]
webhook (FAILED)  ─▶ PENDING transaction + outbox row ─▶ [OutboxPublisher] ─▶ payment-failed    ─▶ [failure consumer]
```

Requires a Kafka broker on `localhost:9092` (see Prerequisites) — the app still starts without one, and outbox rows just accumulate as `PENDING`/retrying until the broker becomes reachable, at which point the publisher catches up automatically.

Two more consumer groups independently read all 3 topics, on top of the business ones above (Kafka gives every consumer group its own full copy) — see [Dead Letter Topics](#dead-letter-topics-dlt) and [Audit Log](#audit-log) below.

---

## Failure Codes

When a `Transaction` or `OutboxEvent` ends up `FAILED`, it's not just a bare status — a `failureCode` + `failureMessage` explain why:

```java
enum FailureCode {
    WALLET_NOT_FOUND, WALLET_INACTIVE, INSUFFICIENT_BALANCE, CURRENCY_MISMATCH,
    PAYMENT_GATEWAY_DECLINED, KAFKA_PUBLISH_FAILED, UNKNOWN_ERROR
}
```

- `Transaction.failureCode` — set from whichever validation check actually failed (`WALLET_NOT_FOUND`, `WALLET_INACTIVE`, `INSUFFICIENT_BALANCE`, `CURRENCY_MISMATCH`, or `UNKNOWN_ERROR` as a fallback), or `PAYMENT_GATEWAY_DECLINED` when a webhook reports the gateway itself declined the payment.
- `OutboxEvent.failureCode` — always `KAFKA_PUBLISH_FAILED` on a terminal (post-retry) failure, since the only way an outbox row fails is Kafka being unreachable when the publisher tries to send it.

---

## Reconciliation

A safety net (`ReconciliationService`, `@Scheduled` every 5 minutes) that looks for payments where the `PaymentOrder` says `SUCCESS` but the money never actually showed up — and tries to get it moving again instead of leaving it stuck. Only orders older than a 2-minute safety buffer are checked, so it never races the normal in-flight webhook → outbox → Kafka → deposit path.

```
Every 5 min → find PaymentOrders where status = SUCCESS (and old enough)
                              │
                 find the Transaction for that order
                              │
              ┌───────────────┴────────────────┐
          COMPLETED                      missing / PENDING
              │                                 │
           nothing                  find the latest PAYMENT_COMPLETED outbox event
                                                 │
                              ┌──────────────────┼───────────────────────┐
                          PENDING              FAILED                 missing
                              │                   │                       │
                    leave it (OutboxPublisher   reset it to PENDING    create a fresh
                     is already retrying it)     to retry again        recovery event
```

If the `Transaction` itself is already `FAILED` (a real validation failure, e.g. the wallet went inactive), reconciliation leaves it alone — that needs a human, not an automatic retry. Each run also logs a count of all currently-`FAILED` transactions for visibility.

---

## Dead Letter Topics (DLT)

If a Kafka **consumer** (not the outbox publisher — a listener like the deposit consumer) keeps throwing on a message, it doesn't get retried forever and it doesn't get silently dropped. `KafkaConfig` wires up a `DefaultErrorHandler`: retry 3 times (1s apart), then publish the raw message to `<topic>.DLT` (e.g. `payment-completed.DLT`) via `DeadLetterPublishingRecoverer`. A log-only `DeadLetterListener` (group `finflow-dlt-group`) also picks up everything that lands there, just to log it.

### Admin API for DLT messages
`ADMIN`-role-only (see [Roles & Admin Access](#roles--admin-access)):

| Method | Endpoint | Description |
|---|---|---|
| GET | `/admin/dlt/{topic}?page=0&size=50` | List messages on `{topic}.DLT` (paginated, 0-based) |
| POST | `/admin/dlt/{topic}/retry` | Republish one message (body: `{"partition": 0, "offset": 5}`) back onto `{topic}` |
| DELETE | `/admin/dlt/{topic}?partition=0&offset=5` | Discard one message without retrying it |

Both retry and delete immediately mark the message **resolved** in a `dlt_resolutions` table and it disappears from the list right away — regardless of whether Kafka has actually let go of the record yet. That's because **Kafka can't delete one arbitrary record**: a partition is an append-only log, and `AdminClient.deleteRecords()` can only trim everything before a given offset. So if a message is already the oldest one on its partition, it's purged from Kafka immediately; otherwise it just waits, physically still there but hidden, until a scheduled `DltCleanupScheduler` (also every 5 minutes) sweeps each DLT topic from the front and bulk-deletes the longest consecutive run of resolved messages it finds. The list response includes a `deletable` flag per message (informational — Retry/Delete work regardless) showing whether Kafka could remove it right this second or whether it's waiting on something ahead of it.

---

## Audit Log

A dedicated Kafka consumer group, `finflow-audit-group`, listens to all 3 business topics *and* their `.DLT` counterparts — completely independent of the business consumers (deposit/failure/notify/dlt groups), since every consumer group gets its own full copy of a topic. It writes one row per event to `audit_logs`, regardless of whether the business consumer for that same message ever succeeds:

| Column | Contents |
|---|---|
| `topic` | The actual Kafka topic the message arrived on (business or `.DLT`) |
| `eventType` | `PAYMENT_COMPLETED` / `PAYMENT_FAILED` / `WALLET_CREDITED` |
| `userId` | Username of the wallet owner (looked up from the event's `walletId`), or `null` if the wallet couldn't be found |
| `details` | Short human-readable summary, e.g. `"Payment completed: amount=500.00, ref=PAY-..., method=UPI"` |
| `payload` | The raw event JSON, verbatim, for full traceability |

---

## Roles & Admin Access

Every new user registers with `roles = ["USER"]`. An `ADMIN` role is required for the DLT admin API and for granting/revoking roles on other users (`PUT /auth/role`, `PUT /auth/remove/role` in lib-commons) — both are gated with `@PreAuthorize("hasRole('ADMIN')")`.

Since granting `ADMIN` itself requires already being `ADMIN`, **the first admin has to be created directly in the database** — there's no bootstrap endpoint:
```sql
INSERT INTO user_roles (user_id, role) VALUES (<user's internal id>, 'ADMIN');
```
Once that's done, that user can promote/demote anyone else via `PUT /auth/role?username=...&role=ADMIN`. The frontend shows a "DLT Admin" nav button only when `GET /users/me` reports `ADMIN` in `roles` — that's just UI convenience, the real enforcement is server-side.

---

## How Money Moves (Two-Phase Commit)

Every transaction is committed in two independent database transactions:

1. **PENDING** is written immediately — visible in the DB even if the operation fails halfway
2. Business logic runs (ledger entries, wallet balance update)
3. On success → status updated to **COMPLETED**
4. On failure → status updated to **FAILED**, partial changes rolled back

This means failed operations always leave an audit trail. For payments, step 1 happens synchronously inside the webhook request itself (before the outbox/Kafka pipeline even starts) — so the same guarantee holds even if the async completion never arrives.

---

## Double-Entry Ledger

Every balance change creates a `LedgerEntry` record alongside the transaction:

- Deposit → one `CREDIT` entry on the destination wallet
- Withdrawal → one `DEBIT` entry on the source wallet
- Transfer → one `DEBIT` on source + one `CREDIT` on destination

Each entry stores `balanceAfter` so the full balance history is reconstructible.

---

## Timestamps

All timestamps in API responses are in **IST (Asia/Kolkata, UTC+5:30)**:
```
2026-07-04T22:00:00+05:30
```

---

## Frontend

Open `http://localhost:8080` in a browser. Features:

- Register / Login
- Create wallet, freeze/unfreeze, manual refresh button on the wallet card
- Add money via payment gateway (UPI / Card / Net Banking)
- Withdraw and transfer
- Transaction history with filters (status, type, date range)
- Dark mode toggle
- **DLT Admin panel** (visible only to `ADMIN` users) — pick a topic, list its dead-lettered messages, and Retry or Delete each one

---

The Login request auto-captures the JWT token. Create Wallet auto-captures the wallet ID. Initiate Payment auto-captures the payment order ID — run the webhook request immediately after to simulate gateway confirmation. Since the deposit now happens asynchronously through Kafka, the UI waits briefly after the webhook ack before refreshing the wallet and transaction list.
