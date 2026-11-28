-- ============================================================================
-- Report 2: Failed Transactions Grouped by Reason
-- ============================================================================
-- Description:
--   Aggregates all failed and rejected transactions by error_category and
--   failure_reason, showing total failure count, percentage share, and the
--   most recent occurrence timestamp.
--
-- Tables Used:
--   - failed_transactions (id, bank_transaction_id, error_category, failure_reason, failed_at)
--   - bank_transactions (id, amount, status)
--
-- Sample Usage (psql):
--   psql -U postgres -d banking_test_db -f database/reports/02_failed_transactions_by_reason.sql
-- ============================================================================

SELECT
    ft.error_category                                                   AS error_category,
    ft.failure_reason                                                   AS failure_reason,
    bt.status                                                           AS transaction_status,
    COUNT(ft.id)                                                        AS failure_count,
    COALESCE(SUM(bt.amount), 0.00)                                      AS attempted_amount,
    ROUND(
        COUNT(ft.id) * 100.0 / NULLIF(SUM(COUNT(ft.id)) OVER (), 0),
        2
    )                                                                   AS percentage_of_failures,
    MIN(ft.failed_at)                                                   AS first_occurred_at,
    MAX(ft.failed_at)                                                   AS last_occurred_at
FROM failed_transactions ft
INNER JOIN bank_transactions bt
    ON bt.id = ft.bank_transaction_id
GROUP BY
    ft.error_category,
    ft.failure_reason,
    bt.status
ORDER BY
    failure_count DESC,
    last_occurred_at DESC;
