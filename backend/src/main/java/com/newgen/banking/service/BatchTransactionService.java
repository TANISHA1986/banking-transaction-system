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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
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
     * Processes a batch of transaction requests concurrently with bounded backpressure.
     *
     * <p>A sliding window limits the number of in-flight tasks submitted to the executor
     * to at most half of the executor's queue capacity (bounded between 1 and 100).
     * This controlled submission ensures that large valid batches (up to 1,000 items)
     * can be processed without saturating the bounded queue on their own, while leaving
     * headroom for concurrent requests. If the queue becomes saturated due to genuine
     * external load, {@link TaskRejectedException} is caught and recorded as {@link TransactionStatus#REJECTED}.</p>
     *
     * @param request the validated batch request
     * @return a full summary including per-item results and performance metrics
     */
    public BatchTransactionResult processBatch(BatchTransactionRequest request) {
        String batchId = (request.batchId() != null && !request.batchId().isBlank())
                ? request.batchId()
                : "BATCH-" + UUID.randomUUID();

        List<BatchTransactionItem> items = request.transactions() != null
                ? request.transactions()
                : List.of();
        int totalSubmitted = items.size();

        log.info("Batch {} started — {} transactions submitted", batchId, totalSubmitted);
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();

        // Calculate sliding window capacity for in-flight tasks.
        // Uses at most half of the queue capacity (capped at 100) to avoid self-saturation.
        int queueCap = executor.getQueueCapacity();
        int maxInFlight = (queueCap > 0) ? Math.max(1, Math.min(queueCap / 2, 100)) : 50;

        record InFlightTask(int index, BatchTransactionItem item, Future<TransactionResponse> future) {}
        Queue<InFlightTask> inFlight = new ArrayDeque<>();
        BatchTransactionItemResult[] results = new BatchTransactionItemResult[totalSubmitted];

        for (int i = 0; i < totalSubmitted; i++) {
            BatchTransactionItem item = items.get(i);

            // Eagerly harvest any in-flight tasks that have already completed
            while (!inFlight.isEmpty() && inFlight.peek().future().isDone()) {
                InFlightTask completed = inFlight.poll();
                resolveAndStore(completed.index(), completed.item(), completed.future(), results, batchId);
            }

            // If in-flight window is full, wait for the oldest task to finish (bounded backpressure)
            while (inFlight.size() >= maxInFlight) {
                InFlightTask oldest = inFlight.poll();
                resolveAndStore(oldest.index(), oldest.item(), oldest.future(), results, batchId);
            }

            // Submit next task to executor
            try {
                Future<TransactionResponse> future = executor.submit(buildCallable(item));
                inFlight.add(new InFlightTask(i, item, future));
            } catch (TaskRejectedException ex) {
                // Genuine queue saturation from concurrent external load
                log.warn("Batch {} — task rejected (pool saturated): {}", batchId, ex.getMessage());
                results[i] = BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.REJECTED,
                        "Task rejected — thread pool saturated: " + ex.getMessage()
                );
            } catch (Exception ex) {
                log.error("Batch {} — unexpected error submitting task", batchId, ex);
                results[i] = BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.REJECTED,
                        "Submission error: " + ex.getMessage()
                );
            }
        }

        // Drain any remaining in-flight tasks
        while (!inFlight.isEmpty()) {
            InFlightTask remaining = inFlight.poll();
            resolveAndStore(remaining.index(), remaining.item(), remaining.future(), results, batchId);
        }

        // Aggregate results and metrics
        List<BatchTransactionItemResult> resultList = new ArrayList<>(totalSubmitted);
        int accepted = 0, rejected = 0, successful = 0, failed = 0;

        for (int i = 0; i < totalSubmitted; i++) {
            BatchTransactionItemResult itemResult = results[i];
            resultList.add(itemResult);
            if (itemResult.status() == TransactionStatus.REJECTED) {
                rejected++;
            } else {
                accepted++;
                if (itemResult.status() == TransactionStatus.SUCCESS) {
                    successful++;
                } else {
                    failed++;
                }
            }
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LocalDateTime completedAt = LocalDateTime.now();

        BatchTransactionResult summary = BatchTransactionResult.of(
                batchId, startedAt, completedAt, durationMs,
                totalSubmitted, accepted, rejected, successful, failed,
                executor.getCorePoolSize(), executor.getQueueCapacity(),
                resultList);

        log.info("Batch {} completed in {}ms — submitted={} accepted={} rejected={} successful={} failed={} TPS={}",
                batchId, durationMs, totalSubmitted, accepted, rejected, successful, failed, summary.tps());

        return summary;
    }

    private void resolveAndStore(
            int index,
            BatchTransactionItem item,
            Future<TransactionResponse> future,
            BatchTransactionItemResult[] results,
            String batchId) {
        try {
            TransactionResponse response = future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response != null && response.status() == TransactionStatus.SUCCESS) {
                results[index] = BatchTransactionItemResult.success(response);
            } else if (response != null) {
                // Business failure recorded by TransactionRecordingService
                results[index] = BatchTransactionItemResult.failed(
                        response.transactionId(),
                        response.transactionType(),
                        response.amount(),
                        response.status(),
                        response.failureReason()
                );
            } else {
                log.error("Batch {} — task {} returned null response", batchId, item.transactionId());
                results[index] = BatchTransactionItemResult.failed(
                        item.transactionId(),
                        item.type(),
                        item.amount(),
                        TransactionStatus.FAILED,
                        "Transaction returned null response"
                );
            }
        } catch (CancellationException ex) {
            log.warn("Batch {} — task {} was cancelled", batchId, item.transactionId());
            results[index] = BatchTransactionItemResult.failed(
                    item.transactionId(),
                    item.type(),
                    item.amount(),
                    TransactionStatus.FAILED,
                    "Task was cancelled"
            );
        } catch (ExecutionException ex) {
            // Business exception thrown by TransactionService (e.g. InsufficientBalanceException)
            // The failure record is already persisted by TransactionRecordingService.
            String reason = ex.getCause() != null
                    ? ex.getCause().getMessage()
                    : ex.getMessage();
            log.warn("Batch {} — task {} completed with business failure: {}",
                    batchId, item.transactionId(), reason);
            results[index] = BatchTransactionItemResult.failed(
                    item.transactionId(),
                    item.type(),
                    item.amount(),
                    TransactionStatus.FAILED,
                    reason
            );
        } catch (TimeoutException ex) {
            log.error("Batch {} — task {} timed out after {}s",
                    batchId, item.transactionId(), TASK_TIMEOUT_SECONDS);
            future.cancel(true);
            results[index] = BatchTransactionItemResult.failed(
                    item.transactionId(),
                    item.type(),
                    item.amount(),
                    TransactionStatus.FAILED,
                    "Task timed out after " + TASK_TIMEOUT_SECONDS + " seconds"
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Batch {} — interrupted while collecting results", batchId);
            results[index] = BatchTransactionItemResult.failed(
                    item.transactionId(),
                    item.type(),
                    item.amount(),
                    TransactionStatus.FAILED,
                    "Interrupted"
            );
        }
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
