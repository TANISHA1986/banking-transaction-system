package com.newgen.banking.controller;

import com.newgen.banking.dto.BatchTransactionRequest;
import com.newgen.banking.dto.BatchTransactionResult;
import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.service.BatchTransactionService;
import com.newgen.banking.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing deposit, withdrawal, transfer, batch, and transaction history endpoints.
 *
 * <h2>Batch endpoint</h2>
 * <p>{@code POST /api/transactions/batch} — submits multiple transactions concurrently
 * via the bounded thread pool. See {@link BatchTransactionService} for processing details.</p>
 *
 * <h3>Sample request</h3>
 * <pre>{@code
 * POST /api/transactions/batch
 * {
 *   "batchId": "BATCH-2026-001",
 *   "transactions": [
 *     { "type": "DEPOSIT",    "destinationAccountId": 1, "amount": 500.00 },
 *     { "type": "WITHDRAWAL", "sourceAccountId": 2,      "amount": 200.00 },
 *     { "type": "TRANSFER",   "sourceAccountId": 1, "destinationAccountId": 2, "amount": 100.00 }
 *   ]
 * }
 * }</pre>
 *
 * <h3>Sample response</h3>
 * <pre>{@code
 * HTTP 200 OK
 * {
 *   "batchId": "BATCH-2026-001",
 *   "totalSubmitted": 3,
 *   "accepted": 3, "rejected": 0,
 *   "successful": 3, "failed": 0,
 *   "durationMs": 142,
 *   "tps": 21.13,
 *   ...
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final BatchTransactionService batchTransactionService;

    public TransactionController(
            TransactionService transactionService,
            BatchTransactionService batchTransactionService) {
        this.transactionService = transactionService;
        this.batchTransactionService = batchTransactionService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(@Valid @RequestBody DepositRequest request) {
        TransactionResponse response = transactionService.deposit(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(@Valid @RequestBody WithdrawalRequest request) {
        TransactionResponse response = transactionService.withdraw(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(@Valid @RequestBody TransferRequest request) {
        TransactionResponse response = transactionService.transfer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransactionById(@PathVariable String transactionId) {
        return ResponseEntity.ok(transactionService.getTransactionByTransactionId(transactionId));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<PagedResponse<TransactionResponse>> getTransactionsByAccountId(
            @PathVariable Long accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        return ResponseEntity.ok(
                transactionService.getTransactionsByAccountId(accountId, page, size, sortBy, sortDir)
        );
    }

    /**
     * Submits multiple transactions for concurrent processing via the bounded thread pool.
     *
     * <p>Returns HTTP 200 with a {@link BatchTransactionResult} summary regardless of
     * individual transaction outcomes. HTTP 400 is returned only if the request itself is
     * structurally invalid (missing fields, oversized batch).</p>
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchTransactionResult> processBatch(
            @Valid @RequestBody BatchTransactionRequest request) {
        BatchTransactionResult result = batchTransactionService.processBatch(request);
        return ResponseEntity.ok(result);
    }
}
