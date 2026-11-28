-- ============================================================================
-- Optional Performance Indexes (Justified by Query Plan Analysis)
-- ============================================================================
-- Apply only after reviewing docs/sql-performance-analysis.md.
-- Uses IF NOT EXISTS so the script is safe and idempotent.
-- ============================================================================

-- 1. Composite index for daily transaction volume by type filtered by status
--    Supports: 03_daily_transaction_amount_by_type.sql (Index Only Scan candidate)
CREATE INDEX IF NOT EXISTS idx_bank_transactions_status_created_type
    ON bank_transactions (status, created_at, transaction_type)
    INCLUDE (amount);

-- 2. Composite index for failed transactions grouped by error_category & failure_reason
--    Supports: 02_failed_transactions_by_reason.sql
CREATE INDEX IF NOT EXISTS idx_failed_transactions_category_reason
    ON failed_transactions (error_category, failure_reason);
