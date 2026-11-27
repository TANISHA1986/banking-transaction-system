package com.newgen.banking.dto;

import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;

import java.math.BigDecimal;

/**
 * Result for a single transaction within a batch execution.
 *
 * <p>Status meanings:</p>
 * <ul>
 *   <li>{@code SUCCESS}  — transaction completed and balance was updated</li>
 *   <li>{@code FAILED}   — transaction was processed but rejected by business logic
 *                          (e.g. insufficient balance)</li>
 *   <li>{@code REJECTED} — task could not be submitted to the thread pool
 *                          (pool + queue saturated) OR account was inactive</li>
 * </ul>
 */
public record BatchTransactionItemResult(
        String transactionId,
        TransactionType type,
        BigDecimal amount,
        TransactionStatus status,
        String failureReason
) {
    /** Convenience factory for a successful item result. */
    public static BatchTransactionItemResult success(TransactionResponse response) {
        return new BatchTransactionItemResult(
                response.transactionId(),
                response.transactionType(),
                response.amount(),
                TransactionStatus.SUCCESS,
                null
        );
    }

    /** Convenience factory for a failed/rejected item result. */
    public static BatchTransactionItemResult failed(
            String transactionId,
            TransactionType type,
            BigDecimal amount,
            TransactionStatus status,
            String reason) {
        return new BatchTransactionItemResult(transactionId, type, amount, status, reason);
    }
}
