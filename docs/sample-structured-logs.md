# Structured Logging Architecture & Operational Log Samples

## 1. Overview

The **Banking Transaction Processing System** employs SLF4J with Logback to produce structured, human-readable, and machine-parsable application logs. Every mutating operation logs:
- Exact ISO-8601 timestamp with millisecond precision
- Executing thread name (essential for multi-threaded executor debugging, e.g. `txn-worker-1`)
- Business `transactionId`
- Financial operation (`DEPOSIT`, `WITHDRAWAL`, `TRANSFER`)
- Target account number(s)
- Amount transacted (using exact `BigDecimal` formatting)
- Outcome status (`SUCCESS`, `FAILED`, `REJECTED`)
- Categorized error reasons when applicable
- Wall-clock execution duration in milliseconds

> [!NOTE]
> Sensitive customer data (e.g., passwords, complete credit details, security tokens) is **never** emitted to application logs.

---

## 2. Standard Log Pattern

Our Logback configuration formats logs as follows:
```
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{36} : %msg%n
```

---

## 3. Representative Structured Log Samples

### 3.1 Successful Deposit
*Emitted when an account is locked, balance credited, and transaction + audit committed atomically.*
```text
2026-11-28T09:15:01.102+05:30 INFO  [txn-worker-1] c.n.b.service.TransactionRecordingService : Transaction TXN-DEP-20261128-001 (DEPOSIT) committed with status SUCCESS - Account: NG1000000001, Amount: 5000.00, Duration: 12ms
```

### 3.2 Successful Withdrawal
*Emitted after pessimistic lock acquisition, balance check verification, and debit commitment.*
```text
2026-11-28T09:15:02.415+05:30 INFO  [txn-worker-2] c.n.b.service.TransactionRecordingService : Transaction TXN-WDR-20261128-002 (WITHDRAWAL) committed with status SUCCESS - Account: NG1000000001, Amount: 1200.00, Duration: 8ms
```

### 3.3 Successful Multi-Leg Fund Transfer
*Emitted when both source and destination accounts are locked in sorted order, debited/credited, and committed.*
```text
2026-11-28T09:15:03.882+05:30 INFO  [txn-worker-3] c.n.b.service.TransactionRecordingService : Transaction TXN-TRF-20261128-003 (TRANSFER) committed with status SUCCESS - Source: NG1000000001, Destination: NG1000000002, Amount: 750.00, Duration: 18ms
```

### 3.4 Insufficient Balance Business Failure
*Emitted when withdrawal/transfer exceeds available funds. Recorded via `Propagation.REQUIRES_NEW` before outer transaction rolls back.*
```text
2026-11-28T09:15:05.120+05:30 WARN  [txn-worker-4] c.n.b.service.TransactionRecordingService : Transaction TXN-WDR-20261128-004 (WITHDRAWAL) committed with status FAILED: Insufficient balance in account NG1000000003 (available: 450.00, requested: 2000.00) [Category: INSUFFICIENT_BALANCE]
2026-11-28T09:15:05.123+05:30 WARN  [txn-worker-4] c.n.b.service.BatchTransactionService    : Batch BATCH-PAYROLL-01 — task TXN-WDR-20261128-004 completed with business failure: Insufficient balance in account NG1000000003 (available: 450.00, requested: 2000.00)
```

### 3.5 Duplicate Transaction ID Rejection (Idempotency Guard)
*Emitted when an incoming request carries an ID that already exists in `bank_transactions`.*
```text
2026-11-28T09:15:06.004+05:30 WARN  [http-nio-8080-exec-3] c.n.b.service.TransactionService  : Duplicate transaction request rejected for transactionId: TXN-DEP-20261128-001
2026-11-28T09:15:06.005+05:30 WARN  [http-nio-8080-exec-3] c.n.b.e.GlobalExceptionHandler   : Duplicate transaction error: Duplicate transaction request rejected for transactionId: TXN-DEP-20261128-001 [Path: /api/transactions/deposit]
```

### 3.6 Inactive / Frozen Account Rejection
*Emitted when an account row lock is acquired but its lifecycle status is `FROZEN` or `INACTIVE`.*
```text
2026-11-28T09:15:07.450+05:30 WARN  [txn-worker-5] c.n.b.service.TransactionRecordingService : Transaction TXN-DEP-20261128-005 (DEPOSIT) committed with status REJECTED: Account NG1000000006 is not ACTIVE (current status: FROZEN) [Category: INACTIVE_ACCOUNT]
```

### 3.7 Bounded Thread Pool Queue Saturation Rejection
*Emitted when both worker threads and the 500-task queue are full, triggering fail-fast back-pressure.*
```text
2026-11-28T09:15:08.910+05:30 WARN  [main] c.n.b.e.TransactionThreadPoolExecutor : Task rejected: thread pool queue is full (capacity: 500, active: 20, completed: 4210)
2026-11-28T09:15:08.911+05:30 WARN  [main] c.n.b.service.BatchTransactionService : Batch BATCH-STRESS-01 — task rejected (pool saturated): Task rejected: thread pool queue is full (capacity: 500, active: 20, completed: 4210)
```

### 3.8 Batch Processing Summary Metrics
*Emitted upon completion of a batch execution.*
```text
2026-11-28T09:15:09.520+05:30 INFO  [main] c.n.b.service.BatchTransactionService : Batch BATCH-PAYROLL-01 completed in 245ms — submitted=500 accepted=500 rejected=0 successful=480 failed=20 TPS=1959.18
```

---

## 4. Actual Captured Benchmark Log Sample

Below is an authentic sample captured during the progressive benchmark test run (`TransactionBenchmarkTest`):

```text
2026-11-28T08:54:58.210+05:30 INFO  [main] c.n.b.service.TransactionBenchmarkTest : 
╔══════════════════════════════════════════════════════════════╗
  BENCHMARK: Progressive Benchmark — 10 Workers (200 txns)
╠══════════════════════════════════════════════════════════════╣
  Total submitted : 200
  Accepted        : 200
  Rejected        : 0
  Successful      : 200
  Failed          : 0
  Duration (ms)   : 142
  TPS             : 1408.45
  Worker count    : 10
  Queue capacity  : 500
  HikariCP Pool   : total=10 active=0 idle=10 awaiting=0 (max=30)
╚══════════════════════════════════════════════════════════════╝
```
