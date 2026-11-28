# Banking Transaction Processing System

## About the Project

This project is a Java, Spring Boot, and PostgreSQL banking transaction processing system designed to support customer and account management, deposits, withdrawals, fund transfers, concurrent transaction processing, and audit/failure tracking.

## Current Implementation Status (Day 6 — Benchmarking, SQL Reports & Optimization)

Currently implemented:
- Spring Boot Maven backend skeleton (`banking-system`)
- PostgreSQL DDL for `customers` (`001_create_customers.sql`), `accounts` + `account_number_seq` (`002_create_accounts.sql`), and `bank_transactions`, `transaction_audits`, `failed_transactions` (`003_create_transactions.sql`)
- Justified performance index script (`004_performance_indexes.sql`)
- Health check endpoint (`GET /api/v1/health`)
- Customer registration and retrieval APIs (`/api/customers`)
- Account management APIs (`/api/accounts`)
- Core banking transaction APIs (`POST /api/transactions/deposit`, `POST /api/transactions/withdraw`, `POST /api/transactions/transfer`, `GET /api/transactions/{transactionId}`, `GET /api/transactions/account/{accountId}`)
- **Batch transaction API (`POST /api/transactions/batch`)** — submits up to 1,000 transactions concurrently via a bounded thread pool; returns per-item results with TPS metrics
- Idempotent duplicate transaction ID protection, transaction audit logging, and failed/rejected transaction persistence
- Request validation and centralized JSON error handling (`GlobalExceptionHandler`)
- Unit, WebMVC slice, and PostgreSQL integration tests for Customer, Account, and Transaction modules
- **Pessimistic write locking (`SELECT … FOR UPDATE`)** on all balance-mutating operations — prevents lost updates and negative balances under concurrent load
- **Ordered lock acquisition for transfers** — both accounts always locked in ascending ID order, eliminating deadlocks
- **`TransactionRecordingService` with `Propagation.REQUIRED` for success** — atomic commit of balance updates, transaction records, and audit entries on 1 connection
- **`TransactionRecordingService` with `Propagation.REQUIRES_NEW` for failure** — failure and audit records commit independently, surviving outer transaction rollback
- **`TransactionConcurrencyTest`** — PostgreSQL integration tests proving correct behaviour under simultaneous requests (concurrent deposits, withdrawals, duplicate IDs, opposite-direction transfers)
- **`TransactionThreadPoolExecutor`** — bounded `LinkedBlockingQueue`, named worker threads (`txn-worker-N`), `SmartLifecycle` for graceful shutdown, `TaskRejectedException` for back-pressure
- **`BatchTransactionService`** — submits DEPOSIT / WITHDRAWAL / TRANSFER items as `Callable`s to the pool; collects `Future` results; aggregates success / failed / rejected counts
- **`TransactionBenchmarkTest`** (`@Tag("benchmark")`) — progressive benchmarks (1, 5, 10, 20 workers with 200 transfers) + mixed scenarios (50 / 200 / 500 / 1,000 transactions)
- **`StressBenchmark50kTest`** (`@Tag("benchmark-50k")`) — repeatable 50,000-transaction stress benchmark in chunked batches of 500 with 10 active + frozen accounts, measuring throughput and verifying balance conservation
- **Comprehensive SQL Reports (`database/reports/`)** — 5 required reports (Top 10 accounts by volume, failed transactions by reason, daily transaction amount by type, accounts with > N transactions, customer transaction history)
- **Query Performance Analysis (`docs/sql-performance-analysis.md`)** — structural index analysis, before/after execution plans, and justified indexes
- **Sample Seed Data Script (`database/seed/seed_data.sql`)** — repeatable seed data for demo and report verification

## Concurrency Design

All balance-mutating operations (`deposit`, `withdraw`, `transfer`) use **pessimistic
write locking** (`SELECT … FOR UPDATE`) to serialise concurrent access to the same
account row. Full design rationale and flow diagrams are in
[`docs/concurrency-design.md`](docs/concurrency-design.md).

## Thread Pool Design

The bounded thread pool architecture, configuration reference, and batch endpoint usage
examples are documented in [`docs/thread-pool-design.md`](docs/thread-pool-design.md).

## Current Architecture

The backend follows a layered architecture:
- **Controller (`controller/`)**: Exposes REST endpoints, validates incoming DTOs (`@Valid`), and returns HTTP responses.
- **DTO (`dto/`)**: Defines request, response, pagination, and error payloads so JPA entities are not exposed directly.
- **Service (`service/`)**: Implements business rules (input normalization, duplicate email checks, pagination guardrails, not-found handling).
- **Repository (`repository/`)**: Communicates with PostgreSQL via Spring Data JPA.
- **Entity (`entity/`)**: Maps domain objects to PostgreSQL tables.
- **Exception (`exception/`)**: Translates domain and validation exceptions into consistent JSON error responses via `@RestControllerAdvice`.

## Technologies Used

- Java 17 (target compatibility)
- Spring Boot 4.2.0 (WebMVC, Data JPA, Validation, Actuator)
- Maven (via Maven Wrapper `mvnw` / `mvnw.cmd`)
- PostgreSQL
- JUnit 5 & Mockito (automated testing)
- SLF4J and Logback

## Database Setup

1. Ensure PostgreSQL is running locally on port `5432`.
2. Create the database if it does not already exist:
   ```sql
   CREATE DATABASE banking_db;
   ```
3. Run the DDL scripts against `banking_db` in order:
   ```bash
   psql -U postgres -d banking_db -f database/ddl/001_create_customers.sql
   psql -U postgres -d banking_db -f database/ddl/002_create_accounts.sql
   psql -U postgres -d banking_db -f database/ddl/003_create_transactions.sql
   ```
   `spring.jpa.hibernate.ddl-auto` is set to `none`, so schema management is handled explicitly through SQL scripts in `database/ddl/`.

## Environment Variables

Database credentials are read from environment variables in `backend/src/main/resources/application.properties` and must not be hardcoded:

| Variable | Required | Default | Description |
|---|---|---|---|
| `DB_USERNAME` | No | `postgres` | PostgreSQL username (main app) |
| `DB_PASSWORD` | Yes (for `spring-boot:run`) | — | PostgreSQL password (main app) |
| `TEST_DB_USERNAME` | No | `postgres` | PostgreSQL username (isolated `test` profile) |
| `TEST_DB_PASSWORD` | Yes (for integration tests) | — | PostgreSQL password (isolated `test` profile) |

PowerShell example:
```powershell
$env:DB_USERNAME="postgres"
$env:DB_PASSWORD="your_postgres_password"
$env:TEST_DB_USERNAME="postgres"
$env:TEST_DB_PASSWORD="your_postgres_password"
```

### Isolated Test Database (`banking_test_db`)

Integration tests use `backend/src/test/resources/application-test.properties` (`@ActiveProfiles("test")`), which connects **only** to `banking_test_db` and never touches `banking_db`:

```sql
CREATE DATABASE banking_test_db;
```

## How to Run the Backend

From the `backend/` directory:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

To compile and run unit/controller tests:
```powershell
cd backend
.\mvnw.cmd test
```

The backend runs on `http://localhost:8080`.

## Current Implemented APIs

### Health Check
- `GET /api/v1/health` — Returns application status (`200 OK`)
- `GET /actuator/health` — Spring Boot Actuator health endpoint

### Customer APIs
- `POST /api/customers` — Create a new customer (`201 Created`, `400 Bad Request` on validation error, `409 Conflict` on duplicate email)
  - Request body:
    ```json
    {
      "fullName": "Aarav Sharma",
      "email": "aarav.sharma@example.com",
      "phone": "9876543210"
    }
    ```
- `GET /api/customers` — List all customers (`200 OK`)
- `GET /api/customers/paged?page=0&size=20&sortBy=id&sortDir=asc` — Paginated and sorted customer list (`200 OK`, `400 Bad Request` for invalid paging/sort parameters)
- `GET /api/customers/{id}` — Get a customer by ID (`200 OK`, `404 Not Found` if missing, `400 Bad Request` if ID is non-positive or non-numeric)

### Account APIs
- `POST /api/accounts` — Open a new account for an existing customer (`201 Created`, `400 Bad Request` on invalid input/negative balance, `404 Not Found` if customer does not exist)
  - Request body (`initialBalance` is optional and defaults to `0.00`; `accountType` is `SAVINGS` or `CURRENT`):
    ```json
    {
      "customerId": 1,
      "accountType": "SAVINGS",
      "initialBalance": 1500.00
    }
    ```
  - Example response (`201 Created`):
    ```json
    {
      "id": 1,
      "accountNumber": "NG1000000001",
      "customerId": 1,
      "customerName": "Aarav Sharma",
      "accountType": "SAVINGS",
      "balance": 1500.00,
      "status": "ACTIVE",
      "createdAt": "2026-11-26T10:15:30.123456",
      "updatedAt": "2026-11-26T10:15:30.123456"
    }
    ```
- `GET /api/accounts?page=0&size=20&sortBy=id&sortDir=asc` — Paginated and sorted list of accounts (`200 OK`, `400 Bad Request` for invalid paging/sort parameters)
- `GET /api/accounts/{id}` — Get account by database ID (`200 OK`, `404 Not Found` if missing, `400 Bad Request` if non-positive or non-numeric)
- `GET /api/accounts/number/{accountNumber}` — Get account by unique account number (`200 OK`, `404 Not Found` if missing)
- `GET /api/accounts/customer/{customerId}` — List all accounts belonging to a customer (`200 OK`, `404 Not Found` if customer does not exist)

### Running Account Tests
- Unit & MockMvc slice tests (no DB required):
  ```powershell
  cd backend
  .\mvnw.cmd test "-Dtest=AccountServiceTest,AccountControllerTest"
  ```
- Full test suite including PostgreSQL integration tests (requires `banking_test_db` and `TEST_DB_PASSWORD`):
  ```powershell
  cd backend
  .\mvnw.cmd clean test
  ```

### Transaction APIs

#### Transaction Lifecycle
1. **Validation & Idempotency:** Request payload is validated (`amount > 0`, at most 2 decimal places, valid account IDs, source != destination for transfers). If `transactionId` is omitted, the server generates `TXN-<UUID>`; if `transactionId` already exists, the request is rejected with `409 Conflict` without modifying balances.
2. **Account & Balance Checks:** Target accounts must exist (`404 Not Found`) and be `ACTIVE` (`409 Conflict` -> recorded as `REJECTED`). Debits must not exceed balance (`422 Unprocessable Content` -> recorded as `FAILED`).
3. **Persistence:**
   - Successful operations update account balance(s) and save a `SUCCESS` row in `bank_transactions` plus an audit entry in `transaction_audits` inside the same database transaction.
   - Failed/rejected business operations leave account balances untouched while recording the `FAILED`/`REJECTED` transaction, an audit entry, and a categorized `failed_transactions` record.

#### Endpoints
- `POST /api/transactions/deposit` — Deposit money (`201 Created`)
  ```json
  {
    "transactionId": "TXN-20261126-001",
    "accountId": 1,
    "amount": 500.00,
    "description": "Salary deposit"
  }
  ```
- `POST /api/transactions/withdraw` — Withdraw money (`201 Created`, `422 Unprocessable Content` if insufficient balance)
  ```json
  {
    "transactionId": "TXN-20261126-002",
    "accountId": 1,
    "amount": 200.00,
    "description": "ATM withdrawal"
  }
  ```
- `POST /api/transactions/transfer` — Transfer between two distinct accounts (`201 Created`, `400 Bad Request` if source == destination)
  ```json
  {
    "transactionId": "TXN-20261126-003",
    "sourceAccountId": 1,
    "destinationAccountId": 2,
    "amount": 300.00,
    "description": "Rent transfer"
  }
  ```
  - Example response (`201 Created`):
    ```json
    {
      "id": 3,
      "transactionId": "TXN-20261126-003",
      "transactionType": "TRANSFER",
      "amount": 300.00,
      "sourceAccountId": 1,
      "sourceAccountNumber": "NG1000000001",
      "destinationAccountId": 2,
      "destinationAccountNumber": "NG1000000002",
      "status": "SUCCESS",
      "description": "Rent transfer",
      "failureReason": null,
      "createdAt": "2026-11-26T10:35:00.123456"
    }
    ```
- `GET /api/transactions/{transactionId}` — Retrieve a transaction by its unique `transactionId` (`200 OK`, `404 Not Found`)
- `GET /api/transactions/account/{accountId}?page=0&size=20&sortBy=createdAt&sortDir=desc` — Paginated transaction history for an account (`200 OK`, `404 Not Found` if account missing)

### Running Transaction Tests
```powershell
cd backend
.\mvnw.cmd test "-Dtest=TransactionServiceTest,TransactionControllerTest"
```

## Running Benchmarks (Local PostgreSQL)

Ensure PostgreSQL is running and set your local test database password in PowerShell:
```powershell
$env:TEST_DB_PASSWORD="your_postgres_password"
cd backend
```

### 1. Progressive Benchmarks (200 transfers per worker tier)
```powershell
# 1 Worker
.\mvnw.cmd test "-Dtest=TransactionBenchmarkTest#benchmark_1Worker_200Transfers"

# 5 Workers
.\mvnw.cmd test "-Dtest=TransactionBenchmarkTest#benchmark_5Workers_200Transfers"

# 10 Workers
.\mvnw.cmd test "-Dtest=TransactionBenchmarkTest#benchmark_10Workers_200Transfers"

# 20 Workers
.\mvnw.cmd test "-Dtest=TransactionBenchmarkTest#benchmark_20Workers_200Transfers"
```

### 2. 50,000+ Transaction Stress Benchmark
Submits 50,000 mixed transactions (deposits, withdrawals, transfers, and business failures) in controlled chunks of 500 across 10 active accounts:
```powershell
# Run with 10 workers (recommended default)
.\mvnw.cmd test "-Dtest=StressBenchmark50kTest#benchmark50k_10Workers"

# Run with 1, 5, or 20 workers
.\mvnw.cmd test "-Dtest=StressBenchmark50kTest#benchmark50k_1Worker"
.\mvnw.cmd test "-Dtest=StressBenchmark50kTest#benchmark50k_5Workers"
.\mvnw.cmd test "-Dtest=StressBenchmark50kTest#benchmark50k_20Workers"
```

## SQL Reports (`database/reports/`)

Run against PostgreSQL (`banking_test_db` or `banking_db`):
```powershell
$env:PGPASSWORD="your_postgres_password"

# 1. Top 10 accounts by volume
psql -U postgres -d banking_test_db -f database/reports/01_top_10_accounts_by_volume.sql

# 2. Failed transactions grouped by reason
psql -U postgres -d banking_test_db -f database/reports/02_failed_transactions_by_reason.sql

# 3. Daily transaction amount by type
psql -U postgres -d banking_test_db -f database/reports/03_daily_transaction_amount_by_type.sql

# 4. Accounts with more than N transactions
psql -U postgres -d banking_test_db -v min_tx_count=10 -f database/reports/04_accounts_with_more_than_n_transactions.sql

# 5. Customer transaction history
psql -U postgres -d banking_test_db -v customer_id=1 -f database/reports/05_customer_transaction_history.sql
```

## Performance & Gap Analysis Documentation

- [docs/sql-performance-analysis.md](docs/sql-performance-analysis.md) — Query execution plan analysis and index justifications
- [docs/assignment-completion-checklist.md](docs/assignment-completion-checklist.md) — Assignment requirement gap analysis and evidence matrix
- [docs/concurrency-design.md](docs/concurrency-design.md) — Pessimistic locking and deadlock prevention architecture
- [docs/thread-pool-design.md](docs/thread-pool-design.md) — Bounded executor and batch processing design

## Project Structure

See [docs/project-structure.md](docs/project-structure.md) for the complete repository layout.
