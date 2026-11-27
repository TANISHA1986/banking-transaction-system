package com.newgen.banking.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary result returned by {@code POST /api/transactions/batch}.
 *
 * <p>Metrics captured:</p>
 * <ul>
 *   <li><b>totalSubmitted</b>  — items in the original request</li>
 *   <li><b>accepted</b>        — items that were successfully queued for execution</li>
 *   <li><b>rejected</b>        — items rejected by the pool (queue full) or invalid</li>
 *   <li><b>successful</b>      — accepted items that completed with SUCCESS</li>
 *   <li><b>failed</b>          — accepted items that completed with FAILED or REJECTED status</li>
 *   <li><b>durationMs</b>      — wall-clock ms from first submission to last result</li>
 *   <li><b>tps</b>             — transactions per second (successful / elapsed seconds)</li>
 * </ul>
 */
public record BatchTransactionResult(
        String batchId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        long durationMs,
        int totalSubmitted,
        int accepted,
        int rejected,
        int successful,
        int failed,
        BigDecimal tps,
        int workerCount,
        int queueCapacity,
        List<BatchTransactionItemResult> results
) {
    /**
     * Builds a {@link BatchTransactionResult} from raw counters and the item result list.
     * TPS is computed as {@code successful / (durationMs / 1000.0)}, guarded against
     * division by zero when duration is too short to measure.
     */
    public static BatchTransactionResult of(
            String batchId,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            long durationMs,
            int totalSubmitted,
            int accepted,
            int rejected,
            int successful,
            int failed,
            int workerCount,
            int queueCapacity,
            List<BatchTransactionItemResult> results) {

        BigDecimal tps;
        if (durationMs > 0) {
            BigDecimal elapsedSeconds = BigDecimal.valueOf(durationMs)
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            tps = BigDecimal.valueOf(successful)
                    .divide(elapsedSeconds, 2, RoundingMode.HALF_UP);
        } else {
            tps = BigDecimal.ZERO;
        }

        return new BatchTransactionResult(
                batchId, startedAt, completedAt, durationMs,
                totalSubmitted, accepted, rejected, successful, failed,
                tps, workerCount, queueCapacity, results);
    }
}
