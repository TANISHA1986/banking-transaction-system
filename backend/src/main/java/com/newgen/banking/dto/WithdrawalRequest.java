package com.newgen.banking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for withdrawing funds from an account.
 */
public record WithdrawalRequest(

        @Size(max = 64, message = "Transaction ID cannot exceed 64 characters")
        String transactionId,

        @NotNull(message = "Account ID is required")
        @Positive(message = "Account ID must be a positive number")
        Long accountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", inclusive = true, message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Amount must have at most 17 integer digits and 2 decimal places")
        BigDecimal amount,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {
}
