package com.newgen.banking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request payload for transferring funds between two accounts.
 */
public record TransferRequest(

        @Size(max = 64, message = "Transaction ID cannot exceed 64 characters")
        String transactionId,

        @NotNull(message = "Source account ID is required")
        @Positive(message = "Source account ID must be a positive number")
        Long sourceAccountId,

        @NotNull(message = "Destination account ID is required")
        @Positive(message = "Destination account ID must be a positive number")
        Long destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", inclusive = true, message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Amount must have at most 17 integer digits and 2 decimal places")
        BigDecimal amount,

        @Size(max = 255, message = "Description cannot exceed 255 characters")
        String description
) {
}
