package com.newgen.banking.service;

import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.DuplicateTransactionException;
import com.newgen.banking.exception.InactiveAccountException;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.exception.TransactionNotFoundException;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service layer for deposits, withdrawals, transfers, and transaction lookup.
 *
 * <h2>Concurrency design — pessimistic locking</h2>
 * <p>Every balance-mutating operation acquires a {@code SELECT … FOR UPDATE} lock
 * on the account row(s) before reading the balance. The database serialises all
 * concurrent operations on the same row; no two threads can hold the lock
 * simultaneously, eliminating lost updates and phantom reads.</p>
 *
 * <h3>Deadlock prevention in transfers</h3>
 * <p>Both accounts are locked in a single query ordered by {@code id ASC}.
 * Because every transfer thread acquires locks in the same ID order, the
 * circular-wait condition that causes deadlocks cannot occur.</p>
 *
 * <h3>Failure recording with REQUIRES_NEW</h3>
 * <p>Business failures (inactive account, insufficient balance, account not found)
 * cause the outer transaction to roll back, which would release the lock AND discard
 * any writes done in the same transaction. {@link TransactionRecordingService} uses
 * {@code Propagation.REQUIRES_NEW} so failure/audit records are committed
 * independently before the outer transaction rolls back.</p>
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "amount", "status", "transactionType", "createdAt");

    private final AccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final TransactionRecordingService recorder;

    public TransactionService(
            AccountRepository accountRepository,
            BankTransactionRepository bankTransactionRepository,
            TransactionRecordingService recorder) {
        this.accountRepository = accountRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.recorder = recorder;
    }

    // ── Deposit ──────────────────────────────────────────────────────────────

    /**
     * Deposits {@code amount} into the specified account.
     * Acquires a pessimistic write lock on the account row before mutating the balance.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionResponse deposit(DepositRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Deposit request must not be null");
        }
        long startNanos = System.nanoTime();
        validatePositiveAccountId(request.accountId(), "Account ID");
        BigDecimal amount = validateAndNormalizeAmount(request.amount());
        String transactionId = resolveUniqueTransactionId(request.transactionId());
        String description = normalizeDescription(request.description());

        // ── 1. Lock the destination account row (SELECT … FOR UPDATE) ──────
        Account destination = accountRepository.findByIdForUpdate(request.accountId())
                .orElseGet(() -> {
                    recorder.recordFailure(transactionId, TransactionType.DEPOSIT, amount,
                            null, null, TransactionStatus.REJECTED, description,
                            "ACCOUNT_NOT_FOUND", "Account not found with ID: " + request.accountId(),
                            startNanos);
                    throw new AccountNotFoundException("Account not found with ID: " + request.accountId());
                });

        // ── 2. Validate active status ──────────────────────────────────────
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            String reason = buildInactiveReason(destination);
            recorder.recordFailure(transactionId, TransactionType.DEPOSIT, amount,
                    null, destination, TransactionStatus.REJECTED, description,
                    "INACTIVE_ACCOUNT", reason, startNanos);
            throw new InactiveAccountException(reason);
        }

        // ── 3. Mutate balance and save ─────────────────────────────────────
        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.save(destination);

        // ── 4. Record success (REQUIRES_NEW — commits now, inside outer tx) ─
        BankTransaction savedTx = recorder.recordSuccess(transactionId, TransactionType.DEPOSIT,
                amount, null, destination, description, startNanos,
                "Deposited " + amount + " into account " + destination.getAccountNumber());
        return TransactionResponse.from(savedTx);
    }

    // ── Withdrawal ───────────────────────────────────────────────────────────

    /**
     * Withdraws {@code amount} from the specified account.
     * Acquires a pessimistic write lock on the account row before reading the balance.
     */
    @Transactional(rollbackFor = Exception.class)
    public TransactionResponse withdraw(WithdrawalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Withdrawal request must not be null");
        }
        long startNanos = System.nanoTime();
        validatePositiveAccountId(request.accountId(), "Account ID");
        BigDecimal amount = validateAndNormalizeAmount(request.amount());
        String transactionId = resolveUniqueTransactionId(request.transactionId());
        String description = normalizeDescription(request.description());

        // ── 1. Lock the source account row (SELECT … FOR UPDATE) ───────────
        Account source = accountRepository.findByIdForUpdate(request.accountId())
                .orElseGet(() -> {
                    recorder.recordFailure(transactionId, TransactionType.WITHDRAWAL, amount,
                            null, null, TransactionStatus.REJECTED, description,
                            "ACCOUNT_NOT_FOUND", "Account not found with ID: " + request.accountId(),
                            startNanos);
                    throw new AccountNotFoundException("Account not found with ID: " + request.accountId());
                });

        // ── 2. Validate active status ──────────────────────────────────────
        if (source.getStatus() != AccountStatus.ACTIVE) {
            String reason = buildInactiveReason(source);
            recorder.recordFailure(transactionId, TransactionType.WITHDRAWAL, amount,
                    source, null, TransactionStatus.REJECTED, description,
                    "INACTIVE_ACCOUNT", reason, startNanos);
            throw new InactiveAccountException(reason);
        }

        // ── 3. Validate balance (read is now inside the lock — no phantom) ─
        if (source.getBalance().compareTo(amount) < 0) {
            String reason = buildInsufficientReason(source, amount);
            recorder.recordFailure(transactionId, TransactionType.WITHDRAWAL, amount,
                    source, null, TransactionStatus.FAILED, description,
                    "INSUFFICIENT_BALANCE", reason, startNanos);
            throw new InsufficientBalanceException(reason);
        }

        // ── 4. Mutate balance and save ─────────────────────────────────────
        source.setBalance(source.getBalance().subtract(amount));
        accountRepository.save(source);

        // ── 5. Record success ──────────────────────────────────────────────
        BankTransaction savedTx = recorder.recordSuccess(transactionId, TransactionType.WITHDRAWAL,
                amount, source, null, description, startNanos,
                "Withdrew " + amount + " from account " + source.getAccountNumber());
        return TransactionResponse.from(savedTx);
    }

    // ── Transfer ─────────────────────────────────────────────────────────────

    /**
     * Transfers {@code amount} from source to destination account.
     *
     * <p><b>Deadlock prevention:</b> both accounts are locked in a single
     * {@code SELECT … FOR UPDATE} query ordered by {@code id ASC}. This guarantees
     * that concurrent opposite-direction transfers always acquire locks in the same
     * order, making circular waits impossible.</p>
     */
    @Transactional(rollbackFor = Exception.class)
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

        // ── 1. Lock BOTH account rows in ascending ID order (deadlock-safe) ─
        List<Long> orderedIds = new ArrayList<>(List.of(
                Math.min(request.sourceAccountId(), request.destinationAccountId()),
                Math.max(request.sourceAccountId(), request.destinationAccountId())
        ));
        List<Account> lockedAccounts = accountRepository.findByIdsForUpdate(orderedIds);

        Account source = lockedAccounts.stream()
                .filter(a -> a.getId().equals(request.sourceAccountId()))
                .findFirst()
                .orElseGet(() -> {
                    recorder.recordFailure(transactionId, TransactionType.TRANSFER, amount,
                            null, null, TransactionStatus.REJECTED, description,
                            "ACCOUNT_NOT_FOUND", "Source account not found with ID: " + request.sourceAccountId(),
                            startNanos);
                    throw new AccountNotFoundException("Source account not found with ID: " + request.sourceAccountId());
                });

        Account destination = lockedAccounts.stream()
                .filter(a -> a.getId().equals(request.destinationAccountId()))
                .findFirst()
                .orElseGet(() -> {
                    recorder.recordFailure(transactionId, TransactionType.TRANSFER, amount,
                            source, null, TransactionStatus.REJECTED, description,
                            "ACCOUNT_NOT_FOUND", "Destination account not found with ID: " + request.destinationAccountId(),
                            startNanos);
                    throw new AccountNotFoundException("Destination account not found with ID: " + request.destinationAccountId());
                });

        // ── 2. Validate active status of both accounts ─────────────────────
        if (source.getStatus() != AccountStatus.ACTIVE) {
            String reason = buildInactiveReason(source);
            recorder.recordFailure(transactionId, TransactionType.TRANSFER, amount,
                    source, destination, TransactionStatus.REJECTED, description,
                    "INACTIVE_ACCOUNT", reason, startNanos);
            throw new InactiveAccountException(reason);
        }
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            String reason = buildInactiveReason(destination);
            recorder.recordFailure(transactionId, TransactionType.TRANSFER, amount,
                    source, destination, TransactionStatus.REJECTED, description,
                    "INACTIVE_ACCOUNT", reason, startNanos);
            throw new InactiveAccountException(reason);
        }

        // ── 3. Validate balance (locked read — always consistent) ──────────
        if (source.getBalance().compareTo(amount) < 0) {
            String reason = buildInsufficientReason(source, amount);
            recorder.recordFailure(transactionId, TransactionType.TRANSFER, amount,
                    source, destination, TransactionStatus.FAILED, description,
                    "INSUFFICIENT_BALANCE", reason, startNanos);
            throw new InsufficientBalanceException(reason);
        }

        // ── 4. Mutate balances and save both rows ──────────────────────────
        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destination.getBalance().add(amount));
        accountRepository.save(source);
        accountRepository.save(destination);

        // ── 5. Record success ──────────────────────────────────────────────
        BankTransaction savedTx = recorder.recordSuccess(transactionId, TransactionType.TRANSFER,
                amount, source, destination, description, startNanos,
                "Transferred " + amount + " from " + source.getAccountNumber()
                        + " to " + destination.getAccountNumber());
        return TransactionResponse.from(savedTx);
    }

    // ── Query operations ─────────────────────────────────────────────────────

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
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(direction, sortBy).and(Sort.by(direction, "id")));
        Page<TransactionResponse> responsePage =
                bankTransactionRepository.findByAccountId(accountId, pageable)
                        .map(TransactionResponse::from);
        return PagedResponse.from(responsePage);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns a unique transaction ID, generating one automatically when the caller
     * did not provide one.
     *
     * <p>Application-level duplicate check: if another thread races through this
     * check with the same ID, the database's UNIQUE constraint on
     * {@code transaction_id} will fire, which we catch and rethrow as
     * {@link DuplicateTransactionException}.</p>
     */
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
        // Fast-path check (not atomic — the DB unique constraint is the real guard)
        if (bankTransactionRepository.existsByTransactionId(transactionId)) {
            throw new DuplicateTransactionException(
                    "Duplicate transaction request rejected for transactionId: " + transactionId);
        }
        return transactionId;
    }

    /**
     * Wraps a {@link DataIntegrityViolationException} raised by the DB unique
     * constraint on {@code transaction_id} into a {@link DuplicateTransactionException}.
     * Call this around any {@code bankTransactionRepository.save()} that could race.
     */
    static BankTransaction saveOrThrowDuplicate(
            BankTransactionRepository repo, BankTransaction tx) {
        try {
            return repo.save(tx);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException(
                    "Duplicate transaction request rejected for transactionId: " + tx.getTransactionId(), ex);
        }
    }

    private BigDecimal validateAndNormalizeAmount(BigDecimal rawAmount) {
        if (rawAmount == null || rawAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Transaction amount must be greater than zero");
        }
        if (rawAmount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Transaction amount cannot have more than 2 decimal places");
        }
        return rawAmount.setScale(2, RoundingMode.HALF_UP);
    }

    private void validatePositiveAccountId(Long accountId, String label) {
        if (accountId == null || accountId <= 0) {
            throw new IllegalArgumentException(label + " must be a positive number");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null) return null;
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String buildInactiveReason(Account account) {
        return "Account " + account.getAccountNumber()
                + " is not ACTIVE (current status: " + account.getStatus() + ")";
    }

    private String buildInsufficientReason(Account source, BigDecimal amount) {
        return "Insufficient balance in account " + source.getAccountNumber()
                + " (available: " + source.getBalance() + ", requested: " + amount + ")";
    }
}
