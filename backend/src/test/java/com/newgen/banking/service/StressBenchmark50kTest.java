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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.newgen.banking.entity.TransactionType.DEPOSIT;
import static com.newgen.banking.entity.TransactionType.TRANSFER;
import static com.newgen.banking.entity.TransactionType.WITHDRAWAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeatable 50,000+ transaction stress benchmark against {@code banking_test_db}.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Submits 50,000 transactions in controlled chunks of 500 ({@code CHUNK_SIZE = 500},
 *       {@code NUM_CHUNKS = 100}) to match the bounded executor queue capacity and avoid
 *       heap exhaustion from holding 50,000 {@code Future} / result DTOs simultaneously.</li>
 *   <li>Uses 10 active accounts + 1 frozen account + 1 non-existent account ID to model
 *       realistic concurrent multi-account contention and failure scenarios (90% valid,
 *       10% business failures: insufficient balance, frozen account, account not found).</li>
 *   <li>Verifies exact final balances on every account, total financial conservation,
 *       and database record counts in {@code bank_transactions}, {@code transaction_audits},
 *       and {@code failed_transactions}.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark-50k")
@Tag("benchmark")
class StressBenchmark50kTest {

    private static final Logger log = LoggerFactory.getLogger(StressBenchmark50kTest.class);

    private static final int TOTAL_TRANSACTIONS = 50_000;
    private static final int CHUNK_SIZE = 500;
    private static final int NUM_CHUNKS = TOTAL_TRANSACTIONS / CHUNK_SIZE;
    private static final int ACTIVE_ACCOUNT_COUNT = 10;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

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

    @Test
    void benchmark50k_1Worker() {
        run50kBenchmark(1);
    }

    @Test
    void benchmark50k_5Workers() {
        run50kBenchmark(5);
    }

    @Test
    void benchmark50k_10Workers() {
        run50kBenchmark(10);
    }

    @Test
    void benchmark50k_20Workers() {
        run50kBenchmark(20);
    }

    private void run50kBenchmark(int workers) {
        String runTag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<Account> activeAccounts = new ArrayList<>(ACTIVE_ACCOUNT_COUNT);
        BigDecimal[] expectedBalances = new BigDecimal[ACTIVE_ACCOUNT_COUNT];

        for (int i = 0; i < ACTIVE_ACCOUNT_COUNT; i++) {
            Customer c = saveCustomer("Stress50k-" + workers + "w-" + i);
            activeAccounts.add(saveAccount(c, INITIAL_BALANCE, AccountStatus.ACTIVE));
            expectedBalances[i] = INITIAL_BALANCE;
        }
        Customer frozenCust = saveCustomer("Stress50k-Frozen-" + workers + "w");
        Account frozenAccount = saveAccount(frozenCust, new BigDecimal("500.00"), AccountStatus.FROZEN);

        TransactionExecutorProperties props = new TransactionExecutorProperties();
        props.setCorePoolSize(workers);
        props.setMaxPoolSize(workers);
        props.setQueueCapacity(CHUNK_SIZE);
        props.setThreadNamePrefix("stress50k-" + workers + "w-");

        TransactionThreadPoolExecutor pool = new TransactionThreadPoolExecutor(props);
        pool.start();

        long totalSubmitted = 0;
        long totalAccepted = 0;
        long totalRejected = 0;
        long totalSuccessful = 0;
        long totalFailed = 0;

        long overallStartNanos = System.nanoTime();
        int globalSeq = 0;

        try {
            BatchTransactionService batchService = new BatchTransactionService(transactionService, pool);

            for (int chunk = 0; chunk < NUM_CHUNKS; chunk++) {
                List<BatchTransactionItem> items = new ArrayList<>(CHUNK_SIZE);
                for (int j = 0; j < CHUNK_SIZE; j++) {
                    int idx = globalSeq++;
                    int slot = idx % 100;
                    String txId = "B50K-" + runTag + "-" + idx;

                    if (slot < 40) {
                        // 40% valid DEPOSIT (+10.00)
                        int acctIdx = idx % ACTIVE_ACCOUNT_COUNT;
                        items.add(new BatchTransactionItem(txId, DEPOSIT, null,
                                activeAccounts.get(acctIdx).getId(), new BigDecimal("10.00"), "50k deposit"));
                        expectedBalances[acctIdx] = expectedBalances[acctIdx].add(new BigDecimal("10.00"));
                    } else if (slot < 65) {
                        // 25% valid WITHDRAWAL (-5.00)
                        int acctIdx = idx % ACTIVE_ACCOUNT_COUNT;
                        items.add(new BatchTransactionItem(txId, WITHDRAWAL,
                                activeAccounts.get(acctIdx).getId(), null, new BigDecimal("5.00"), "50k withdrawal"));
                        expectedBalances[acctIdx] = expectedBalances[acctIdx].subtract(new BigDecimal("5.00"));
                    } else if (slot < 90) {
                        // 25% valid TRANSFER (3.00)
                        int srcIdx = idx % ACTIVE_ACCOUNT_COUNT;
                        int dstIdx = (idx + 1) % ACTIVE_ACCOUNT_COUNT;
                        items.add(new BatchTransactionItem(txId, TRANSFER,
                                activeAccounts.get(srcIdx).getId(), activeAccounts.get(dstIdx).getId(),
                                new BigDecimal("3.00"), "50k transfer"));
                        expectedBalances[srcIdx] = expectedBalances[srcIdx].subtract(new BigDecimal("3.00"));
                        expectedBalances[dstIdx] = expectedBalances[dstIdx].add(new BigDecimal("3.00"));
                    } else if (slot < 95) {
                        // 5% insufficient balance WITHDRAWAL -> FAILED
                        int acctIdx = idx % ACTIVE_ACCOUNT_COUNT;
                        items.add(new BatchTransactionItem(txId, WITHDRAWAL,
                                activeAccounts.get(acctIdx).getId(), null, new BigDecimal("999999999.00"), "50k overdraft"));
                    } else if (slot < 98) {
                        // 3% frozen account DEPOSIT -> REJECTED
                        items.add(new BatchTransactionItem(txId, DEPOSIT, null,
                                frozenAccount.getId(), new BigDecimal("10.00"), "50k frozen deposit"));
                    } else {
                        // 2% non-existent source account TRANSFER -> REJECTED
                        items.add(new BatchTransactionItem(txId, TRANSFER,
                                999_999_999L, activeAccounts.get(0).getId(), new BigDecimal("1.00"), "50k missing src"));
                    }
                }

                BatchTransactionResult chunkResult = batchService.processBatch(
                        new BatchTransactionRequest("B50K-" + workers + "W-C" + chunk, items));

                totalSubmitted += chunkResult.totalSubmitted();
                totalAccepted += chunkResult.accepted();
                totalRejected += chunkResult.rejected();
                totalSuccessful += chunkResult.successful();
                totalFailed += chunkResult.failed();
            }
        } finally {
            pool.stop();
        }

        long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - overallStartNanos);
        double tps = totalDurationMs > 0
                ? BigDecimal.valueOf(totalSuccessful * 1000.0 / totalDurationMs)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        log.info("""
                
                ╔══════════════════════════════════════════════════════════════╗
                  50,000-TXN STRESS BENCHMARK ({} Workers)
                ╠══════════════════════════════════════════════════════════════╣
                  Total submitted : {}
                  Accepted        : {}
                  Rejected (pool) : {}
                  Successful      : {}
                  Failed (biz)    : {}
                  Duration (ms)   : {}
                  TPS (success)   : {}
                  HikariCP Pool   : {}
                ╚══════════════════════════════════════════════════════════════╝
                """,
                workers, totalSubmitted, totalAccepted, totalRejected,
                totalSuccessful, totalFailed, totalDurationMs, String.format("%.2f", tps),
                getHikariPoolSummary());

        // Strict assertions: 90% valid (45,000), 10% business failures (5,000), 0 pool rejections
        assertEquals(TOTAL_TRANSACTIONS, totalSubmitted);
        assertEquals(TOTAL_TRANSACTIONS, totalAccepted);
        assertEquals(0L, totalRejected);
        assertEquals(45_000L, totalSuccessful);
        assertEquals(5_000L, totalFailed);

        // Verify every active account's exact balance
        for (int i = 0; i < ACTIVE_ACCOUNT_COUNT; i++) {
            BigDecimal actual = accountRepository.findById(activeAccounts.get(i).getId()).orElseThrow().getBalance();
            assertTrue(expectedBalances[i].compareTo(actual) == 0,
                    "Balance mismatch on active account " + i + ": expected " + expectedBalances[i] + " got " + actual);
        }

        // Frozen account balance must remain untouched
        BigDecimal frozenActual = accountRepository.findById(frozenAccount.getId()).orElseThrow().getBalance();
        assertTrue(new BigDecimal("500.00").compareTo(frozenActual) == 0,
                "Frozen account balance changed: " + frozenActual);

        // Verify database persistence counts
        assertEquals(TOTAL_TRANSACTIONS, bankTransactionRepository.count());
        assertEquals(TOTAL_TRANSACTIONS, transactionAuditRepository.count());
        assertEquals(5_000L, failedTransactionRepository.count());
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

    private Customer saveCustomer(String name) {
        Customer c = new Customer();
        c.setFullName(name);
        c.setEmail("s50k." + UUID.randomUUID().toString().substring(0, 8) + "@bench.com");
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
}
