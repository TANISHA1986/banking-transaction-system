-- ============================================================================
-- Report 5: Complete Transaction History for a Customer
-- ============================================================================
-- Description:
--   Displays the chronological transaction history across all accounts owned
--   by a given customer, including source/destination account numbers,
--   transaction type, amount, status, failure reason, and audit duration.
--
-- Tables Used:
--   - customers (id, full_name, email)
--   - accounts (id, account_number, customer_id)
--   - bank_transactions (id, transaction_id, transaction_type, amount, status, created_at, ...)
--   - transaction_audits (bank_transaction_id, thread_name, duration_ms)
--
-- Sample Usage (psql with customer_id variable):
--   psql -U postgres -d banking_test_db -v customer_id=1 -f database/reports/05_customer_transaction_history.sql
--
-- Note:
--   If running in a SQL client without psql variables, replace :customer_id
--   with a literal integer such as 1.
-- ============================================================================

\if :{?customer_id}
\else
\set customer_id 1
\endif

SELECT DISTINCT
    c.id                    AS customer_id,
    c.full_name             AS customer_name,
    bt.transaction_id       AS transaction_id,
    bt.transaction_type     AS transaction_type,
    bt.amount               AS amount,
    bt.status               AS status,
    src.account_number      AS source_account_number,
    dst.account_number      AS destination_account_number,
    bt.description          AS description,
    bt.failure_reason       AS failure_reason,
    ta.thread_name          AS processed_by_thread,
    ta.duration_ms          AS processing_duration_ms,
    bt.created_at           AS transaction_timestamp
FROM customers c
INNER JOIN accounts cust_acct
    ON cust_acct.customer_id = c.id
INNER JOIN bank_transactions bt
    ON bt.source_account_id = cust_acct.id
    OR bt.destination_account_id = cust_acct.id
LEFT JOIN accounts src
    ON src.id = bt.source_account_id
LEFT JOIN accounts dst
    ON dst.id = bt.destination_account_id
LEFT JOIN transaction_audits ta
    ON ta.bank_transaction_id = bt.id
WHERE c.id = :customer_id
ORDER BY
    bt.created_at DESC,
    bt.transaction_id ASC;
