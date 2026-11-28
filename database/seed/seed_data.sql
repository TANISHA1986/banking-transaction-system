-- ============================================================================
-- Seed Data for Banking Transaction Processing System
-- ============================================================================
-- Populates representative sample data for Customers, Accounts, Transactions,
-- Transaction Audits, and Failed Transactions.
--
-- Safe to re-run: deletes existing seed data and re-inserts.
-- Compatible with PostgreSQL.
--
-- Usage:
--   psql -U postgres -d banking_test_db -f database/seed/seed_data.sql
-- ============================================================================

-- Clean up existing data in reverse FK order
DELETE FROM transaction_audits;
DELETE FROM failed_transactions;
DELETE FROM bank_transactions;
DELETE FROM accounts;
DELETE FROM customers;

-- Reset account number sequence
ALTER SEQUENCE account_number_seq RESTART WITH 1000000001;

-- 1. Insert Customers
INSERT INTO customers (id, full_name, email, phone, created_at) VALUES
(1, 'Aarav Sharma',    'aarav.sharma@example.com',    '9876543210', '2026-11-20 09:00:00'),
(2, 'Priya Patel',     'priya.patel@example.com',     '9876543211', '2026-11-20 09:15:00'),
(3, 'Rohan Verma',     'rohan.verma@example.com',     '9876543212', '2026-11-21 10:00:00'),
(4, 'Ananya Iyer',     'ananya.iyer@example.com',     '9876543213', '2026-11-21 11:30:00'),
(5, 'Vikram Malhotra', 'vikram.malhotra@example.com', '9876543214', '2026-11-22 08:45:00');

SELECT setval('customers_id_seq', (SELECT MAX(id) FROM customers));

-- 2. Insert Accounts
INSERT INTO accounts (id, account_number, customer_id, account_type, balance, status, created_at, updated_at) VALUES
(1, 'NG1000000001', 1, 'SAVINGS', 25000.00, 'ACTIVE',   '2026-11-20 09:05:00', '2026-11-27 10:00:00'),
(2, 'NG1000000002', 1, 'CURRENT', 150000.00, 'ACTIVE',  '2026-11-20 09:10:00', '2026-11-27 10:00:00'),
(3, 'NG1000000003', 2, 'SAVINGS', 45000.00, 'ACTIVE',   '2026-11-20 09:20:00', '2026-11-27 10:00:00'),
(4, 'NG1000000004', 3, 'SAVINGS', 12500.00, 'ACTIVE',   '2026-11-21 10:05:00', '2026-11-27 10:00:00'),
(5, 'NG1000000005', 4, 'SAVINGS',  8000.00, 'ACTIVE',   '2026-11-21 11:35:00', '2026-11-27 10:00:00'),
(6, 'NG1000000006', 5, 'SAVINGS',   500.00, 'FROZEN',   '2026-11-22 08:50:00', '2026-11-22 08:50:00'),
(7, 'NG1000000007', 5, 'CURRENT',     0.00, 'INACTIVE', '2026-11-22 09:00:00', '2026-11-22 09:00:00');

SELECT setval('accounts_id_seq', (SELECT MAX(id) FROM accounts));

-- 3. Insert Bank Transactions
INSERT INTO bank_transactions (id, transaction_id, transaction_type, amount, source_account_id, destination_account_id, status, description, failure_reason, created_at) VALUES
(1,  'TXN-SEED-001', 'DEPOSIT',    10000.00, NULL, 1,    'SUCCESS',  'Initial salary deposit',          NULL,                         '2026-11-23 10:00:00'),
(2,  'TXN-SEED-002', 'DEPOSIT',    50000.00, NULL, 2,    'SUCCESS',  'Business invoice settlement',      NULL,                         '2026-11-23 11:30:00'),
(3,  'TXN-SEED-003', 'TRANSFER',    5000.00, 1,    3,    'SUCCESS',  'Rent payment to Priya',           NULL,                         '2026-11-24 09:15:00'),
(4,  'TXN-SEED-004', 'WITHDRAWAL',  2000.00, 1,    NULL, 'SUCCESS',  'ATM cash withdrawal',             NULL,                         '2026-11-24 14:00:00'),
(5,  'TXN-SEED-005', 'TRANSFER',   15000.00, 2,    1,    'SUCCESS',  'Transfer to personal savings',    NULL,                         '2026-11-25 10:00:00'),
(6,  'TXN-SEED-006', 'WITHDRAWAL', 70000.00, 3,    NULL, 'FAILED',   'ATM cash withdrawal',             'Insufficient balance in account NG1000000003 (available: 45000.00, requested: 70000.00)', '2026-11-25 12:30:00'),
(7,  'TXN-SEED-007', 'DEPOSIT',     5000.00, NULL, 6,    'REJECTED', 'Deposit into frozen account',     'Account NG1000000006 is not ACTIVE (current status: FROZEN)',                               '2026-11-26 09:00:00'),
(8,  'TXN-SEED-008', 'TRANSFER',    2500.00, 4,    5,    'SUCCESS',  'Shared expense reimbursement',    NULL,                         '2026-11-26 15:45:00'),
(9,  'TXN-SEED-009', 'TRANSFER',    1000.00, 5,    4,    'SUCCESS',  'Dinner bill split',               NULL,                         '2026-11-27 08:30:00'),
(10, 'TXN-SEED-010', 'TRANSFER',   30000.00, 5,    1,    'FAILED',   'Transfer to Aarav',               'Insufficient balance in account NG1000000005 (available: 8000.00, requested: 30000.00)',   '2026-11-27 09:00:00');

SELECT setval('bank_transactions_id_seq', (SELECT MAX(id) FROM bank_transactions));

-- 4. Insert Transaction Audits
INSERT INTO transaction_audits (id, bank_transaction_id, transaction_id, event_timestamp, thread_name, duration_ms, event_status, event_details) VALUES
(1,  1,  'TXN-SEED-001', '2026-11-23 10:00:00', 'txn-worker-1', 12, 'SUCCESS',  'Deposited 10000.00 into account NG1000000001'),
(2,  2,  'TXN-SEED-002', '2026-11-23 11:30:00', 'txn-worker-2', 15, 'SUCCESS',  'Deposited 50000.00 into account NG1000000002'),
(3,  3,  'TXN-SEED-003', '2026-11-24 09:15:00', 'txn-worker-3', 22, 'SUCCESS',  'Transferred 5000.00 from NG1000000001 to NG1000000003'),
(4,  4,  'TXN-SEED-004', '2026-11-24 14:00:00', 'txn-worker-1',  8, 'SUCCESS',  'Withdrew 2000.00 from account NG1000000001'),
(5,  5,  'TXN-SEED-005', '2026-11-25 10:00:00', 'txn-worker-4', 19, 'SUCCESS',  'Transferred 15000.00 from NG1000000002 to NG1000000001'),
(6,  6,  'TXN-SEED-006', '2026-11-25 12:30:00', 'txn-worker-2',  6, 'FAILED',   'Insufficient balance in account NG1000000003 (available: 45000.00, requested: 70000.00)'),
(7,  7,  'TXN-SEED-007', '2026-11-26 09:00:00', 'txn-worker-5',  5, 'REJECTED', 'Account NG1000000006 is not ACTIVE (current status: FROZEN)'),
(8,  8,  'TXN-SEED-008', '2026-11-26 15:45:00', 'txn-worker-1', 18, 'SUCCESS',  'Transferred 2500.00 from NG1000000004 to NG1000000005'),
(9,  9,  'TXN-SEED-009', '2026-11-27 08:30:00', 'txn-worker-3', 14, 'SUCCESS',  'Transferred 1000.00 from NG1000000005 to NG1000000004'),
(10, 10, 'TXN-SEED-010', '2026-11-27 09:00:00', 'txn-worker-4',  7, 'FAILED',   'Insufficient balance in account NG1000000005 (available: 8000.00, requested: 30000.00)');

SELECT setval('transaction_audits_id_seq', (SELECT MAX(id) FROM transaction_audits));

-- 5. Insert Failed Transactions
INSERT INTO failed_transactions (id, bank_transaction_id, transaction_id, error_category, failure_reason, failed_at) VALUES
(1, 6,  'TXN-SEED-006', 'INSUFFICIENT_BALANCE', 'Insufficient balance in account NG1000000003 (available: 45000.00, requested: 70000.00)', '2026-11-25 12:30:00'),
(2, 7,  'TXN-SEED-007', 'INACTIVE_ACCOUNT',     'Account NG1000000006 is not ACTIVE (current status: FROZEN)',                               '2026-11-26 09:00:00'),
(3, 10, 'TXN-SEED-010', 'INSUFFICIENT_BALANCE', 'Insufficient balance in account NG1000000005 (available: 8000.00, requested: 30000.00)',   '2026-11-27 09:00:00');

SELECT setval('failed_transactions_id_seq', (SELECT MAX(id) FROM failed_transactions));
