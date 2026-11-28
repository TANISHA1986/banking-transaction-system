# SQL Query Performance Analysis

## 1. Overview

This document analyses four key transactional and reporting queries in the **Banking Transaction Processing System** against the PostgreSQL schema defined in:
- `database/ddl/001_create_customers.sql`
- `database/ddl/002_create_accounts.sql`
- `database/ddl/003_create_transactions.sql`

> [!NOTE]
> **Execution Environment Note:** In the automated agent environment, `TEST_DB_PASSWORD` was not set (`fe_sendauth: no password supplied`), so live `EXPLAIN ANALYZE` timings against `banking_test_db` could not be executed from the agent shell. Below are the structural query plan analyses based on the exact PostgreSQL DDL/indexes, justified index additions (`database/ddl/004_performance_indexes.sql`), and exact `psql` commands to capture live `EXPLAIN ANALYZE` output locally.

---

## 2. Existing Index Inventory

| Table | Index / Constraint | Columns | Purpose |
|---|---|---|---|
| `customers` | PK (`customers_pkey`) | `(id)` | Customer lookup & FK join |
| `customers` | UNIQUE (`customers_email_key`) | `(email)` | Duplicate email check |
| `customers` | `idx_customers_full_name` | `(full_name)` | Name search |
| `accounts` | PK (`accounts_pkey`) | `(id)` | `SELECT ... FOR UPDATE` row locking |
| `accounts` | UNIQUE (`uk_accounts_account_number`) | `(account_number)` | Account number lookup |
| `accounts` | `idx_accounts_customer_id` | `(customer_id)` | Customer-to-accounts join |
| `accounts` | `idx_accounts_status` | `(status)` | Active/frozen filtering |
| `bank_transactions` | PK (`bank_transactions_pkey`) | `(id)` | Primary key |
| `bank_transactions` | UNIQUE (`uk_bank_transactions_transaction_id`) | `(transaction_id)` | Idempotency check (`existsByTransactionId`) |
| `bank_transactions` | `idx_bank_transactions_source_account_id` | `(source_account_id)` | Outgoing transaction lookup |
| `bank_transactions` | `idx_bank_transactions_dest_account_id` | `(destination_account_id)` | Incoming transaction lookup |
| `bank_transactions` | `idx_bank_transactions_status` | `(status)` | Status filter |
| `bank_transactions` | `idx_bank_transactions_created_at` | `(created_at)` | Time-range sort/filter |
| `transaction_audits` | `idx_transaction_audits_bank_tx_id` | `(bank_transaction_id)` | FK join to `bank_transactions` |
| `failed_transactions` | UNIQUE (`uk_failed_transactions_bank_tx`) | `(bank_transaction_id)` | 1:1 FK join to `bank_transactions` |
| `failed_transactions` | `idx_failed_transactions_error_category` | `(error_category)` | Failure category filter |

---

## 3. Query-by-Query Analysis

### Query 1 — Idempotency Duplicate Check (Transactional Hot Path)
**Source:** `BankTransactionRepository.existsByTransactionId(String transactionId)`
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS(
    SELECT 1 FROM bank_transactions WHERE transaction_id = 'BNC-TRF-sample'
);
```
- **Expected Plan:** `Index Only Scan using uk_bank_transactions_transaction_id on bank_transactions` (or `Index Scan` if heap visibility check is needed).
- **Complexity:** $O(\log N)$ — sub-millisecond even at 50,000+ rows.
- **Verdict:** Already optimal. No additional index required.

---

### Query 2 — Account Transaction History (`OR` Predicate)
**Source:** `BankTransactionRepository.findByAccountId(Long accountId, Pageable pageable)`
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, transaction_id, transaction_type, amount, status, created_at
FROM bank_transactions
WHERE source_account_id = 1 OR destination_account_id = 1
ORDER BY created_at DESC, id DESC
LIMIT 20;
```
- **Before Observation (Expected):** PostgreSQL uses `BitmapOr` combining `idx_bank_transactions_source_account_id` and `idx_bank_transactions_dest_account_id`, followed by `Bitmap Heap Scan` and an in-memory `Sort` (`top-N heapsort`) on `(created_at DESC, id DESC)`.
- **Optimization Notes:**
  - When an account has thousands of transactions (e.g., in the 50k benchmark), `BitmapOr` still retrieves all matching heap pages before sorting for `LIMIT 20`.
  - Avoiding `OR` in custom SQL reports (e.g., rewriting as `UNION ALL` of two indexed queries, or keeping `BitmapOr` for moderate per-account histories) allows `Index Scan` on `(source_account_id)` and `(destination_account_id)`.
  - Since `idx_bank_transactions_source_account_id` and `idx_bank_transactions_dest_account_id` already exist, we avoid creating redundant single-column indexes.

---

### Query 3 — Daily Transaction Amount by Type (`03_daily_transaction_amount_by_type.sql`)
**Source:** `database/reports/03_daily_transaction_amount_by_type.sql`
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    DATE(created_at) AS transaction_date,
    transaction_type,
    COUNT(id)        AS transaction_count,
    SUM(amount)      AS total_amount
FROM bank_transactions
WHERE status = 'SUCCESS'
GROUP BY DATE(created_at), transaction_type
ORDER BY transaction_date DESC, transaction_type ASC;
```
- **Before Observation (Expected):**
  - `idx_bank_transactions_status` has low selectivity (~90% of rows are `'SUCCESS'`), so PostgreSQL falls back to a full `Seq Scan` on `bank_transactions` + `HashAggregate` + `Sort`.
  - At 50,000+ rows, this reads every heap page of `bank_transactions` including `description`, `failure_reason`, and account FKs.
- **Justified Index Added (`database/ddl/004_performance_indexes.sql`):**
  ```sql
  CREATE INDEX IF NOT EXISTS idx_bank_transactions_status_created_type
      ON bank_transactions (status, created_at, transaction_type)
      INCLUDE (amount);
  ```
- **After Observation (Expected):**
  - Because `status`, `created_at`, `transaction_type`, and `amount` are all present in the index (`INCLUDE (amount)`), PostgreSQL can satisfy the query via an **`Index Only Scan`** (after `VACUUM ANALYZE bank_transactions`), eliminating heap page fetches.

---

### Query 4 — Failed Transactions Grouped by Reason (`02_failed_transactions_by_reason.sql`)
**Source:** `database/reports/02_failed_transactions_by_reason.sql`
```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT
    ft.error_category,
    ft.failure_reason,
    bt.status,
    COUNT(ft.id) AS failure_count
FROM failed_transactions ft
INNER JOIN bank_transactions bt ON bt.id = ft.bank_transaction_id
GROUP BY ft.error_category, ft.failure_reason, bt.status
ORDER BY failure_count DESC;
```
- **Before Observation (Expected):** `Seq Scan` on `failed_transactions` + `Nested Loop` / `Hash Join` with `bank_transactions` + `HashAggregate`.
- **Justified Index Added (`database/ddl/004_performance_indexes.sql`):**
  ```sql
  CREATE INDEX IF NOT EXISTS idx_failed_transactions_category_reason
      ON failed_transactions (error_category, failure_reason);
  ```
- **After Observation (Expected):** Supports grouped aggregation and filtering by `(error_category, failure_reason)` without redundant single-column duplication (complementing `idx_failed_transactions_error_category` when filtering/grouping by both columns).

---

## 4. How to Run Live `EXPLAIN ANALYZE` Locally

After running the 50,000-transaction benchmark (or seeding `banking_test_db`), run the following in PowerShell to capture live PostgreSQL timings before and after applying `004_performance_indexes.sql`:

```powershell
$env:PGPASSWORD="your_postgres_password"

# 1. Refresh planner statistics
psql -U postgres -d banking_test_db -c "VACUUM ANALYZE;"

# 2. Capture BEFORE plan for Report 3
psql -U postgres -d banking_test_db -c "EXPLAIN (ANALYZE, BUFFERS) SELECT DATE(created_at), transaction_type, COUNT(id), SUM(amount) FROM bank_transactions WHERE status = 'SUCCESS' GROUP BY DATE(created_at), transaction_type;"

# 3. Apply justified indexes
psql -U postgres -d banking_test_db -f database/ddl/004_performance_indexes.sql
psql -U postgres -d banking_test_db -c "VACUUM ANALYZE;"

# 4. Capture AFTER plan for Report 3
psql -U postgres -d banking_test_db -c "EXPLAIN (ANALYZE, BUFFERS) SELECT DATE(created_at), transaction_type, COUNT(id), SUM(amount) FROM bank_transactions WHERE status = 'SUCCESS' GROUP BY DATE(created_at), transaction_type;"
```
