package com.newgen.banking.service;

import com.newgen.banking.dto.BatchTransactionItem;
import com.newgen.banking.dto.BatchTransactionRequest;
import com.newgen.banking.dto.BatchTransactionResult;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.executor.TransactionExecutorProperties;
import com.newgen.banking.executor.TransactionThreadPoolExecutor;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.CustomerRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.newgen.banking.entity.TransactionType.DEPOSIT;
import static com.newgen.banking.entity.TransactionType.TRANSFER;
import static com.newgen.banking.entity.TransactionType.WITHDRAWAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark for the bounded thread pool + {@link BatchTransactionService}.
 *
 * <h2>Purpose</h2>
 * <p>Submits realistic mixed-transaction batches (deposit / withdrawal / transfer) and
 * measures wall-clock throughput (TPS). The results are printed as a formatted table to
 * standard output — they are <em>not</em> strict assertions (latency is environment-
 * dependent), only correctness assertions (balance ≥ 0, counts add up) are enforced.</p>
 *
 * <h2>Scenarios</h2>
 * <table border="1">
 *   <tr><th>Scenario</th><th>Transactions</th><th>Notes</th></tr>
 *   <tr><td>Small</td>  <td>50</td>  <td>Warm-up / correctness</td></tr>
 *   <tr><td>Medium</td> <td>200</td> <td>Moderate concurrent load</td></tr>
 *   <tr><td>Large</td>  <td>500</td> <td>Heavier load, many TRANSFERS</td></tr>
 *   <tr><td>Stress</td> <td>1000</td><td>Max batch size; pure DEPOSIT</td></tr>
 * </table>
 *
 * <h2>Tag</h2>
 * <p>Annotated {@code @Tag("benchmark")} so CI can exclude it with
 * {@code -Dgroups='!benchmark'} when a full DB is unavailable.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
class TransactionBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionBenchmarkTest.class);

    @Autowired private BatchTransactionService batchTransactionService;
    @Autowired private TransactionService transactionService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private BankTransactionRepository bankTransactionRepository;
    @Autowired private TransactionAuditRepository transactionAuditRepository;
    @Autowired private FailedTransactionRepository failedTransactionRepository;
    @Autowired private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        transactionAuditRepository.deleteAllInBatch();
        failedTransactionRepository.deleteAllInBatch();
        bankTransactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
    }

    // ── Scenario 1 — Small batch (50 deposits) ────────────────────────────────

    @Test
    void benchmark_SmallBatch_50Deposits() {
        Customer c = saveCustomer("Bench Small");
        Account account = saveAccount(c, new BigDecimal("100000.00"), AccountStatus.ACTIVE);

        List<BatchTransactionItem> items = buildDeposits(account.getId(), 50);
        BatchTransactionResult result = run("BENCH-SMALL-50", items);

        printReport("Small — 50 deposits", result);
        assertCorrectness(result, 50);
    }

    // ── Scenario 2 — Medium batch (200 mixed) ────────────────────────────────

    @Test
    void benchmark_MediumBatch_200Mixed() {
        Customer c1 = saveCustomer("Bench Med Source");
        Customer c2 = saveCustomer("Bench Med Dest");
        Account src = saveAccount(c1, new BigDecimal("500000.00"), AccountStatus.ACTIVE);
        Account dst = saveAccount(c2, new BigDecimal("100000.00"), AccountStatus.ACTIVE);

        List<BatchTransactionItem> items = buildMixed(src.getId(), dst.getId(), 200);
        BatchTransactionResult result = run("BENCH-MEDIUM-200", items);

        printReport("Medium — 200 mixed (deposit/withdrawal/transfer)", result);
        // Not all may succeed (withdrawals might fail if balance runs low)
        int total = result.successful() + result.failed() + result.rejected();
        assertTrue(total == result.totalSubmitted(),
                "successful + failed + rejected must equal totalSubmitted");
        assertTrue(accountRepository.findById(src.getId()).orElseThrow()
                .getBalance().compareTo(BigDecimal.ZERO) >= 0,
                "Source balance must remain non-negative");
    }

    // ── Scenario 3 — Large batch (500 transfers) ─────────────────────────────

    @Test
    void benchmark_LargeBatch_500Transfers() {
        Customer c1 = saveCustomer("Bench Large Source");
        Customer c2 = saveCustomer("Bench Large Dest");
        // Large initial balance so most transfers succeed
        Account src = saveAccount(c1, new BigDecimal("5000000.00"), AccountStatus.ACTIVE);
        Account dst = saveAccount(c2, new BigDecimal("0.00"), AccountStatus.ACTIVE);

        List<BatchTransactionItem> items = buildTransfers(src.getId(), dst.getId(), 500, new BigDecimal("10.00"));
        BatchTransactionResult result = run("BENCH-LARGE-500", items);

        printReport("Large — 500 transfers (10.00 each)", result);

        // 500 × 10 = 5000 total. Source started at 5,000,000; must remain >= 0
        BigDecimal srcBalance = accountRepository.findById(src.getId()).orElseThrow().getBalance();
        assertTrue(srcBalance.compareTo(BigDecimal.ZERO) >= 0,
                "Source balance must remain non-negative: " + srcBalance);
        int total = result.successful() + result.failed() + result.rejected();
        assertTrue(total == result.totalSubmitted(), "Counts must add up");
    }

    // ── Scenario 4 — Stress batch (1000 deposits, max allowed) ───────────────

    @Test
    void benchmark_StressBatch_1000Deposits() {
        Customer c = saveCustomer("Bench Stress");
        Account account = saveAccount(c, new BigDecimal("0.00"), AccountStatus.ACTIVE);

        List<BatchTransactionItem> items = buildDeposits(account.getId(), 1000);
        BatchTransactionResult result = run("BENCH-STRESS-1000", items);

        printReport("Stress — 1000 deposits (max batch size)", result);
        assertCorrectness(result, 1000);

        assertEquals(1000, result.totalSubmitted(), "Total submitted must be 1000");
        assertEquals(1000, result.accepted(), "All 1000 transactions must be accepted via bounded backpressure");
        assertEquals(0, result.rejected(), "0 transactions should be rejected in a valid 1000 batch");
        assertEquals(1000, result.successful(), "All 1000 deposits must succeed");
        assertEquals(0, result.failed(), "0 deposits should fail");

        // 1000 × 1.00 = 1000.00 added
        BigDecimal balance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        // All 1000 must succeed because deposits never fail on balance
        assertBigDecimalEquals(new BigDecimal("1000.00"), balance,
                "All 1000 deposits must have been applied, balance should be 1000.00");
    }

    // ── Scenario 5 — Genuine Queue Saturation Test ───────────────────────────

    @Test
    void benchmark_GenuineQueueSaturation_WhenExternalLoadExhaustsQueue() {
        TransactionExecutorProperties tinyProps = new TransactionExecutorProperties();
        tinyProps.setCorePoolSize(1);
        tinyProps.setMaxPoolSize(1);
        tinyProps.setQueueCapacity(2);
        tinyProps.setThreadNamePrefix("sat-test-");
        TransactionThreadPoolExecutor tinyPool = new TransactionThreadPoolExecutor(tinyProps);
        tinyPool.start();

        try {
            Customer c = saveCustomer("Bench Saturation");
            Account account = saveAccount(c, new BigDecimal("0.00"), AccountStatus.ACTIVE);

            // Submit 3 blocking background tasks directly to tinyPool to completely fill 1 worker + 2 queue slots
            java.util.concurrent.CountDownLatch blockerLatch = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 3; i++) {
                tinyPool.submit(() -> {
                    blockerLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    return null;
                });
            }

            // Pool now has 1 active thread + 2 queued tasks = 3 (100% full).
            // Initial submissions to tinyPool will throw TaskRejectedException.
            BatchTransactionService saturatedBatchService = new BatchTransactionService(transactionService, tinyPool);
            List<BatchTransactionItem> items = buildDeposits(account.getId(), 5);
            BatchTransactionResult result = saturatedBatchService.processBatch(
                    new BatchTransactionRequest("BENCH-SATURATION", items)
            );

            blockerLatch.countDown();

            printReport("Genuine Saturation — External load filling pool", result);

            // Verify that genuine external saturation produces rejections gracefully
            assertTrue(result.rejected() > 0, "External queue saturation must result in task rejections");
            assertEquals(5, result.totalSubmitted());
            assertEquals(result.accepted() + result.rejected(), result.totalSubmitted());

            // Balance must match only the successful deposits, zero lost transactions or double counts
            BigDecimal expectedBalance = new BigDecimal("1.00").multiply(BigDecimal.valueOf(result.successful()));
            BigDecimal actualBalance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
            assertBigDecimalEquals(expectedBalance, actualBalance, "Balance must strictly match successful count");
        } finally {
            tinyPool.stop();
        }
    }

    // ── Progressive Worker Benchmarks ────────────────────────────────────────

    @Test
    void benchmark_1Worker_20Transfers() {
        runWorkerBenchmark(1, 20, new BigDecimal("1.00"), new BigDecimal("1000.00"));
    }

    @Test
    void benchmark_5Workers_20Transfers() {
        runWorkerBenchmark(5, 20, new BigDecimal("1.00"), new BigDecimal("1000.00"));
    }

    @Test
    void benchmark_1Worker_200Transfers() {
        runWorkerBenchmark(1, 200, new BigDecimal("1.00"), new BigDecimal("10000.00"));
    }

    @Test
    void benchmark_5Workers_200Transfers() {
        runWorkerBenchmark(5, 200, new BigDecimal("1.00"), new BigDecimal("10000.00"));
    }

    @Test
    void benchmark_10Workers_200Transfers() {
        runWorkerBenchmark(10, 200, new BigDecimal("1.00"), new BigDecimal("10000.00"));
    }

    @Test
    void benchmark_20Workers_200Transfers() {
        runWorkerBenchmark(20, 200, new BigDecimal("1.00"), new BigDecimal("10000.00"));
    }

    private void runWorkerBenchmark(int workers, int txnCount, BigDecimal transferAmount, BigDecimal initialSrcBalance) {
        Customer c1 = saveCustomer("Bench " + workers + "w Src");
        Customer c2 = saveCustomer("Bench " + workers + "w Dst");
        Account src = saveAccount(c1, initialSrcBalance, AccountStatus.ACTIVE);
        Account dst = saveAccount(c2, BigDecimal.ZERO.setScale(2), AccountStatus.ACTIVE);

        TransactionExecutorProperties props = new TransactionExecutorProperties();
        props.setCorePoolSize(workers);
        props.setMaxPoolSize(workers);
        props.setQueueCapacity(500);
        props.setThreadNamePrefix("bench-" + workers + "w-");
        TransactionThreadPoolExecutor customPool = new TransactionThreadPoolExecutor(props);
        customPool.start();

        try {
            BatchTransactionService customBatchService = new BatchTransactionService(transactionService, customPool);
            List<BatchTransactionItem> items = buildTransfers(src.getId(), dst.getId(), txnCount, transferAmount);
            BatchTransactionResult result = customBatchService.processBatch(
                    new BatchTransactionRequest("BENCH-" + workers + "W-" + txnCount, items)
            );

            printReport("Progressive Benchmark — " + workers + " Workers (" + txnCount + " txns)", result);

            assertEquals(txnCount, result.totalSubmitted(), "Total submitted count mismatch");
            assertEquals(txnCount, result.successful(), "All submitted transactions must succeed");
            assertEquals(0, result.failed(), "Failed transactions must be 0");
            assertEquals(0, result.rejected(), "Rejected transactions must be 0");

            BigDecimal expectedSrc = initialSrcBalance.subtract(transferAmount.multiply(BigDecimal.valueOf(txnCount)));
            BigDecimal actualSrc = accountRepository.findById(src.getId()).orElseThrow().getBalance();
            assertBigDecimalEquals(expectedSrc, actualSrc, "Source balance mismatch");

            BigDecimal expectedDst = transferAmount.multiply(BigDecimal.valueOf(txnCount));
            BigDecimal actualDst = accountRepository.findById(dst.getId()).orElseThrow().getBalance();
            assertBigDecimalEquals(expectedDst, actualDst, "Destination balance mismatch");

            // Verify financial conservation (no duplicate financial impact)
            assertBigDecimalEquals(initialSrcBalance, actualSrc.add(actualDst),
                    "Total funds in system must be strictly conserved");

            // Verify database records
            assertEquals(txnCount, bankTransactionRepository.count(), "Bank transactions count mismatch");
            assertEquals(txnCount, transactionAuditRepository.count(), "Transaction audits count mismatch");
            assertEquals(0, failedTransactionRepository.count(), "Failed transactions count must be 0");
        } finally {
            customPool.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String nextTxnId(String type) {
        return "BNC-" + type + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private BatchTransactionResult run(String batchId, List<BatchTransactionItem> items) {
        return batchTransactionService.processBatch(new BatchTransactionRequest(batchId, items));
    }

    private List<BatchTransactionItem> buildDeposits(Long accountId, int count) {
        List<BatchTransactionItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new BatchTransactionItem(
                    nextTxnId("DEP"),
                    DEPOSIT, null, accountId,
                    new BigDecimal("1.00"), "Benchmark deposit"
            ));
        }
        return items;
    }

    private List<BatchTransactionItem> buildTransfers(Long srcId, Long dstId, int count, BigDecimal amount) {
        List<BatchTransactionItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new BatchTransactionItem(
                    nextTxnId("TRF"),
                    TRANSFER, srcId, dstId,
                    amount, "Benchmark transfer"
            ));
        }
        return items;
    }

    private List<BatchTransactionItem> buildMixed(Long srcId, Long dstId, int count) {
        List<BatchTransactionItem> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int mod = i % 3;
            items.add(switch (mod) {
                case 0 -> new BatchTransactionItem(nextTxnId("DEP"), DEPOSIT, null, dstId,
                        new BigDecimal("5.00"), "Benchmark deposit");
                case 1 -> new BatchTransactionItem(nextTxnId("WDR"), WITHDRAWAL, srcId, null,
                        new BigDecimal("3.00"), "Benchmark withdrawal");
                default -> new BatchTransactionItem(nextTxnId("TRF"), TRANSFER, srcId, dstId,
                        new BigDecimal("2.00"), "Benchmark transfer");
            });
        }
        return items;
    }

    private void assertCorrectness(BatchTransactionResult result, int expectedTotal) {
        int total = result.successful() + result.failed() + result.rejected();
        assertTrue(total == result.totalSubmitted(),
                "successful + failed + rejected must equal totalSubmitted, got "
                        + result.successful() + "+" + result.failed() + "+" + result.rejected()
                        + " vs expected " + expectedTotal);
        assertTrue(result.durationMs() >= 0, "durationMs must be non-negative");
    }

    private void printReport(String scenarioName, BatchTransactionResult r) {
        double tpsDouble = r.tps() != null ? r.tps().doubleValue() : 0.0;
        String poolInfo = getHikariPoolSummary();
        log.info("""
                
                ╔══════════════════════════════════════════════════════════════╗
                  BENCHMARK: {}
                ╠══════════════════════════════════════════════════════════════╣
                  Total submitted : {}
                  Accepted        : {}
                  Rejected        : {}
                  Successful      : {}
                  Failed          : {}
                  Duration (ms)   : {}
                  TPS             : {}
                  Worker count    : {}
                  Queue capacity  : {}
                  HikariCP Pool   : {}
                ╚══════════════════════════════════════════════════════════════╝
                """,
                scenarioName,
                r.totalSubmitted(), r.accepted(), r.rejected(),
                r.successful(), r.failed(),
                r.durationMs(), String.format("%.2f", tpsDouble),
                r.workerCount(), r.queueCapacity(),
                poolInfo
        );
        if (r.failed() > 0 && r.results() != null) {
            r.results().stream()
                    .filter(item -> item.status() != com.newgen.banking.entity.TransactionStatus.SUCCESS)
                    .limit(5)
                    .forEach(item -> log.warn("Sample failure [{}]: {}", item.transactionId(), item.failureReason()));
        }
    }

    private String getHikariPoolSummary() {
        if (dataSource instanceof HikariDataSource hikari) {
            HikariPoolMXBean mxBean = hikari.getHikariPoolMXBean();
            if (mxBean != null) {
                return String.format("total=%d active=%d idle=%d awaiting=%d (max=%d)",
                        mxBean.getTotalConnections(),
                        mxBean.getActiveConnections(),
                        mxBean.getIdleConnections(),
                        mxBean.getThreadsAwaitingConnection(),
                        hikari.getMaximumPoolSize());
            }
        }
        return "N/A";
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private Customer saveCustomer(String name) {
        Customer c = new Customer();
        c.setFullName(name + " " + UUID.randomUUID().toString().substring(0, 6));
        c.setEmail("bench." + UUID.randomUUID().toString().substring(0, 8) + "@bench.com");
        c.setPhone("9000000000");
        return customerRepository.saveAndFlush(c);
    }

    private Account saveAccount(Customer customer, BigDecimal balance, AccountStatus status) {
        Long seq = accountRepository.getNextAccountNumberSequence();
        Account account = new Account();
        account.setAccountNumber("NG" + seq);
        account.setCustomer(customer);
        account.setAccountType(AccountType.SAVINGS);
        account.setBalance(balance);
        account.setStatus(status);
        return accountRepository.saveAndFlush(account);
    }

    // Keep the assertBigDecimalEquals helper local
    private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual, String msg) {
        assertTrue(expected.compareTo(actual) == 0, msg + " — expected " + expected + " got " + actual);
    }
}
