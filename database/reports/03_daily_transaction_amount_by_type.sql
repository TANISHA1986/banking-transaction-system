-- ============================================================================
-- Report 3: Daily Transaction Amount Grouped by Transaction Type
-- ============================================================================
-- Description:
--   Calculates daily transaction count, total amount, average amount, min, and
--   max amount grouped by calendar date and transaction_type (DEPOSIT,
--   WITHDRAWAL, TRANSFER) for SUCCESS transactions.
--
-- Tables Used:
--   - bank_transactions (id, transaction_type, amount, status, created_at)
--
-- Sample Usage (psql):
--   psql -U postgres -d banking_test_db -f database/reports/03_daily_transaction_amount_by_type.sql
-- ============================================================================

SELECT
    DATE(bt.created_at)             AS transaction_date,
    bt.transaction_type             AS transaction_type,
    COUNT(bt.id)                    AS transaction_count,
    SUM(bt.amount)                  AS total_amount,
    ROUND(AVG(bt.amount), 2)        AS average_amount,
    MIN(bt.amount)                  AS min_amount,
    MAX(bt.amount)                  AS max_amount
FROM bank_transactions bt
WHERE bt.status = 'SUCCESS'
GROUP BY
    DATE(bt.created_at),
    bt.transaction_type
ORDER BY
    transaction_date DESC,
    bt.transaction_type ASC;
