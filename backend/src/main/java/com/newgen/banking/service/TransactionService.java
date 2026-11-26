package com.newgen.banking.service;

import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.FailedTransaction;
import com.newgen.banking.entity.TransactionAudit;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.DuplicateTransactionException;
import com.newgen.banking.exception.InactiveAccountException;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.exception.TransactionNotFoundException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import com.newgen.banking.repository.FailedTransactionRepository;
import com.newgen.banking.repository.TransactionAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

/**
 * Service layer for deposits, withdrawals, transfers, transaction lookup, and audit/failure logging.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "amount", "status", "transactionType", "createdAt");

    private final AccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionAuditRepository transactionAuditRepository;
    private final FailedTransactionRepository failedTransactionRepository;

    public TransactionService(
            AccountRepository accountRepository,
            BankTransactionRepository bankTransactionRepository,
            TransactionAuditRepository transactionAuditRepository,
            FailedTransactionRepository failedTransactionRepository) {
        this.accountRepository = accountRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.transactionAuditRepository = transactionAuditRepository;
        this.failedTransactionRepository = failedTransactionRepository;
    }

    @Transactional(noRollbackFor = {
            AccountNotFoundException.class,
            InactiveAccountException.class
    })
    public TransactionResponse deposit(DepositRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Deposit request must not be null");
        }
        long startNanos = System.nanoTime();
        validatePositiveAccountId(request.accountId(), "Account ID");
        BigDecimal amount = validateAndNormalizeAmount(request.amount());
        String transactionId = resolveUniqueTransactionId(request.transactionId());
        String description = normalizeDescription(request.description());

        Account destination = findAccountOrRecordFailure(
                request.accountId(),
                transactionId,
                TransactionType.DEPOSIT,
                amount,
                null,
                null,
                description,
                startNanos
        );

        ensureAccountActiveOrRecordFailure(
                destination,
                transactionId,
                TransactionType.DEPOSIT,
                amount,
                null,
                destination,
                description,
                startNanos
        );

        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.save(destination);

        BankTransaction savedTx = recordSuccess(
                transactionId,
                TransactionType.DEPOSIT,
                amount,
                null,
                destination,
                description,
                startNanos,
                "Deposited " + amount + " into account " + destination.getAccountNumber()
        );
        return TransactionResponse.from(savedTx);
    }

    @Transactional(noRollbackFor = {
            AccountNotFoundException.class,
            InactiveAccountException.class,
            InsufficientBalanceException.class
    })
    public TransactionResponse withdraw(WithdrawalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Withdrawal request must not be null");
        }
        long startNanos = System.nanoTime();
        validatePositiveAccountId(request.accountId(), "Account ID");
        BigDecimal amount = validateAndNormalizeAmount(request.amount());
        String transactionId = resolveUniqueTransactionId(request.transactionId());
        String description = normalizeDescription(request.description());

        Account source = findAccountOrRecordFailure(
                request.accountId(),
                transactionId,
                TransactionType.WITHDRAWAL,
                amount,
                null,
                null,
                description,
                startNanos
        );

        ensureAccountActiveOrRecordFailure(
                source,
                transactionId,
                TransactionType.WITHDRAWAL,
                amount,
                source,
                null,
                description,
                startNanos
        );

        ensureSufficientBalanceOrRecordFailure(
                source,
                amount,
                transactionId,
                TransactionType.WITHDRAWAL,
                null,
                description,
                startNanos
        );

        source.setBalance(source.getBalance().subtract(amount));
        accountRepository.save(source);

        BankTransaction savedTx = recordSuccess(
                transactionId,
                TransactionType.WITHDRAWAL,
                amount,
                source,
                null,
                description,
                startNanos,
                "Withdrew " + amount + " from account " + source.getAccountNumber()
        );
        return TransactionResponse.from(savedTx);
    }

    @Transactional(noRollbackFor = {
            AccountNotFoundException.class,
            InactiveAccountException.class,
            InsufficientBalanceException.class
    })
    public TransactionResponse transfer(TransferRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Transfer request must not be null");
        }
        long startNanos = System.nanoTime();
        validatePositiveAccountId(request.sourceAccountId(), "Source account ID");
        validatePositiveAccountId(request.destinationAccountId(), "Destination account ID");
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new IllegalArgumentException("Source and destination accounts must be different");
        }

        BigDecimal amount = validateAndNormalizeAmount(request.amount());
        String transactionId = resolveUniqueTransactionId(request.transactionId());
        String description = normalizeDescription(request.description());

        Account source = findAccountOrRecordFailure(
                request.sourceAccountId(),
                transactionId,
                TransactionType.TRANSFER,
                amount,
                null,
                null,
                description,
                startNanos
        );

        Account destination = findAccountOrRecordFailure(
                request.destinationAccountId(),
                transactionId,
                TransactionType.TRANSFER,
                amount,
                source,
                null,
                description,
                startNanos
        );

        ensureAccountActiveOrRecordFailure(
                source,
                transactionId,
                TransactionType.TRANSFER,
                amount,
                source,
                destination,
                description,
                startNanos
        );

        ensureAccountActiveOrRecordFailure(
                destination,
                transactionId,
                TransactionType.TRANSFER,
                amount,
                source,
                destination,
                description,
                startNanos
        );

        ensureSufficientBalanceOrRecordFailure(
                source,
                amount,
                transactionId,
                TransactionType.TRANSFER,
                destination,
                description,
                startNanos
        );

        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.save(source);
        accountRepository.save(destination);

        BankTransaction savedTx = recordSuccess(
                transactionId,
                TransactionType.TRANSFER,
                amount,
                source,
                destination,
                description,
                startNanos,
                "Transferred " + amount + " from " + source.getAccountNumber() + " to " + destination.getAccountNumber()
        );
        return TransactionResponse.from(savedTx);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransactionByTransactionId(String transactionId) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("Transaction ID is required");
        }
        String normalizedId = transactionId.trim();
        BankTransaction transaction = bankTransactionRepository.findByTransactionId(normalizedId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction not found with ID: " + normalizedId
                ));
        return TransactionResponse.from(transaction);
    }

    @Transactional(readOnly = true)
    public PagedResponse<TransactionResponse> getTransactionsByAccountId(
            Long accountId,
            int page,
            int size,
            String sortBy,
            String sortDir) {
        validatePositiveAccountId(accountId, "Account ID");
        if (page < 0) {
            throw new IllegalArgumentException("Page index must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw new IllegalArgumentException("Invalid sort field: " + sortBy);
        }
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException("Account not found with ID: " + accountId);
        }

        Sort.Direction direction = "asc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy).and(Sort.by(direction, "id")));
        Page<TransactionResponse> responsePage = bankTransactionRepository.findByAccountId(accountId, pageable)
                .map(TransactionResponse::from);
        return PagedResponse.from(responsePage);
    }

    private Account findAccountOrRecordFailure(
            Long accountId,
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account recordedSource,
            Account recordedDestination,
            String description,
            long startNanos) {
        return accountRepository.findWithCustomerById(accountId)
                .orElseThrow(() -> {
                    String reason = "Account not found with ID: " + accountId;
                    recordFailure(
                            transactionId,
                            type,
                            amount,
                            recordedSource,
                            recordedDestination,
                            TransactionStatus.REJECTED,
                            description,
                            "ACCOUNT_NOT_FOUND",
                            reason,
                            startNanos
                    );
                    return new AccountNotFoundException(reason);
                });
    }

    private void ensureAccountActiveOrRecordFailure(
            Account account,
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account recordedSource,
            Account recordedDestination,
            String description,
            long startNanos) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            String reason = "Account " + account.getAccountNumber() + " is not ACTIVE (current status: " + account.getStatus() + ")";
            recordFailure(
                    transactionId,
                    type,
                    amount,
                    recordedSource,
                    recordedDestination,
                    TransactionStatus.REJECTED,
                    description,
                    "INACTIVE_ACCOUNT",
                    reason,
                    startNanos
            );
            throw new InactiveAccountException(reason);
        }
    }

    private void ensureSufficientBalanceOrRecordFailure(
            Account source,
            BigDecimal amount,
            String transactionId,
            TransactionType type,
            Account destination,
            String description,
            long startNanos) {
        if (source.getBalance().compareTo(amount) < 0) {
            String reason = "Insufficient balance in account " + source.getAccountNumber()
                    + " (available: " + source.getBalance() + ", requested: " + amount + ")";
            recordFailure(
                    transactionId,
                    type,
                    amount,
                    source,
                    destination,
                    TransactionStatus.FAILED,
                    description,
                    "INSUFFICIENT_BALANCE",
                    reason,
                    startNanos
            );
            throw new InsufficientBalanceException(reason);
        }
    }

    private BankTransaction recordSuccess(
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            Account source,
            Account destination,
            String description,
            long startNanos,
            String details) {
        BankTransaction tx = new BankTransaction();
        tx.setTransactionId(transactionId);
        tx.setTransactionType(type);
        tx.setAmount(amount);
        tx.setSourceAccount(source);
        tx.setDestinationAccount(destination);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setDescription(description);

        BankTransaction savedTx = bankTransactionRepository.save(tx);
        saveAudit(savedTx, TransactionStatus.SUCCESS, startNanos, details);
        log.info("Transaction {} ({}) completed with status SUCCESS", transactionId, type);
        return savedTx;
    }

    private void recordFailure(
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
        BankTransaction tx = new BankTransaction();
        tx.setTransactionId(transactionId);
        tx.setTransactionType(type);
        tx.setAmount(amount);
        tx.setSourceAccount(source);
        tx.setDestinationAccount(destination);
        tx.setStatus(status);
        tx.setDescription(description);
        tx.setFailureReason(reason);

        BankTransaction savedTx = bankTransactionRepository.save(tx);
        saveAudit(savedTx, status, startNanos, reason);

        FailedTransaction failed = new FailedTransaction();
        failed.setBankTransaction(savedTx);
        failed.setTransactionId(transactionId);
        failed.setErrorCategory(errorCategory);
        failed.setFailureReason(reason);
        failedTransactionRepository.save(failed);

        log.warn("Transaction {} ({}) ended with status {}: {}", transactionId, type, status, reason);
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

    private String resolveUniqueTransactionId(String rawTransactionId) {
        String transactionId;
        if (rawTransactionId == null) {
            transactionId = "TXN-" + UUID.randomUUID();
        } else {
            transactionId = rawTransactionId.trim();
            if (transactionId.isEmpty()) {
                throw new IllegalArgumentException("Transaction ID must not be blank");
            }
            if (transactionId.length() > 64) {
                throw new IllegalArgumentException("Transaction ID cannot exceed 64 characters");
            }
        }
        if (bankTransactionRepository.existsByTransactionId(transactionId)) {
            throw new DuplicateTransactionException(
                    "Duplicate transaction request rejected for transactionId: " + transactionId
            );
        }
        return transactionId;
    }

    private BigDecimal validateAndNormalizeAmount(BigDecimal rawAmount) {
        if (rawAmount == null || rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transaction amount must be greater than zero");
        }
        if (rawAmount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Transaction amount cannot have more than 2 decimal places");
        }
        return rawAmount.setScale(2, RoundingMode.HALF_UP);
    }

    private void validatePositiveAccountId(Long accountId, String label) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException(label + " must be a positive number");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
