package com.newgen.banking.controller;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.entity.FailedTransaction;
import com.newgen.banking.entity.TransactionAudit;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.CustomerRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests for {@code POST /api/transactions/batch}.
 *
 * <h2>Why NO {@code @Transactional} on the test class</h2>
 * <p>
 * {@link com.newgen.banking.service.TransactionRecordingService} uses
 * {@code Propagation.REQUIRES_NEW}. If the test class were {@code @Transactional},
 * account rows would be invisible to those inner transactions (PostgreSQL READ COMMITTED
 * isolation hides uncommitted data from other connections), causing FK violations.
 * Instead, {@link AccountRepository#saveAndFlush} commits each row immediately, and
 * {@link AfterEach} cleans up in FK-safe order.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BatchTransactionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private BankTransactionRepository bankTransactionRepository;
    @Autowired private TransactionAuditRepository transactionAuditRepository;
    @Autowired private FailedTransactionRepository failedTransactionRepository;

    private static final AtomicInteger TXN_COUNTER = new AtomicInteger(1);

    /** Returns a unique transaction ID for every test run. */
    private static String txnId(String prefix) {
        return prefix + "-" + TXN_COUNTER.getAndIncrement();
    }

    @AfterEach
    void cleanUp() {
        transactionAuditRepository.deleteAll();
        failedTransactionRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ── Happy-path: 3 mixed transactions ─────────────────────────────────────

    @Test
    void batch_ShouldProcessMixedTransactions_AndUpdateBalancesCorrectly() throws Exception {
        Customer c1 = saveCustomer("Aarav Joshi Batch", "aarav.batch@example.com");
        Customer c2 = saveCustomer("Riya Kulkarni Batch", "riya.batch@example.com");
        Account src = saveAccount(c1, new BigDecimal("5000.00"), AccountStatus.ACTIVE);
        Account dst = saveAccount(c2, new BigDecimal("1000.00"), AccountStatus.ACTIVE);

        String depId  = txnId("BIT-DEP");
        String wdrId  = txnId("BIT-WDR");
        String trfId  = txnId("BIT-TRF");

        String body = """
                {
                  "batchId": "BATCH-IT-MIXED",
                  "transactions": [
                    { "transactionId": "%s", "type": "DEPOSIT",    "destinationAccountId": %d, "amount": 500.00 },
                    { "transactionId": "%s", "type": "WITHDRAWAL", "sourceAccountId": %d,      "amount": 300.00 },
                    { "transactionId": "%s", "type": "TRANSFER",   "sourceAccountId": %d, "destinationAccountId": %d, "amount": 200.00 }
                  ]
                }
                """.formatted(depId, dst.getId(), wdrId, src.getId(), trfId, src.getId(), dst.getId());

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(3))
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.successful").value(3))
                .andExpect(jsonPath("$.failed").value(0));

        // src: started 5000, withdrew 300, transferred out 200 → 4500
        // dst: started 1000, deposited 500, received 200          → 1700
        BigDecimal srcBalance = accountRepository.findById(src.getId()).orElseThrow().getBalance();
        BigDecimal dstBalance = accountRepository.findById(dst.getId()).orElseThrow().getBalance();
        assertEquals(new BigDecimal("4500.00"), srcBalance,
                "Source account should be 4500 after withdrawal and transfer");
        assertEquals(new BigDecimal("1700.00"), dstBalance,
                "Destination account should be 1700 after deposit and received transfer");

        // Audit records must be persisted for each successful transaction
        List<TransactionAudit> audits = transactionAuditRepository.findAll();
        assertEquals(3, audits.size(), "One audit record per successful transaction");
    }

    // ── Insufficient balance scenario ─────────────────────────────────────────

    @Test
    void batch_ShouldRecordFailure_WhenWithdrawalExceedsBalance() throws Exception {
        Customer c = saveCustomer("Karan Batch", "karan.batch@example.com");
        Account account = saveAccount(c, new BigDecimal("100.00"), AccountStatus.ACTIVE);

        String okId   = txnId("BIT-DEP-OK");
        String failId = txnId("BIT-WDR-FAIL");

        String body = """
                {
                  "transactions": [
                    { "transactionId": "%s", "type": "DEPOSIT",    "destinationAccountId": %d, "amount": 50.00 },
                    { "transactionId": "%s", "type": "WITHDRAWAL", "sourceAccountId": %d,      "amount": 999.00 }
                  ]
                }
                """.formatted(okId, account.getId(), failId, account.getId());

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.accepted").value(2));

        // Balance must never go negative
        BigDecimal balance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0, "Balance must remain non-negative");

        // The failure record must be committed (REQUIRES_NEW)
        List<FailedTransaction> failures = failedTransactionRepository.findAll();
        assertFalse(failures.isEmpty(), "At least one FailedTransaction must be persisted");
    }

    // ── Mandatory race condition test: two simultaneous withdrawals ───────────

    /**
     * Mandatory race condition test.
     *
     * <p>Account starts at ₹10,000. Two withdrawals (₹7,000 and ₹5,000) are submitted
     * in the same batch so the thread pool processes them concurrently. Because the
     * balance is only enough for one, pessimistic locking (Day 4) must ensure exactly
     * one succeeds and one fails — balance must never go negative.</p>
     *
     * <p>Repeated 5 times to detect intermittent failures caused by race conditions.</p>
     */
    @RepeatedTest(5)
    void batch_ShouldAllowOnlyOneWithdrawal_WhenTwoCompeteForSameBalance() throws Exception {
        Customer c = saveCustomer("Ravi Concurrent", "ravi.concurrent." + TXN_COUNTER.get() + "@example.com");
        Account account = saveAccount(c, new BigDecimal("10000.00"), AccountStatus.ACTIVE);

        String wdr1Id = txnId("BIT-CC-WDR1");
        String wdr2Id = txnId("BIT-CC-WDR2");

        String body = """
                {
                  "transactions": [
                    { "transactionId": "%s", "type": "WITHDRAWAL", "sourceAccountId": %d, "amount": 7000.00 },
                    { "transactionId": "%s", "type": "WITHDRAWAL", "sourceAccountId": %d, "amount": 5000.00 }
                  ]
                }
                """.formatted(wdr1Id, account.getId(), wdr2Id, account.getId());

        MvcResult result = mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.accepted").value(2))
                .andReturn();

        // Extract counts from response
        String responseBody = result.getResponse().getContentAsString();

        // Parse successful + failed from JSON
        int successCount = extractInt(responseBody, "\"successful\":");
        int failCount    = extractInt(responseBody, "\"failed\":");

        assertEquals(1, successCount, "Exactly one withdrawal must succeed");
        assertEquals(1, failCount,    "Exactly one withdrawal must fail (insufficient balance)");

        BigDecimal balance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        assertTrue(balance.compareTo(BigDecimal.ZERO) >= 0, "Balance must never be negative");

        // Winning withdrawal was ₹7,000 or ₹5,000; balance must be 3000 or 5000
        boolean isValidBalance = balance.compareTo(new BigDecimal("3000.00")) == 0
                || balance.compareTo(new BigDecimal("5000.00")) == 0;
        assertTrue(isValidBalance,
                "Balance must be 3000 or 5000, was: " + balance);
    }

    // ── Duplicate transaction ID in batch ─────────────────────────────────────

    @Test
    void batch_ShouldRejectDuplicateTransactionId_SecondItemFails() throws Exception {
        Customer c = saveCustomer("Pooja Duplicate Batch", "pooja.dupbatch@example.com");
        Account account = saveAccount(c, new BigDecimal("2000.00"), AccountStatus.ACTIVE);

        String dupId = txnId("BIT-DUP");

        String body = """
                {
                  "transactions": [
                    { "transactionId": "%s", "type": "DEPOSIT", "destinationAccountId": %d, "amount": 100.00 },
                    { "transactionId": "%s", "type": "DEPOSIT", "destinationAccountId": %d, "amount": 100.00 }
                  ]
                }
                """.formatted(dupId, account.getId(), dupId, account.getId());

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.accepted").value(2));

        // Exactly one deposit must have succeeded — balance = 2100, not 2200
        BigDecimal balance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        // Could be 2100 (1 success) or 2000 (both failed race to duplicate). Never 2200.
        assertTrue(balance.compareTo(new BigDecimal("2200.00")) < 0,
                "Balance must not reflect two identical deposits, was: " + balance);
        assertTrue(balance.compareTo(new BigDecimal("2000.00")) >= 0,
                "Balance must not decrease, was: " + balance);
    }

    // ── Audit records persisted ───────────────────────────────────────────────

    @Test
    void batch_ShouldPersistAuditRecords_ForEachSuccessfulTransaction() throws Exception {
        Customer c = saveCustomer("Sneha Audit Batch", "sneha.auditbatch@example.com");
        Account account = saveAccount(c, new BigDecimal("1000.00"), AccountStatus.ACTIVE);

        String dep1Id = txnId("BIT-AUD-DEP1");
        String dep2Id = txnId("BIT-AUD-DEP2");

        String body = """
                {
                  "transactions": [
                    { "transactionId": "%s", "type": "DEPOSIT", "destinationAccountId": %d, "amount": 100.00 },
                    { "transactionId": "%s", "type": "DEPOSIT", "destinationAccountId": %d, "amount": 150.00 }
                  ]
                }
                """.formatted(dep1Id, account.getId(), dep2Id, account.getId());

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successful").value(2));

        List<TransactionAudit> audits = transactionAuditRepository.findAll();
        assertEquals(2, audits.size(), "Two audit records must be committed by REQUIRES_NEW");
    }

    // ── Validation: 400 for empty batch ──────────────────────────────────────

    @Test
    void batch_ShouldReturn400_WhenTransactionsListIsEmpty() throws Exception {
        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private Customer saveCustomer(String fullName, String email) {
        Customer c = new Customer();
        c.setFullName(fullName);
        c.setEmail(email);
        c.setPhone("9876543210");
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

    /**
     * Minimal JSON value extractor — avoids a Jackson dependency in test assertions.
     * Finds the integer value after the given key in the JSON string.
     */
    private int extractInt(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) throw new IllegalArgumentException("Key not found: " + key);
        int start = idx + key.length();
        // skip whitespace
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Integer.parseInt(json.substring(start, end));
    }
}
