package com.newgen.banking.service;

import com.newgen.banking.dto.BatchTransactionItem;
import com.newgen.banking.dto.BatchTransactionItemResult;
import com.newgen.banking.dto.BatchTransactionRequest;
import com.newgen.banking.dto.BatchTransactionResult;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.executor.TaskRejectedException;
import com.newgen.banking.executor.TransactionThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchTransactionService}.
 *
 * <p>Both {@link TransactionService} and {@link TransactionThreadPoolExecutor} are mocked
 * so these tests run without Spring context or database. They verify correct aggregation
 * of batch results, handling of business failures, task rejections, and metrics.</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchTransactionServiceTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private TransactionThreadPoolExecutor executor;

    @InjectMocks
    private BatchTransactionService batchService;

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static TransactionResponse successResponse(String txId, TransactionType type, BigDecimal amount) {
        return new TransactionResponse(1L, txId, type, amount,
                null, null, 1L, "NG1000000001",
                TransactionStatus.SUCCESS, null, null,
                LocalDateTime.now());
    }

    /**
     * Returns a real {@link CompletableFuture} already completed with {@code value}.
     * {@code future.get(long, TimeUnit)} returns {@code value} without any Mockito stubbing.
     */
    private static <T> Future<T> completedFuture(T value) {
        return CompletableFuture.completedFuture(value);
    }

    /**
     * Returns a real {@link CompletableFuture} already completed exceptionally.
     * {@code future.get(long, TimeUnit)} throws {@link java.util.concurrent.ExecutionException}
     * wrapping {@code cause} — exactly what {@link BatchTransactionService} expects.
     */
    private static <T> Future<T> failingFuture(Throwable cause) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(cause);
        return f;
    }

    /**
     * Returns a real {@link CompletableFuture} that has been cancelled.
     * {@code future.get(long, TimeUnit)} throws {@link java.util.concurrent.CancellationException}.
     */
    private static <T> Future<T> cancelledFuture() {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.cancel(true);
        return f;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("All successful deposits: batch result shows correct counts and TPS > 0")
    void processBatch_AllSuccessful_ShouldReturnCorrectCounts() throws Exception {
        BigDecimal amount = new BigDecimal("100.00");
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-1", TransactionType.DEPOSIT, null, 1L, amount, null),
                new BatchTransactionItem("TXN-2", TransactionType.DEPOSIT, null, 1L, amount, null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-1", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any()))
                .thenReturn(completedFuture(successResponse("TXN-1", TransactionType.DEPOSIT, amount)))
                .thenReturn(completedFuture(successResponse("TXN-2", TransactionType.DEPOSIT, amount)));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals("BATCH-1", result.batchId());
        assertEquals(2, result.totalSubmitted());
        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(2, result.successful());
        assertEquals(0, result.failed());
        assertEquals(2, result.results().size());
    }

    @Test
    @DisplayName("One business failure: failed count increments, successful count unaffected")
    void processBatch_OneBusinessFailure_ShouldCountFailed() throws Exception {
        BigDecimal amount = new BigDecimal("5000.00");
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-OK", TransactionType.DEPOSIT, null, 1L, new BigDecimal("100.00"), null),
                new BatchTransactionItem("TXN-FAIL", TransactionType.WITHDRAWAL, 2L, null, amount, null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest(null, items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any()))
                .thenReturn(completedFuture(successResponse("TXN-OK", TransactionType.DEPOSIT, new BigDecimal("100.00"))))
                .thenReturn(failingFuture(new InsufficientBalanceException("Insufficient balance")));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(2, result.totalSubmitted());
        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(1, result.successful());
        assertEquals(1, result.failed());

        // Batch ID should be auto-generated when null
        assertNotNull(result.batchId());
        assertTrue(result.batchId().startsWith("BATCH-"));
    }

    @Test
    @DisplayName("Task rejected by pool: rejected count increments, accepted count unaffected")
    void processBatch_TaskRejectedByPool_ShouldCountRejected() throws Exception {
        BigDecimal amount = new BigDecimal("100.00");
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-REJ", TransactionType.DEPOSIT, null, 1L, amount, null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-REJ", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any())).thenThrow(new TaskRejectedException("Pool full"));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(1, result.totalSubmitted());
        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(0, result.successful());
        assertEquals(1, result.results().size());
        assertEquals(TransactionStatus.REJECTED, result.results().get(0).status());
    }

    @Test
    @DisplayName("Partial batch: some succeed, some fail, some rejected — counts all correct")
    void processBatch_PartialFailure_ShouldAggregateCorrectly() throws Exception {
        BigDecimal amount = new BigDecimal("100.00");
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-S1", TransactionType.DEPOSIT, null, 1L, amount, null),
                new BatchTransactionItem("TXN-F1", TransactionType.WITHDRAWAL, 2L, null, amount, null),
                new BatchTransactionItem("TXN-R1", TransactionType.DEPOSIT, null, 1L, amount, null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-MIXED", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any()))
                .thenReturn(completedFuture(successResponse("TXN-S1", TransactionType.DEPOSIT, amount)))
                .thenReturn(failingFuture(new InsufficientBalanceException("No funds")))
                .thenThrow(new TaskRejectedException("Pool full"));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(3, result.totalSubmitted());
        assertEquals(2, result.accepted());   // TXN-S1 and TXN-F1 were accepted
        assertEquals(1, result.rejected());   // TXN-R1 was rejected at submit
        assertEquals(1, result.successful()); // TXN-S1
        assertEquals(1, result.failed());     // TXN-F1
    }

    @Test
    @DisplayName("TPS is 0 when successful count is 0")
    void processBatch_AllFailed_ShouldHaveZeroTps() throws Exception {
        BigDecimal amount = new BigDecimal("100.00");
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-X", TransactionType.WITHDRAWAL, 1L, null, amount, null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-FAIL", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any()))
                .thenReturn(failingFuture(new InsufficientBalanceException("No funds")));

        BatchTransactionResult result = batchService.processBatch(request);
        assertEquals(0, result.successful());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.tps()));
    }

    @Test
    @DisplayName("Results list contains one entry per submitted item")
    void processBatch_ShouldReturnOneResultPerItem() throws Exception {
        int itemCount = 5;
        List<BatchTransactionItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new BatchTransactionItem("TXN-" + i, TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null));
        }
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-5", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        for (int i = 0; i < itemCount; i++) {
            when(executor.submit(any()))
                    .thenReturn(completedFuture(successResponse("TXN-" + i, TransactionType.DEPOSIT, new BigDecimal("10.00"))));
        }

        BatchTransactionResult result = batchService.processBatch(request);
        assertEquals(itemCount, result.results().size());
    }

    @Test
    @DisplayName("Cancelled task: future throws CancellationException, recorded as failed")
    void processBatch_CancelledTask_ShouldCountFailed() {
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-CAN", TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-CANCEL", items);

        when(executor.getCorePoolSize()).thenReturn(5);
        when(executor.getQueueCapacity()).thenReturn(100);
        when(executor.submit(any())).thenReturn(cancelledFuture());

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(1, result.totalSubmitted());
        assertEquals(1, result.accepted());
        assertEquals(0, result.successful());
        assertEquals(1, result.failed());
        assertEquals(TransactionStatus.FAILED, result.results().get(0).status());
        assertEquals("Task was cancelled", result.results().get(0).failureReason());
    }

    @Test
    @DisplayName("Batch larger than queue capacity: sliding window backpressure allows all items to complete")
    void processBatch_BatchLargerThanQueueCapacity_AllCompleteSuccessfully() {
        int count = 25;
        List<BatchTransactionItem> items = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new BatchTransactionItem("TXN-LARGE-" + i, TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null));
        }
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-LARGE-QUEUE", items);

        // Queue capacity is 10, meaning maxInFlight = 5 (much smaller than batch size 25)
        when(executor.getCorePoolSize()).thenReturn(4);
        when(executor.getQueueCapacity()).thenReturn(10);
        when(executor.submit(any())).thenAnswer(invocation ->
                completedFuture(successResponse("TXN-LARGE", TransactionType.DEPOSIT, new BigDecimal("10.00"))));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(25, result.totalSubmitted());
        assertEquals(25, result.accepted(), "All 25 items should be accepted via sliding window");
        assertEquals(0, result.rejected(), "No items should be rejected by self-saturation");
        assertEquals(25, result.successful(), "All 25 items should succeed");
        assertEquals(0, result.failed());
        assertEquals(25, result.results().size());
    }

    @Test
    @DisplayName("Genuine queue saturation from concurrent load: rejections recorded gracefully while accepted tasks complete")
    void processBatch_GenuineQueueSaturation_RejectionsHandledCorrectly() {
        List<BatchTransactionItem> items = List.of(
                new BatchTransactionItem("TXN-SAT-1", TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null),
                new BatchTransactionItem("TXN-SAT-2", TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null),
                new BatchTransactionItem("TXN-SAT-3", TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null),
                new BatchTransactionItem("TXN-SAT-4", TransactionType.DEPOSIT, null, 1L, new BigDecimal("10.00"), null)
        );
        BatchTransactionRequest request = new BatchTransactionRequest("BATCH-SATURATION", items);

        when(executor.getCorePoolSize()).thenReturn(2);
        when(executor.getQueueCapacity()).thenReturn(10);
        when(executor.submit(any()))
                .thenReturn(completedFuture(successResponse("TXN-SAT-1", TransactionType.DEPOSIT, new BigDecimal("10.00"))))
                .thenReturn(completedFuture(successResponse("TXN-SAT-2", TransactionType.DEPOSIT, new BigDecimal("10.00"))))
                .thenThrow(new TaskRejectedException("External saturation: pool and queue full"))
                .thenReturn(completedFuture(successResponse("TXN-SAT-4", TransactionType.DEPOSIT, new BigDecimal("10.00"))));

        BatchTransactionResult result = batchService.processBatch(request);

        assertEquals(4, result.totalSubmitted());
        assertEquals(3, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(3, result.successful());
        assertEquals(0, result.failed());
        assertEquals(TransactionStatus.REJECTED, result.results().get(2).status());
        assertTrue(result.results().get(2).failureReason().contains("pool saturated"));
    }
}
