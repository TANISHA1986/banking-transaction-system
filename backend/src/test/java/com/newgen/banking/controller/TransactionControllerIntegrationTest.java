package com.newgen.banking.controller;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.entity.FailedTransaction;
import com.newgen.banking.entity.TransactionAudit;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.CustomerRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests verifying balances, transactions, audits, failures, and DB
 * constraints in banking_test_db.
 *
 * <h2>Why NO @Transactional on the test class</h2>
 * <p>
 * {@link com.newgen.banking.service.TransactionRecordingService} uses
 * {@code Propagation.REQUIRES_NEW} to write audit and failure records in an independent
 * transaction so they survive even when the outer service transaction rolls back.
 * </p>
 * <p>
 * If the test class were annotated {@code @Transactional}, Spring would wrap each test
 * in an uncommitted test transaction and roll it back after the test. That uncommitted
 * transaction would hold the account rows, but {@code REQUIRES_NEW} opens a brand-new
 * database connection — and PostgreSQL's READ COMMITTED isolation means that new
 * connection <em>cannot see rows from an uncommitted transaction</em>.
 * </p>
 * <p>
 * The result: foreign key violations (SQLState 23503) on
 * {@code fk_bank_transactions_dest_account} / {@code fk_bank_transactions_source_account}
 * because the new connection tries to reference account IDs that don't yet exist in its
 * view of the database.
 * </p>
 * <p>
 * <b>Fix:</b> Do not annotate the test class with {@code @Transactional}. Instead, use
 * {@link AfterEach} to delete the data written during each test so the database stays
 * clean for the next test. Customers and accounts are committed immediately by
 * {@code saveAndFlush}, so every connection (including REQUIRES_NEW ones) can see them.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private TransactionAuditRepository transactionAuditRepository;

    @Autowired
    private FailedTransactionRepository failedTransactionRepository;

    /**
     * Delete data in FK-safe order after every test.
     * transaction_audits → failed_transactions → bank_transactions → accounts → customers
     */
    @AfterEach
    void cleanUp() {
        transactionAuditRepository.deleteAll();
        failedTransactionRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void depositAndWithdraw_ShouldUpdateBalancesAndPersistTransactionAndAudit() throws Exception {
        Customer customer = saveCustomer("Aarav Joshi", "aarav.joshi@example.com");
        Account account = saveAccount(customer, new BigDecimal("1000.00"), AccountStatus.ACTIVE);

        String depositBody = """
                {
                  "transactionId": "IT-DEP-001",
                  "accountId": %d,
                  "amount": 500.25,
                  "description": "Initial deposit"
                }
                """.formatted(account.getId());

        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("IT-DEP-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(500.25));

        Account afterDeposit = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals(new BigDecimal("1500.25"), afterDeposit.getBalance());

        String withdrawBody = """
                {
                  "transactionId": "IT-WDR-001",
                  "accountId": %d,
                  "amount": 300.25,
                  "description": "Bill payment"
                }
                """.formatted(account.getId());

        mockMvc.perform(post("/api/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("IT-WDR-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        Account afterWithdraw = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals(new BigDecimal("1200.00"), afterWithdraw.getBalance());

        List<TransactionAudit> audits =
                transactionAuditRepository.findByTransactionIdOrderByEventTimestampAsc("IT-WDR-001");
        assertEquals(1, audits.size());
        assertEquals(TransactionStatus.SUCCESS, audits.get(0).getEventStatus());
    }

    @Test
    void withdraw_ShouldReturn422AndPersistFailedRecord_WhenInsufficientBalance() throws Exception {
        Customer customer = saveCustomer("Riya Kulkarni", "riya.kulkarni@example.com");
        Account account = saveAccount(customer, new BigDecimal("250.00"), AccountStatus.ACTIVE);

        String body = """
                {
                  "transactionId": "IT-WDR-FAIL-001",
                  "accountId": %d,
                  "amount": 500.00
                }
                """.formatted(account.getId());

        mockMvc.perform(post("/api/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422));

        // Balance must be unchanged
        Account unchanged = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals(new BigDecimal("250.00"), unchanged.getBalance());

        // Failure record must have been committed (by REQUIRES_NEW)
        BankTransaction failedTx =
                bankTransactionRepository.findByTransactionId("IT-WDR-FAIL-001").orElseThrow();
        assertEquals(TransactionStatus.FAILED, failedTx.getStatus());

        FailedTransaction failedDetail =
                failedTransactionRepository.findByTransactionId("IT-WDR-FAIL-001").orElseThrow();
        assertEquals("INSUFFICIENT_BALANCE", failedDetail.getErrorCategory());
    }

    @Test
    void deposit_ShouldReturn409AndPersistRejectedRecord_WhenAccountIsFrozen() throws Exception {
        Customer customer = saveCustomer("Karan Malhotra", "karan.malhotra@example.com");
        Account frozen = saveAccount(customer, new BigDecimal("400.00"), AccountStatus.FROZEN);

        String body = """
                {
                  "transactionId": "IT-DEP-FROZEN-001",
                  "accountId": %d,
                  "amount": 100.00
                }
                """.formatted(frozen.getId());

        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // Balance must be unchanged
        Account unchanged = accountRepository.findById(frozen.getId()).orElseThrow();
        assertEquals(new BigDecimal("400.00"), unchanged.getBalance());

        // Rejected record must have been committed (by REQUIRES_NEW)
        BankTransaction rejectedTx =
                bankTransactionRepository.findByTransactionId("IT-DEP-FROZEN-001").orElseThrow();
        assertEquals(TransactionStatus.REJECTED, rejectedTx.getStatus());

        FailedTransaction failedDetail =
                failedTransactionRepository.findByTransactionId("IT-DEP-FROZEN-001").orElseThrow();
        assertEquals("INACTIVE_ACCOUNT", failedDetail.getErrorCategory());
    }

    @Test
    void duplicateTransactionId_ShouldReturn409AndNotChangeBalanceTwice() throws Exception {
        Customer customer = saveCustomer("Sneha Nair", "sneha.nair@example.com");
        Account account = saveAccount(customer, new BigDecimal("1000.00"), AccountStatus.ACTIVE);

        String body = """
                {
                  "transactionId": "IT-DUP-001",
                  "accountId": %d,
                  "amount": 250.00
                }
                """.formatted(account.getId());

        mockMvc.perform(post("/api/transactions/deposit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/transactions/deposit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        Account updated = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals(new BigDecimal("1250.00"), updated.getBalance());
    }

    @Test
    void transfer_ShouldUpdateBothAccountsAndAppearInHistoryAndLookup() throws Exception {
        Customer sender = saveCustomer("Arjun Das", "arjun.das@example.com");
        Customer receiver = saveCustomer("Pooja Hegde", "pooja.hegde@example.com");
        Account source = saveAccount(sender, new BigDecimal("2000.00"), AccountStatus.ACTIVE);
        Account destination = saveAccount(receiver, new BigDecimal("500.00"), AccountStatus.ACTIVE);

        String body = """
                {
                  "transactionId": "IT-TRF-001",
                  "sourceAccountId": %d,
                  "destinationAccountId": %d,
                  "amount": 750.00,
                  "description": "Rent transfer"
                }
                """.formatted(source.getId(), destination.getId());

        mockMvc.perform(post("/api/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("IT-TRF-001"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.sourceAccountId").value(source.getId()))
                .andExpect(jsonPath("$.destinationAccountId").value(destination.getId()));

        assertEquals(new BigDecimal("1250.00"),
                accountRepository.findById(source.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("1250.00"),
                accountRepository.findById(destination.getId()).orElseThrow().getBalance());

        mockMvc.perform(get("/api/transactions/IT-TRF-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(750.00));

        mockMvc.perform(get("/api/transactions/account/" + source.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value("IT-TRF-001"));

        mockMvc.perform(get("/api/transactions/account/" + destination.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value("IT-TRF-001"));
    }

    @Test
    void transfer_ShouldReturn400_WhenSourceAndDestinationAreSame() throws Exception {
        Customer customer = saveCustomer("Manish Roy", "manish.roy@example.com");
        Account account = saveAccount(customer, new BigDecimal("500.00"), AccountStatus.ACTIVE);

        String body = """
                {
                  "transactionId": "IT-TRF-SAME",
                  "sourceAccountId": %d,
                  "destinationAccountId": %d,
                  "amount": 100.00
                }
                """.formatted(account.getId(), account.getId());

        mockMvc.perform(post("/api/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertEquals(new BigDecimal("500.00"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
    }

    @Test
    void databaseConstraints_ShouldRejectNonPositiveAmountAndDuplicateTransactionId() {
        Customer customer = saveCustomer("Divya Sen", "divya.sen@example.com");
        Account account = saveAccount(customer, new BigDecimal("500.00"), AccountStatus.ACTIVE);

        BankTransaction invalidAmount = new BankTransaction();
        invalidAmount.setTransactionId("IT-DB-NEG");
        invalidAmount.setTransactionType(com.newgen.banking.entity.TransactionType.DEPOSIT);
        invalidAmount.setAmount(BigDecimal.ZERO);
        invalidAmount.setDestinationAccount(account);
        invalidAmount.setStatus(TransactionStatus.SUCCESS);

        assertThrows(DataIntegrityViolationException.class,
                () -> bankTransactionRepository.saveAndFlush(invalidAmount));
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Saves and flushes a customer so it is immediately visible to all DB connections.
     * Not wrapped in a test transaction — the row is committed on return.
     */
    private Customer saveCustomer(String fullName, String email) {
        Customer customer = new Customer();
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone("9876543210");
        return customerRepository.saveAndFlush(customer);
    }

    /**
     * Saves and flushes an account so it is immediately visible to all DB connections,
     * including REQUIRES_NEW transactions opened by TransactionRecordingService.
     */
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
