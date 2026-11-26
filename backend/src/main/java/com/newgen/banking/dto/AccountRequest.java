package com.newgen.banking.dto;

import com.newgen.banking.entity.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request payload for opening a new bank account for an existing customer.
 */
public record AccountRequest(

        @NotNull(message = "Customer ID is required")
        @Positive(message = "Customer ID must be a positive number")
        Long customerId,

        @NotNull(message = "Account type is required")
        AccountType accountType,

        @DecimalMin(value = "0.00", inclusive = true, message = "Initial balance cannot be negative")
        @Digits(integer = 17, fraction = 2, message = "Initial balance must have at most 17 integer digits and 2 decimal places")
        BigDecimal initialBalance
) {
}
