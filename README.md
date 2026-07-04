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

---

### Transactions

All write operations require an `Idempotency-Key` header (UUID). Send the same key to safely retry a request — the original response is returned without creating a duplicate.

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

The payment flow is two steps: initiate → webhook. This simulates a real payment gateway where money only enters the system after the gateway confirms success.

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

- `SUCCESS` → deposit is created, wallet balance updated
- `FAILED` → a `FAILED` transaction is recorded for audit, wallet unchanged

---

## How Money Moves (Two-Phase Commit)

Every transaction is committed in two independent database transactions:

1. **PENDING** is written immediately — visible in the DB even if the operation fails halfway
2. Business logic runs (ledger entries, wallet balance update)
3. On success → status updated to **COMPLETED**
4. On failure → status updated to **FAILED**, partial changes rolled back

This means failed operations always leave an audit trail.

---

## Double-Entry Ledger

Every balance change creates a `LedgerEntry` record alongside the transaction:

- Deposit → one `CREDIT` entry on the destination wallet
- Withdrawal → one `DEBIT` entry on the source wallet
- Transfer → one `DEBIT` on source + one `CREDIT` on destination

Each entry stores `balanceAfter` so the full balance history is reconstructible.

---

## Timestamps

All timestamps in API responses are in **IST (Asia/Kolkata, UTC+5:30)** in ISO-8601 format:
```
2026-07-04T22:00:00+05:30
```

---

## Frontend

Open `http://localhost:8080` in a browser. Features:

- Register / Login
- Create wallet, freeze/unfreeze
- Add money via payment gateway (UPI / Card / Net Banking)
- Withdraw and transfer
- Transaction history with filters (status, type, date range)
- Dark mode toggle

---

## Postman Collection

Import `FinFlow.postman_collection.json` from `D:\Downloads\` into Postman.

The Login request auto-captures the JWT token. Create Wallet auto-captures the wallet ID. Initiate Payment auto-captures the payment order ID — run the webhook request immediately after to simulate gateway confirmation.
