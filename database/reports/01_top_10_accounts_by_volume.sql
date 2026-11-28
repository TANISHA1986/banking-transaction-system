-- ============================================================================
-- Report 1: Top 10 Accounts by Transaction Volume
-- ============================================================================
-- Description:
--   Ranks the top 10 accounts by total transaction count (both incoming and
--   outgoing) and total monetary volume across all successful transactions.
--
-- Tables Used:
--   - accounts (id, account_number, account_type, balance, status, customer_id)
--   - customers (id, full_name, email)
--   - bank_transactions (id, source_account_id, destination_account_id, amount, status)
--
-- Sample Usage (psql):
--   psql -U postgres -d banking_test_db -f database/reports/01_top_10_accounts_by_volume.sql
-- ============================================================================

SELECT
    a.id                            AS account_id,
    a.account_number                AS account_number,
    a.account_type                  AS account_type,
    a.status                        AS account_status,
    c.full_name                     AS customer_name,
    a.balance                       AS current_balance,
    COUNT(bt.id)                    AS total_transactions,
    COALESCE(SUM(bt.amount), 0.00)  AS total_monetary_volume
FROM accounts a
INNER JOIN customers c
    ON c.id = a.customer_id
INNER JOIN bank_transactions bt
    ON bt.source_account_id = a.id
    OR bt.destination_account_id = a.id
WHERE bt.status = 'SUCCESS'
GROUP BY
    a.id,
    a.account_number,
    a.account_type,
    a.status,
    c.full_name,
    a.balance
ORDER BY
    total_transactions DESC,
    total_monetary_volume DESC
LIMIT 10;
