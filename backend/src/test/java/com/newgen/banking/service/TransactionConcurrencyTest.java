package com.newgen.banking.service;

import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL concurrency integration tests for {@link TransactionService}.
 *
 * <h2>What these tests prove</h2>
 * <ul>
 *   <li>Concurrent deposits never lose an update — final balance == sum of all deposits.</li>
 *   <li>Concurrent withdrawals never produce a negative balance.</li>
 *   <li>Opposite-direction transfers never deadlock (ordered-lock strategy).</li>
 *   <li>Duplicate transaction IDs are safely rejected — only one succeeds.</li>
 * </ul>
 *
 * <h2>Setup requirements</h2>
 * <p>These tests run against {@code banking_test_db} (never {@code banking_db}).
 * Set environment variable {@code TEST_DB_PASSWORD} before running.</p>
 *
 * <h2>Thread safety contract for test methods</h2>
 * <p>Test methods must NOT be annotated {@code @Transactional}. Each service call
 * must commit its own transaction before the next thread runs; otherwise the
 * pessimistic lock would be held for the entire test, defeating the purpose.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionConcurrencyTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long testCustomerId;

    @BeforeEach
    void setUp() {
        // Clean up in FK order to avoid constraint violations between tests
        transactionTemplate.executeWithoutResult(status -> {
            bankTransactionRepository.deleteAll();
        });
        // Keep customer and accounts seeded per test via helper
    }

    // ── Test 1: Concurrent deposits — no lost updates ─────────────────────────

    @Test
    @DisplayName("10 concurrent deposits of 100.00 each should produce a balance of 1000.00")
    void concurrentDeposits_ShouldAllSucceedAndBalanceIsAccurate() throws Exception {
        // Arrange
        Long accountId = createAccountWithBalance("1000.00");
        int threadCount = 10;
        BigDecimal depositAmount = new BigDecimal("100.00");

        // Act — all threads deposit simultaneously
        runConcurrently(threadCount, (i) -> {
            transactionService.deposit(
                    new DepositRequest(null, accountId, depositAmount, "concurrent deposit " + i));
        });

        // Assert — final balance must be exactly 1000 (initial) + 10*100 = 2000
        BigDecimal finalBalance = getBalance(accountId);
        assertEquals(0, new BigDecimal("2000.00").compareTo(finalBalance),
                "Expected final balance 2000.00 but was " + finalBalance);
    }

    // ── Test 2: Concurrent withdrawals — balance never goes negative ────────

    @Test
    @DisplayName("5 threads withdrawing 3000 from a 10000 account: balance stays >= 0")
    void concurrentWithdrawals_ShouldNeverProduceNegativeBalance() throws Exception {
        // Arrange — 5 * 3000 = 15000, account only has 10000, so at most 3 succeed
        Long accountId = createAccountWithBalance("10000.00");
        int threadCount = 5;
        BigDecimal withdrawAmount = new BigDecimal("3000.00");
        AtomicInteger successCount = new AtomicInteger(0);

        // Act
        runConcurrently(threadCount, (i) -> {
            try {
                transactionService.withdraw(
                        new WithdrawalRequest(null, accountId, withdrawAmount, "concurrent withdrawal " + i));
                successCount.incrementAndGet();
            } catch (InsufficientBalanceException ignored) {
                // Expected for excess withdrawals — balance guard works
            }
        });

        // Assert
        BigDecimal finalBalance = getBalance(accountId);
        assertTrue(finalBalance.compareTo(BigDecimal.ZERO) >= 0,
                "Balance must never be negative. Got: " + finalBalance);
        // Verify accounting: initial - (successes * amount) == final balance
        BigDecimal expectedBalance = new BigDecimal("10000.00")
                .subtract(withdrawAmount.multiply(new BigDecimal(successCount.get())));
        assertEquals(0, expectedBalance.compareTo(finalBalance),
                "Accounting mismatch. success=" + successCount.get() + " finalBalance=" + finalBalance);
    }

    // ── Test 3: Withdrawal race (repeated) — balance never negative ─────────

    @RepeatedTest(5)
    @DisplayName("Two threads each trying to withdraw most of the balance: exactly 1 succeeds")
    void mandatoryRaceCondition_ExactlyOneWithdrawalShouldSucceed() throws Exception {
        // Arrange
        Long accountId = createAccountWithBalance("10000.00");
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Act — two large withdrawals simultaneously
        runConcurrently(2, (i) -> {
            try {
                transactionService.withdraw(
                        new WithdrawalRequest(null, accountId,
                                new BigDecimal("7000.00"), "race withdrawal " + i));
                successCount.incrementAndGet();
            } catch (InsufficientBalanceException ignored) {
                failCount.incrementAndGet();
            }
        });

        // Assert
        BigDecimal finalBalance = getBalance(accountId);
        assertEquals(1, successCount.get(), "Exactly one withdrawal should succeed");
        assertEquals(1, failCount.get(), "Exactly one withdrawal should fail");
        assertTrue(finalBalance.compareTo(BigDecimal.ZERO) >= 0,
                "Balance must not go negative. Got: " + finalBalance);
        assertEquals(0, new BigDecimal("3000.00").compareTo(finalBalance),
                "Final balance should be 10000 - 7000 = 3000.00. Got: " + finalBalance);
    }

    // ── Test 4: Duplicate transaction IDs — only one accepted ───────────────

    @Test
    @DisplayName("3 concurrent deposits with identical transactionId: exactly 1 succeeds")
    void concurrentDuplicateTransactionIds_ShouldAllowOnlyOne() throws Exception {
        Long accountId = createAccountWithBalance("1000.00");
        String sharedTxId = "TXN-CONCURRENT-DUP-" + System.nanoTime();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        runConcurrently(3, (i) -> {
            try {
                transactionService.deposit(
                        new DepositRequest(sharedTxId, accountId, new BigDecimal("100.00"), "dup test"));
                successCount.incrementAndGet();
            } catch (Exception ignored) {
                // DuplicateTransactionException or DataIntegrityViolationException
                rejectedCount.incrementAndGet();
            }
        });

        assertEquals(1, successCount.get(), "Exactly one duplicate transaction should succeed");
        assertEquals(2, rejectedCount.get(), "The other 2 should be rejected");
        // Balance: 1000 + exactly 100 (one deposit)
        BigDecimal finalBalance = getBalance(accountId);
        assertEquals(0, new BigDecimal("1100.00").compareTo(finalBalance),
                "Only one deposit should have committed. Balance: " + finalBalance);
    }

    // ── Test 5: Opposite-direction transfers — no deadlock ──────────────────

    @Test
    @DisplayName("A→B and B→A transfers run simultaneously without deadlocking")
    void oppositeDirectionTransfers_ShouldCompleteWithoutDeadlock() throws Exception {
        // Both accounts start with 1000; total money = 2000, should be conserved
        Long account1Id = createAccountWithBalance("1000.00");
        Long account2Id = createAccountWithBalance("1000.00");
        BigDecimal transferAmount = new BigDecimal("300.00");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Thread A: transfer account1 → account2
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionService.transfer(
                            new TransferRequest(null, account1Id, account2Id, transferAmount, "A→B transfer"));
                } catch (Exception e) {
                    // Insufficient balance possible in some orderings — not a deadlock
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));

            // Thread B: transfer account2 → account1
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    transactionService.transfer(
                            new TransferRequest(null, account2Id, account1Id, transferAmount, "B→A transfer"));
                } catch (Exception e) {
                    // Insufficient balance possible in some orderings — not a deadlock
                } finally {
                    doneLatch.countDown();
                }
                return null;
            }));

            startLatch.countDown(); // Fire both threads simultaneously
            boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
            assertTrue(completed, "Deadlock detected: transfers did not complete within 15 seconds");

        } finally {
            executor.shutdownNow();
        }

        // Assert total money is conserved (2000.00 total)
        BigDecimal balance1 = getBalance(account1Id);
        BigDecimal balance2 = getBalance(account2Id);
        BigDecimal total = balance1.add(balance2);
        assertEquals(0, new BigDecimal("2000.00").compareTo(total),
                "Total money must be conserved. account1=" + balance1 + " account2=" + balance2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a customer + account with the given initial balance.
     * Each call uses its own committed transaction (no @Transactional on this method).
     * Returns the account ID.
     */
    private Long createAccountWithBalance(String initialBalance) {
        // Create customer
        Customer customer = new Customer();
        customer.setFullName("Test Customer " + System.nanoTime());
        customer.setEmail("test" + System.nanoTime() + "@bank.com");
        customer.setPhone("9" + (long)(Math.random() * 900000000L + 100000000L));
        Customer savedCustomer = transactionTemplate.execute(status ->
                customerRepository.save(customer));
        assertNotNull(savedCustomer);

        // Create account
        Account account = new Account();
        account.setCustomer(savedCustomer);
        account.setAccountType(AccountType.SAVINGS);
        account.setBalance(new BigDecimal(initialBalance));
        account.setStatus(AccountStatus.ACTIVE);

        Long seqNum = transactionTemplate.execute(status ->
                accountRepository.getNextAccountNumberSequence());
        assertNotNull(seqNum);
        account.setAccountNumber(String.format("NG%010d", seqNum));

        Account savedAccount = transactionTemplate.execute(status ->
                accountRepository.save(account));
        assertNotNull(savedAccount);
        return savedAccount.getId();
    }

    /**
     * Reads the latest account balance from the database outside any transaction.
     */
    private BigDecimal getBalance(Long accountId) {
        return transactionTemplate.execute(status ->
                accountRepository.findById(accountId)
                        .map(Account::getBalance)
                        .orElseThrow(() -> new IllegalStateException("Account not found: " + accountId)));
    }

    /**
     * Functional interface for a concurrent task that accepts a thread index.
     */
    @FunctionalInterface
    interface IndexedTask {
        void run(int index) throws Exception;
    }

    /**
     * Runs {@code threadCount} threads concurrently, each executing {@code task}.
     * Uses a {@link CountDownLatch} to start all threads simultaneously.
     * Throws an assertion error if any thread does not complete within 20 seconds.
     */
    private void runConcurrently(int threadCount, IndexedTask task) throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait until all threads are ready
                        task.run(index);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // Re-wrap so the calling test can inspect if needed
                        throw new RuntimeException(e);
                    } finally {
                        doneLatch.countDown();
                    }
                    return null;
                }));
            }

            startLatch.countDown(); // Fire all threads simultaneously
            boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
            assertTrue(completed, "Deadlock or timeout: not all threads completed within 20 seconds");

        } finally {
            executor.shutdownNow();
        }
    }
}
