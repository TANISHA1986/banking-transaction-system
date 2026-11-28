# System Architecture & Technical Flow

## 1. Overview

The **Multi-Threaded Banking Transaction Processing System** is built on a high-throughput, concurrency-safe, layered architecture designed with Spring Boot 4.2, Java 17, Spring Data JPA, and PostgreSQL. It delivers strict ACID guarantees, deadlock-free concurrent transaction processing, application- and database-level idempotency, and asynchronous batch execution via a custom bounded thread pool.

---

## 2. Layered Architecture Diagram

```mermaid
flowchart TD
    subgraph ClientLayer ["1. Client & API Consumers"]
        HTTP_CLIENT["REST Clients / Postman / Benchmark Harness"]
    end

    subgraph ControllerLayer ["2. Web & REST Controller Layer"]
        CUST_CTRL["CustomerController<br/><code>/api/customers</code>"]
        ACCT_CTRL["AccountController<br/><code>/api/accounts</code>"]
        TXN_CTRL["TransactionController<br/><code>/api/transactions</code>"]
        BATCH_CTRL["BatchTransactionController<br/><code>/api/transactions/batch</code>"]
        HEALTH_CTRL["HealthController<br/><code>/api/v1/health</code>"]
        VAL_ADVICE["GlobalExceptionHandler<br/><code>@RestControllerAdvice</code>"]
    end

    subgraph ServiceLayer ["3. Business Logic & Transaction Layer"]
        CUST_SRV["CustomerService"]
        ACCT_SRV["AccountService"]
        TXN_SRV["TransactionService<br/><code>@Transactional(rollbackFor = Exception.class)</code><br/><i>Pessimistic Locking & Deadlock Safety</i>"]
        REC_SRV["TransactionRecordingService<br/><code>recordSuccess: Propagation.REQUIRED</code><br/><code>recordFailure: Propagation.REQUIRES_NEW</code>"]
        BATCH_SRV["BatchTransactionService<br/><i>Chunked Submission & Future Result Collector</i>"]
    end

    subgraph ConcurrencyLayer ["4. Concurrency & Thread Pool"]
        POOL_EXEC["TransactionThreadPoolExecutor<br/><code>SmartLifecycle</code> Management"]
        WORKER_QUEUE["LinkedBlockingQueue<br/><code>capacity = 500</code><br/><i>Bounded Queue Back-Pressure</i>"]
        WORKER_THREADS["Worker Threads<br/><code>txn-worker-1 ... txn-worker-20</code>"]
        REJECT_HANDLER["TaskRejectedException<br/><i>Fail-Fast Rejection Placeholder</i>"]
    end

    subgraph PersistenceLayer ["5. Data Access & Repositories"]
        CUST_REPO["CustomerRepository"]
        ACCT_REPO["AccountRepository<br/><code>findByIdForUpdate</code><br/><code>findByIdsForUpdate</code>"]
        TXN_REPO["BankTransactionRepository<br/><code>existsByTransactionId</code>"]
        AUDIT_REPO["TransactionAuditRepository"]
        FAILED_REPO["FailedTransactionRepository"]
    end

    subgraph DBLayer ["6. PostgreSQL Database (ACID Storage)"]
        HIKARI["HikariCP Connection Pool<br/><code>max-pool-size = 30</code><br/><code>min-idle = 10</code>"]
        TBL_CUST[("customers")]
        TBL_ACCT[("accounts<br/><code>CHECK (balance >= 0)</code>")]
        TBL_TXN[("bank_transactions<br/><code>UNIQUE(transaction_id)</code>")]
        TBL_AUDIT[("transaction_audits")]
        TBL_FAILED[("failed_transactions")]
    end

    %% Invocations
    HTTP_CLIENT -->|HTTP POST/GET| CUST_CTRL
    HTTP_CLIENT -->|HTTP POST/GET| ACCT_CTRL
    HTTP_CLIENT -->|HTTP POST/GET| TXN_CTRL
    HTTP_CLIENT -->|HTTP POST| BATCH_CTRL
    HTTP_CLIENT -->|HTTP GET| HEALTH_CTRL

    CUST_CTRL --> CUST_SRV
    ACCT_CTRL --> ACCT_SRV
    TXN_CTRL --> TXN_SRV
    BATCH_CTRL --> BATCH_SRV

    BATCH_SRV -->|Submit Callable Tasks| POOL_EXEC
    POOL_EXEC -->|Enqueue| WORKER_QUEUE
    WORKER_QUEUE -->|Execute in Worker Thread| WORKER_THREADS
    WORKER_THREADS -->|Invoke Business Logic| TXN_SRV
    POOL_EXEC -.->|Queue Full| REJECT_HANDLER
    REJECT_HANDLER -.->|Status: REJECTED| BATCH_SRV

    TXN_SRV -->|Record Success / Audit| REC_SRV
    TXN_SRV -->|Record Failure / Audit| REC_SRV

    CUST_SRV --> CUST_REPO
    ACCT_SRV --> ACCT_REPO
    TXN_SRV --> ACCT_REPO
    TXN_SRV --> TXN_REPO
    REC_SRV --> TXN_REPO
    REC_SRV --> AUDIT_REPO
    REC_SRV --> FAILED_REPO

    CUST_REPO --> HIKARI
    ACCT_REPO --> HIKARI
    TXN_REPO --> HIKARI
    AUDIT_REPO --> HIKARI
    FAILED_REPO --> HIKARI

    HIKARI --> TBL_CUST
    HIKARI --> TBL_ACCT
    HIKARI --> TBL_TXN
    HIKARI --> TBL_AUDIT
    HIKARI --> TBL_FAILED

    VAL_ADVICE -.->|Error JSON| HTTP_CLIENT
```

---

## 3. Core Transfer Request Flow with Pessimistic Locking

The diagram below illustrates the exact end-to-end flow of a fund transfer from Account A to Account B, highlighting the deterministic lock acquisition order that guarantees deadlock prevention.

```mermaid
sequenceDiagram
    autonumber
    actor Client as API Client / Worker Thread
    participant Ctrl as TransactionController
    participant TxnSrv as TransactionService
    participant AcctRepo as AccountRepository
    participant RecSrv as TransactionRecordingService
    participant TxnRepo as BankTransactionRepository
    participant DB as PostgreSQL Database

    Client->>Ctrl: POST /api/transactions/transfer (TransferRequest)
    Ctrl->>TxnSrv: transfer(TransferRequest)
    
    activate TxnSrv
    Note over TxnSrv: 1. Input Validation & Amount Normalization
    TxnSrv->>TxnRepo: existsByTransactionId(transactionId)
    TxnRepo-->>TxnSrv: false (Idempotency pre-check passed)

    Note over TxnSrv: 2. Sort Account IDs: minId = min(src, dst), maxId = max(src, dst)
    TxnSrv->>AcctRepo: findByIdsForUpdate([minId, maxId])
    AcctRepo->>DB: SELECT * FROM accounts WHERE id IN (minId, maxId) ORDER BY id ASC FOR UPDATE
    DB-->>AcctRepo: [Locked Account Rows]
    AcctRepo-->>TxnSrv: List<Account> (Both rows exclusively locked)

    Note over TxnSrv: 3. Validate Account Status (ACTIVE) & Balance (src.balance >= amount)
    alt Insufficient Balance or Inactive Account
        TxnSrv->>RecSrv: recordFailure(transactionId, TRANSFER, amount, src, dst, FAILED, reason)
        activate RecSrv
        Note over RecSrv: Propagation.REQUIRES_NEW (Independent DB Connection)
        RecSrv->>DB: INSERT INTO bank_transactions (status='FAILED')
        RecSrv->>DB: INSERT INTO transaction_audits
        RecSrv->>DB: INSERT INTO failed_transactions
        RecSrv-->>TxnSrv: BankTransaction
        deactivate RecSrv
        TxnSrv-->>Ctrl: throw InsufficientBalanceException / InactiveAccountException
        Ctrl-->>Client: 422 Unprocessable Content / 400 Bad Request
    else Balance Sufficient & Accounts Active
        Note over TxnSrv: 4. Mutate Balances: src.debit(amount), dst.credit(amount)
        TxnSrv->>AcctRepo: save(src)
        TxnSrv->>AcctRepo: save(dst)
        AcctRepo->>DB: UPDATE accounts SET balance = ... WHERE id = src.id
        AcctRepo->>DB: UPDATE accounts SET balance = ... WHERE id = dst.id

        Note over TxnSrv: 5. Record Success & Audit (Propagation.REQUIRED)
        TxnSrv->>RecSrv: recordSuccess(transactionId, TRANSFER, amount, src, dst, ...)
        activate RecSrv
        RecSrv->>DB: INSERT INTO bank_transactions (status='SUCCESS')
        RecSrv->>DB: INSERT INTO transaction_audits
        RecSrv-->>TxnSrv: BankTransaction
        deactivate RecSrv

        Note over TxnSrv: 6. Outer Transaction Commits Atomic Writes
        TxnSrv-->>Ctrl: TransactionResponse
        deactivate TxnSrv
        Ctrl-->>Client: 201 Created (TransactionResponse JSON)
    end
```

---

## 4. Batch Transaction Processing Architecture

The batch transaction endpoint allows high-volume concurrent processing through controlled thread pool submission.

```mermaid
flowchart LR
    subgraph Ingest ["Batch Ingestion"]
        REQ["POST /api/transactions/batch<br/><code>BatchTransactionRequest</code><br/>(1 to 1,000 items)"]
    end

    subgraph Service ["BatchTransactionService"]
        DISPATCH["Submit Loop<br/>Each item mapped to Callable"]
        COLLECT["Future.get(timeout=60s)<br/>Result Aggregation"]
    end

    subgraph Pool ["TransactionThreadPoolExecutor"]
        QUEUE["LinkedBlockingQueue<br/>(capacity = 500)"]
        W1["Worker 1"]
        W2["Worker 2"]
        W3["Worker ..."]
        WN["Worker N (max 20)"]
    end

    subgraph Engine ["Transaction Engine"]
        CORE["TransactionService<br/>Pessimistic Lock & DB Commit"]
    end

    REQ --> DISPATCH
    DISPATCH -->|Submit task| QUEUE
    QUEUE --> W1
    QUEUE --> W2
    QUEUE --> W3
    QUEUE --> WN
    W1 --> CORE
    W2 --> CORE
    W3 --> CORE
    WN --> CORE
    CORE --> COLLECT
    DISPATCH -.->|Queue Full| REJ["TaskRejectedException<br/>status: REJECTED"]
    REJ -.-> COLLECT
    COLLECT --> RESP["BatchTransactionResult<br/>totalSubmitted, accepted, rejected,<br/>successful, failed, durationMs, TPS"]
```

---

## 5. Architectural Quality Attributes

| Quality Attribute | Architectural Solution | Implementation Evidence |
|---|---|---|
| **Data Consistency & ACID** | Pessimistic locking (`SELECT ... FOR UPDATE`) before balance modification; strict non-negative check constraint. | `AccountRepository.findByIdForUpdate`, `002_create_accounts.sql` |
| **Deadlock Freedom** | Sorted account ID locking in transfers (`Math.min(srcId, dstId)` locked first). | `TransactionService.transfer()` lines 208–213 |
| **Idempotency** | Double-layer duplicate prevention: memory/query check (`existsByTransactionId`) + DB unique constraint (`uk_bank_transactions_transaction_id`). | `TransactionService.resolveUniqueTransactionId`, `TransactionRecordingService.saveBankTransaction` |
| **Auditability** | Every operation writes an immutable `transaction_audits` entry containing duration in milliseconds and executing worker thread name. | `TransactionRecordingService.saveAudit`, `transaction_audits` table |
| **Failure Durability** | Failed transactions are saved with categorized reasons (`INSUFFICIENT_BALANCE`, `INACTIVE_ACCOUNT`) using `Propagation.REQUIRES_NEW`, surviving outer rollback. | `TransactionRecordingService.recordFailure`, `failed_transactions` table |
| **High Concurrency & Back-Pressure** | Bounded `LinkedBlockingQueue` with graceful rejection via `TaskRejectedException`. | `TransactionThreadPoolExecutor.java`, `BatchTransactionService.java` |
| **Resource Efficiency** | Single connection reused for successful operations (`Propagation.REQUIRED`), eliminating Hikari connection pool deadlocks. | `TransactionRecordingService.recordSuccess`, Hikari pool max: 30 |
