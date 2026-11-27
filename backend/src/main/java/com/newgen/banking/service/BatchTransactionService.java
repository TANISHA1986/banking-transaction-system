package com.newgen.banking.service;

import com.newgen.banking.dto.BatchTransactionItem;
import com.newgen.banking.dto.BatchTransactionItemResult;
import com.newgen.banking.dto.BatchTransactionRequest;
import com.newgen.banking.dto.BatchTransactionResult;
import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.executor.TaskRejectedException;
import com.newgen.banking.executor.TransactionThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Submits a batch of deposit, withdrawal, and transfer requests to the
 * {@link TransactionThreadPoolExecutor} for concurrent processing.
 *
 * <h2>Architecture</h2>
 * <pre>
 * Client → BatchTransactionService.processBatch()
 *              │
 *              ├── for each item: build Callable → submit to ThreadPoolExecutor
 *              │       └── Callable calls TransactionService.deposit/withdraw/transfer()
 *              │               └── TransactionService uses pessimistic DB locking (Day 4)
 *              │                   └── TransactionRecordingService commits records (REQUIRES_NEW)
 *              │
 *              └── collect Future results → build BatchTransactionResult
 * </pre>
 *
 * <h2>Thread safety</h2>
 * <ul>
 *   <li>Each {@link java.util.concurrent.Callable} captures only immutable DTO values —
 *       no shared mutable state between tasks.</li>
 *   <li>{@link TransactionService} is thread-safe (stateless Spring bean).</li>
 *   <li>DB-level pessimistic locks (from Day 4) guarantee correct balances regardless
 *       of how many worker threads process the same account simultaneously.</li>
 *   <li>Results are collected sequentially after all futures are submitted; no
 *       concurrent writes to the results list.</li>
 * </ul>
 *
 * <h2>Failure handling</h2>
 * <ul>
 *   <li>A business failure in one task (e.g. insufficient balance) does NOT cancel
 *       other tasks — each {@link Future} is collected independently.</li>
 *   <li>Pool saturation → {@link TaskRejectedException} → item recorded as REJECTED.</li>
 *   <li>Future timeout → item recorded as FAILED with a timeout reason.</li>
 * </ul>
 */
@Service
public class BatchTransactionService {

    private static final Logger log = LoggerFactory.getLogger(BatchTransactionService.class);

    /** Maximum seconds to wait for a single task result before marking it as timed out. */
    private static final long TASK_TIMEOUT_SECONDS = 60L;

    private final TransactionService transactionService;
    private final TransactionThreadPoolExecutor executor;

    public BatchTransactionService(
            TransactionService transactionService,
            TransactionThreadPoolExecutor executor) {
        this.transactionService = transactionService;
        this.executor = executor;
    }

    /**
     * Processes a batch of transaction requests concurrently.
     *
     * <p>Steps:</p>
     * <ol>
     *   <li>Generate a batch ID if not supplied.</li>
     *   <li>Submit each valid item as a {@link java.util.concurrent.Callable} to the pool.</li>
     *   <li>Collect all {@link Future} results (or rejections / timeouts).</li>
     *   <li>Aggregate counts and build a {@link BatchTransactionResult}.</li>
     * </ol>
     *
     * @param request the validated batch request
     * @return a full summary including per-item results and performance metrics
     */
    public BatchTransactionResult processBatch(BatchTransactionRequest request) {
        String batchId = (request.batchId() != null && !request.batchId().isBlank())
                ? request.batchId()
                : "BATCH-" + UUID.randomUUID();

        List<BatchTransactionItem> items = request.transactions();
        int totalSubmitted = items.size();

        log.info("Batch {} started — {} transactions submitted", batchId, totalSubmitted);
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        // Phase 1: submit all tasks to the thread pool
        // Each entry is either a Future or a pre-resolved rejection placeholder
        record SubmitOutcome(Future<TransactionResponse> future, BatchTransactionItemResult rejection) {}
        List<SubmitOutcome> outcomes = new ArrayList<>(totalSubmitted);

        for (BatchTransactionItem item : items) {
            try {
                Future<TransactionResponse> future = executor.submit(buildCallable(item));
                outcomes.add(new SubmitOutcome(future, null));
            } catch (TaskRejectedException ex) {
                log.warn("Batch {} — task rejected (pool saturated): {}", batchId, ex.getMessage());
                outcomes.add(new SubmitOutcome(null,
                        BatchTransactionItemResult.failed(
                                item.transactionId(),
                                item.type(),
                                item.amount(),
                                TransactionStatus.REJECTED,
                                "Task rejected — thread pool saturated: " + ex.getMessage()
                        )));
            } catch (Exception ex) {
                log.error("Batch {} — unexpected error submitting task", batchId, ex);
                outcomes.add(new SubmitOutcome(null,
                        BatchTransactionItemResult.failed(
                                item.transactionId(),
                                item.type(),
                                item.amount(),
                                TransactionStatus.REJECTED,
                                "Submission error: " + ex.getMessage()
                        )));
            }
        }

        // Phase 2: collect results
        List<BatchTransactionItemResult> results = new ArrayList<>(totalSubmitted);
        int accepted = 0, rejected = 0, successful = 0, failed = 0;

        for (int i = 0; i < outcomes.size(); i++) {
            SubmitOutcome outcome = outcomes.get(i);
            BatchTransactionItem item = items.get(i);

            if (outcome.rejection() != null) {
                // Was rejected at submission time
                results.add(outcome.rejection());
                rejected++;
                continue;
            }

            accepted++;
            try {
                TransactionResponse response = outcome.future().get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (response != null && response.status() == TransactionStatus.SUCCESS) {
                    results.add(BatchTransactionItemResult.success(response));
                    successful++;
                } else if (response != null) {
                    // Business failure recorded by TransactionRecordingService
                    results.add(BatchTransactionItemResult.failed(
                            response.transactionId(),
                            response.transactionType(),
                            response.amount(),
                            response.status(),
                            response.failureReason()
                    ));
                    failed++;
                } else {
                    log.error("Batch {} — task {} returned null response", batchId, item.transactionId());
                    results.add(BatchTransactionItemResult.failed(
                            item.transactionId(),
                            item.type(),
                            item.amount(),
                            TransactionStatus.FAILED,
                            "Transaction returned null response"
                    ));
                    failed++;
                }
            } catch (CancellationException ex) {
                log.warn("Batch {} — task {} was cancelled", batchId, item.transactionId());
                results.add(BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.FAILED,
                        "Task was cancelled"
                ));
                failed++;
            } catch (ExecutionException ex) {
                // Business exception thrown by TransactionService (e.g. InsufficientBalanceException)
                // The failure record is already persisted by TransactionRecordingService.
                String reason = ex.getCause() != null
                        ? ex.getCause().getMessage()
                        : ex.getMessage();
                log.warn("Batch {} — task {} completed with business failure: {}",
                        batchId, item.transactionId(), reason);
                results.add(BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.FAILED,
                        reason
                ));
                failed++;
            } catch (TimeoutException ex) {
                log.error("Batch {} — task {} timed out after {}s",
                        batchId, item.transactionId(), TASK_TIMEOUT_SECONDS);
                outcome.future().cancel(true);
                results.add(BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.FAILED,
                        "Task timed out after " + TASK_TIMEOUT_SECONDS + " seconds"
                ));
                failed++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.error("Batch {} — interrupted while collecting results", batchId);
                results.add(BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.FAILED,
                        "Interrupted"
                ));
                failed++;
            }
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LocalDateTime completedAt = LocalDateTime.now();

        BatchTransactionResult summary = BatchTransactionResult.of(
                batchId, startedAt, completedAt, durationMs,
                totalSubmitted, accepted, rejected, successful, failed,
                executor.getCorePoolSize(), executor.getQueueCapacity(),
                results);

        log.info("Batch {} completed in {}ms — submitted={} accepted={} rejected={} successful={} failed={} TPS={}",
                batchId, durationMs, totalSubmitted, accepted, rejected, successful, failed, summary.tps());

        return summary;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds a {@link java.util.concurrent.Callable} that calls the correct
     * {@link TransactionService} method based on the item's {@link TransactionType}.
     *
     * <p>Important: each Callable captures only <em>immutable</em> DTO values from the
     * item — no JPA entities, no EntityManager references. This is safe to run on any
     * worker thread.</p>
     */
    private java.util.concurrent.Callable<TransactionResponse> buildCallable(BatchTransactionItem item) {
        return switch (item.type()) {
            case DEPOSIT -> () -> transactionService.deposit(
                    new DepositRequest(
                            item.transactionId(),
                            item.destinationAccountId(),
                            item.amount(),
                            item.description()
                    )
            );
            case WITHDRAWAL -> () -> transactionService.withdraw(
                    new WithdrawalRequest(
                            item.transactionId(),
                            item.sourceAccountId(),
                            item.amount(),
                            item.description()
                    )
            );
            case TRANSFER -> () -> transactionService.transfer(
                    new TransferRequest(
                            item.transactionId(),
                            item.sourceAccountId(),
                            item.destinationAccountId(),
                            item.amount(),
                            item.description()
                    )
            );
        };
    }
}
