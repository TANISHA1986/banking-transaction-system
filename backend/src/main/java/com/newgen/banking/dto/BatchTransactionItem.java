package com.newgen.banking.dto;

import com.newgen.banking.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A single transaction item inside a {@link BatchTransactionRequest}.
 *
 * <p>The {@code type} field controls which operation is executed:</p>
 * <ul>
 *   <li>{@code DEPOSIT}  — requires {@code destinationAccountId}</li>
 *   <li>{@code WITHDRAWAL} — requires {@code sourceAccountId}</li>
 *   <li>{@code TRANSFER} — requires both {@code sourceAccountId} and
 *       {@code destinationAccountId}</li>
 * </ul>
 */
public record BatchTransactionItem(

        @Size(max = 64, message = "Transaction ID cannot exceed 64 characters")
        String transactionId,

        @NotNull(message = "Transaction type is required")
        TransactionType type,

        Long sourceAccountId,

        Long destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", inclusive = true, message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Amount must have at most 17 integer digits and 2 decimal places")
        BigDecimal amount,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {
}
