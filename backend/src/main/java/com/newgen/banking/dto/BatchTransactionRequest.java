package com.newgen.banking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload for the {@code POST /api/transactions/batch} endpoint.
 *
 * <p>A batch is a list of up to 1,000 individual transaction items. Each item
 * is validated independently. Invalid items are rejected; valid items are
 * submitted to the thread pool for concurrent execution.</p>
 */
public record BatchTransactionRequest(

        @Size(max = 64, message = "Batch ID cannot exceed 64 characters")
        String batchId,

        @NotEmpty(message = "Batch must contain at least one transaction")
        @Size(max = 1000, message = "Batch cannot exceed 1000 transactions per request")
        @Valid
        List<BatchTransactionItem> transactions
) {
}
