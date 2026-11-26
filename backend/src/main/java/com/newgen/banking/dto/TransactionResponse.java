package com.newgen.banking.dto;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.BankTransaction;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response representation of a banking transaction.
 */
public record TransactionResponse(
        Long id,
        String transactionId,
        TransactionType transactionType,
        BigDecimal amount,
        Long sourceAccountId,
        String sourceAccountNumber,
        Long destinationAccountId,
        String destinationAccountNumber,
        TransactionStatus status,
        String description,
        String failureReason,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(BankTransaction transaction) {
        Account source = transaction.getSourceAccount();
        Account destination = transaction.getDestinationAccount();
        return new TransactionResponse(
                transaction.getId(),
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                transaction.getAmount(),
                source != null ? source.getId() : null,
                source != null ? source.getAccountNumber() : null,
                destination != null ? destination.getId() : null,
                destination != null ? destination.getAccountNumber() : null,
                transaction.getStatus(),
                transaction.getDescription(),
                transaction.getFailureReason(),
                transaction.getCreatedAt()
        );
    }
}
