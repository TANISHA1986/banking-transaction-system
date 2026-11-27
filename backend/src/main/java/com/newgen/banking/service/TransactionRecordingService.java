package com.newgen.banking.service;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.FailedTransaction;
import com.newgen.banking.entity.TransactionAudit;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.DuplicateTransactionException;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Handles writing of transaction records (success, failure, audit) in their own
 * independent database transactions.
 *
 * <p>Why a separate service?</p>
 * <p>When pessimistic locking is active, the outer transaction holds a
 * {@code SELECT ... FOR UPDATE} lock. If that transaction rolls back (e.g. due to
 * an InsufficientBalanceException), any writes done inside the SAME transaction
 * are also rolled back — including failure records. By using
 * {@code Propagation.REQUIRES_NEW}, failure and audit records are committed in
 * their own separate transaction regardless of whether the outer one rolls back.
 * This guarantees every attempted transaction is permanently logged.</p>
 */
@Service
public class TransactionRecordingService {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecordingService.class);

    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionAuditRepository transactionAuditRepository;
    private final FailedTransactionRepository failedTransactionRepository;

    public TransactionRecordingService(
            BankTransactionRepository bankTransactionRepository,
            TransactionAuditRepository transactionAuditRepository,
            FailedTransactionRepository failedTransactionRepository) {
        this.bankTransactionRepository = bankTransactionRepository;
        this.transactionAuditRepository = transactionAuditRepository;
        this.failedTransactionRepository = failedTransactionRepository;
    }

    /**
     * Saves a successful transaction record and its audit entry.
     * Runs in the caller's transaction so balance updates and transaction records commit atomically.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public BankTransaction recordSuccess(
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account source,
            Account destination,
            String description,
            long startNanos,
            String details) {

        BankTransaction tx = buildTransaction(transactionId, type, amount, source, destination,
                TransactionStatus.SUCCESS, description, null);
        BankTransaction savedTx = saveBankTransaction(tx);
        saveAudit(savedTx, TransactionStatus.SUCCESS, startNanos, details);
        log.info("Transaction {} ({}) committed with status SUCCESS", transactionId, type);
        return savedTx;
    }

    /**
     * Saves a failed/rejected transaction record, its audit entry, and a FailedTransaction entry.
     * Runs in a NEW transaction so it commits even if the outer transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransaction recordFailure(
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account source,
            Account destination,
            TransactionStatus status,
            String description,
            String errorCategory,
            String reason,
            long startNanos) {

        BankTransaction tx = buildTransaction(transactionId, type, amount, source, destination,
                status, description, reason);
        BankTransaction savedTx = saveBankTransaction(tx);
        saveAudit(savedTx, status, startNanos, reason);

        FailedTransaction failed = new FailedTransaction();
        failed.setBankTransaction(savedTx);
        failed.setTransactionId(transactionId);
        failed.setErrorCategory(errorCategory);
        failed.setFailureReason(reason);
        failedTransactionRepository.save(failed);

        log.warn("Transaction {} ({}) committed with status {}: {}", transactionId, type, status, reason);
        return savedTx;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private BankTransaction saveBankTransaction(BankTransaction tx) {
        try {
            return bankTransactionRepository.save(tx);
        } catch (DataIntegrityViolationException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
            if (ex.getCause() != null && ex.getCause().getMessage() != null) {
                msg += " " + ex.getCause().getMessage().toLowerCase();
            }
            if (msg.contains("transaction_id") || msg.contains("uk_bank_transactions_transaction_id")) {
                throw new DuplicateTransactionException(
                        "Duplicate transaction request rejected for transactionId: " + tx.getTransactionId(), ex);
            }
            throw ex;
        }
    }

    private BankTransaction buildTransaction(
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account source,
            Account destination,
            TransactionStatus status,
            String description,
            String failureReason) {

        BankTransaction tx = new BankTransaction();
        tx.setTransactionId(transactionId);
        tx.setTransactionType(type);
        tx.setAmount(amount);
        tx.setSourceAccount(source);
        tx.setDestinationAccount(destination);
        tx.setStatus(status);
        tx.setDescription(description);
        tx.setFailureReason(failureReason);
        return tx;
    }

    private void saveAudit(
            BankTransaction transaction,
            TransactionStatus status,
            long startNanos,
            String details) {
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        TransactionAudit audit = new TransactionAudit();
        audit.setBankTransaction(transaction);
        audit.setTransactionId(transaction.getTransactionId());
        audit.setThreadName(Thread.currentThread().getName());
        audit.setDurationMs(durationMs);
        audit.setEventStatus(status);
        audit.setEventDetails(details);
        transactionAuditRepository.save(audit);
    }
}
