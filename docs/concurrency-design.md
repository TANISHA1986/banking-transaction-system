# Concurrency Design

## Overview

The Banking Transaction Processing System uses **pessimistic database locking** to
ensure that concurrent deposit, withdrawal, and transfer operations are always safe —
even when multiple threads (or HTTP requests) target the same account simultaneously.

---

## Why Concurrency Control Is Necessary

Without any locking, the following race conditions are possible:

### 1. Lost Update (concurrent deposits)

```
Thread A reads balance = 1000
Thread B reads balance = 1000
Thread A adds 500, saves 1500
Thread B adds 500, saves 1500   ← overwrites Thread A's write!
Final balance = 1500 (should be 2000). One deposit is silently lost.
```

### 2. Negative Balance (concurrent withdrawals)

```
Account balance = 1000
Thread A reads balance = 1000  │  Thread B reads balance = 1000
Both pass the balance check
Thread A subtracts 800, saves 200
Thread B subtracts 800, saves -600   ← uses stale read of 1000!
Balance is NEGATIVE — a critical banking bug.
```

### 3. Transfer Deadlock (opposite-direction transfers)

```
Thread A: transfer Account 1 → Account 2 (locks Account 1 first)
Thread B: transfer Account 2 → Account 1 (locks Account 2 first)
Thread A holds lock on 1, waiting for 2
Thread B holds lock on 2, waiting for 1
→ Both wait forever: DEADLOCK
```

### 4. Duplicate Transaction ID Slip-Through

```
Thread A: existsByTransactionId("TXN-X") → false
Thread B: existsByTransactionId("TXN-X") → false (read before A inserts)
Thread A: saves BankTransaction with TXN-X ✓
Thread B: saves BankTransaction with TXN-X → DB constraint violation
                                           → thrown as 500 not 409
```

---

## Solution: Pessimistic Write Locking (`SELECT … FOR UPDATE`)

### How It Works

A **pessimistic write lock** (`SELECT … FOR UPDATE` in PostgreSQL) causes the
database to hold an exclusive row-level lock on the fetched row until the
surrounding transaction either commits or rolls back.

> Any other transaction that tries to read or write the same row is **blocked**
> (not rejected) until the lock is released. This serialises all concurrent
> operations on the same account, completely eliminating lost updates and
> phantom reads.

### Implementation in Spring Data JPA

```java
// AccountRepository.java

@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = "customer")
@Query("SELECT a FROM Account a WHERE a.id = :id")
Optional<Account> findByIdForUpdate(@Param("id") Long id);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = "customer")
@Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
List<Account> findByIdsForUpdate(@Param("ids") List<Long> ids);
```

`@Lock(PESSIMISTIC_WRITE)` instructs Hibernate to append `FOR UPDATE` to the
generated SQL, requesting the lock from PostgreSQL before returning the rows.

---

## Deadlock Prevention: Ordered Locking in Transfers

The classic deadlock scenario occurs when two transfers lock the same two accounts
in **opposite orders**:

```
Thread A locks Account 1, then tries to lock Account 2
Thread B locks Account 2, then tries to lock Account 1  → DEADLOCK
```

**Fix:** Always lock both accounts in the same order — ascending by `id`.

```java
// TransactionService.java — transfer()
List<Long> orderedIds = List.of(
    Math.min(sourceId, destinationId),
    Math.max(sourceId, destinationId)
);
List<Account> lockedAccounts = accountRepository.findByIdsForUpdate(orderedIds);
```

Because every thread acquires locks in ascending ID order, the circular-wait
condition that causes deadlocks **cannot occur**. Both accounts are locked
in a single query (`WHERE id IN (…) ORDER BY id ASC FOR UPDATE`), making
the approach both safe and efficient.

---

## Failure Recording with `REQUIRES_NEW`

### The Problem

With pessimistic locking, when a business failure occurs (e.g. insufficient
balance), the outer `@Transactional` method must roll back to release the lock.
But rolling back also discards any failure records written inside the same
transaction — leaving no audit trail.

### The Solution: `TransactionRecordingService`

A separate Spring bean (`TransactionRecordingService`) uses
`@Transactional(propagation = Propagation.REQUIRES_NEW)` for every record write.

```
Outer transaction (TransactionService):
  1. Acquire lock via SELECT … FOR UPDATE
  2. Run business logic
  3. If failure detected:
       a. Call recorder.recordFailure(...)
          → REQUIRES_NEW: opens its own transaction, writes audit+failure, commits
       b. Throw the business exception
       c. Outer transaction rolls back (releases the lock)
  4. If success:
       a. Mutate balance, save account
       b. Call recorder.recordSuccess(...)
          → REQUIRES_NEW: writes audit, commits
       c. Outer transaction commits
```

This guarantees **every attempted transaction is permanently logged**, even when
the outer transaction rolls back.

---

## Duplicate Transaction ID: Defense in Depth

The application-level check (`existsByTransactionId`) is a **fast-path optimistic
check**, not an atomic gate. Two concurrent threads can both pass the check before
either inserts.

**Defense:** The `bank_transactions.transaction_id` column has a `UNIQUE` constraint
in PostgreSQL. If the race is lost, the DB constraint fires. The service catches
`DataIntegrityViolationException` on save and re-throws it as
`DuplicateTransactionException` (HTTP 409), not a 500 error.

---

## Concurrency Test Coverage

The `TransactionConcurrencyTest` integration test class (runs against
`banking_test_db`) covers:

| Test | Threads | What it proves |
|---|---|---|
| `concurrentDeposits_ShouldAllSucceedAndBalanceIsAccurate` | 10 | No lost updates on deposit |
| `concurrentWithdrawals_ShouldNeverProduceNegativeBalance` | 5 | Balance never goes negative |
| `mandatoryRaceCondition_ExactlyOneWithdrawalShouldSucceed` (×5) | 2 | Exactly 1 withdrawal wins under contention |
| `concurrentDuplicateTransactionIds_ShouldAllowOnlyOne` | 3 | Only 1 duplicate ID accepted |
| `oppositeDirectionTransfers_ShouldCompleteWithoutDeadlock` | 2 | No deadlock on A→B + B→A transfers |

---

## Flow Diagram

```
HTTP Request
    │
    ▼
TransactionService.deposit/withdraw/transfer()
    │  @Transactional(rollbackFor = Exception.class)
    │
    ├── resolveUniqueTransactionId()
    │       └── existsByTransactionId() [fast-path check]
    │
    ├── accountRepository.findByIdForUpdate(id)
    │       └── SELECT a FROM Account a WHERE a.id = :id FOR UPDATE
    │               [PostgreSQL holds exclusive row lock here]
    │
    ├── Business validation (status, balance)
    │       └── on failure → recorder.recordFailure() [REQUIRES_NEW]
    │                        └── commits independently
    │                        └── throw exception → outer tx rolls back → lock released
    │
    ├── Balance mutation + accountRepository.save()
    │
    └── recorder.recordSuccess() [REQUIRES_NEW]
            └── writes BankTransaction + TransactionAudit, commits
            └── outer tx commits → lock released
```

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| Pessimistic over optimistic locking | Banking requires guaranteed serialisation; optimistic locking causes retry storms under contention |
| `SELECT … FOR UPDATE` at service layer | Ensures the balance read and write happen within the same locked transaction |
| Ordered locking for transfers | Prevents deadlocks without application-level retry logic |
| `REQUIRES_NEW` for recording | Audit trail survives even when the outer transaction rolls back |
| `BigDecimal` for all money | Avoids floating-point rounding errors (never use `double`/`float`) |
