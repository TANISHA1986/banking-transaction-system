-- ============================================================================
-- Report 4: Accounts with More Than N Transactions
-- ============================================================================
-- Description:
--   Identifies accounts whose total transaction count (incoming or outgoing)
--   exceeds a configurable threshold N (default: 10 in sample query).
--
-- Tables Used:
--   - accounts (id, account_number, account_type, balance, status, customer_id)
--   - customers (id, full_name, email)
--   - bank_transactions (id, source_account_id, destination_account_id, amount, status)
--
-- Sample Usage (psql with variable threshold):
--   psql -U postgres -d banking_test_db -v min_tx_count=10 -f database/reports/04_accounts_with_more_than_n_transactions.sql
--
-- Note:
--   If running in a SQL client without psql variables, replace :min_tx_count
--   with a literal integer such as 10.
-- ============================================================================

\if :{?min_tx_count}
\else
\set min_tx_count 10
\endif

SELECT
    a.id                            AS account_id,
    a.account_number                AS account_number,
    a.account_type                  AS account_type,
    c.full_name                     AS customer_name,
    c.email                         AS customer_email,
    a.balance                       AS current_balance,
    COUNT(bt.id)                    AS transaction_count,
    COALESCE(SUM(bt.amount), 0.00)  AS total_amount_transacted
FROM accounts a
INNER JOIN customers c
    ON c.id = a.customer_id
INNER JOIN bank_transactions bt
    ON bt.source_account_id = a.id
    OR bt.destination_account_id = a.id
GROUP BY
    a.id,
    a.account_number,
    a.account_type,
    c.full_name,
    c.email,
    a.balance
HAVING COUNT(bt.id) > :min_tx_count
ORDER BY
    transaction_count DESC,
    total_amount_transacted DESC;
