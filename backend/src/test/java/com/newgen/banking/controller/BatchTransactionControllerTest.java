package com.newgen.banking.controller;

import com.newgen.banking.dto.BatchTransactionItemResult;
import com.newgen.banking.dto.BatchTransactionResult;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.GlobalExceptionHandler;
import com.newgen.banking.service.BatchTransactionService;
import com.newgen.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@code POST /api/transactions/batch}.
 *
 * <p>These tests verify HTTP request validation and JSON response structure
 * without touching the database — {@link BatchTransactionService} is mocked.</p>
 */
@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class BatchTransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // Both services must be mocked because TransactionController requires both
    @MockitoBean
    private TransactionService transactionService;

    @MockitoBean
    private BatchTransactionService batchTransactionService;

    // ── Happy-path ────────────────────────────────────────────────────────────

    @Test
    void batch_ShouldReturn200WithSummary_WhenValidBatchSubmitted() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 27, 10, 0);

        BatchTransactionItemResult depositResult = new BatchTransactionItemResult(
                "TXN-B-DEP", TransactionType.DEPOSIT, new BigDecimal("500.00"),
                TransactionStatus.SUCCESS, null);

        BatchTransactionItemResult withdrawalResult = new BatchTransactionItemResult(
                "TXN-B-WDR", TransactionType.WITHDRAWAL, new BigDecimal("200.00"),
                TransactionStatus.SUCCESS, null);

        BatchTransactionItemResult transferResult = new BatchTransactionItemResult(
                "TXN-B-TRF", TransactionType.TRANSFER, new BigDecimal("100.00"),
                TransactionStatus.SUCCESS, null);

        BatchTransactionResult mockResult = BatchTransactionResult.of(
                "BATCH-TEST-001", now, now.plusSeconds(1), 1000L,
                3, 3, 0, 3, 0,
                4, 100,
                List.of(depositResult, withdrawalResult, transferResult)
        );

        when(batchTransactionService.processBatch(any())).thenReturn(mockResult);

        String body = """
                {
                  "batchId": "BATCH-TEST-001",
                  "transactions": [
                    { "type": "DEPOSIT",    "destinationAccountId": 1, "amount": 500.00 },
                    { "type": "WITHDRAWAL", "sourceAccountId": 2,      "amount": 200.00 },
                    { "type": "TRANSFER",   "sourceAccountId": 1, "destinationAccountId": 2, "amount": 100.00 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("BATCH-TEST-001"))
                .andExpect(jsonPath("$.totalSubmitted").value(3))
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.successful").value(3))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.durationMs").value(1000))
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].transactionId").value("TXN-B-DEP"))
                .andExpect(jsonPath("$.results[0].status").value("SUCCESS"));
    }

    @Test
    void batch_ShouldReturn200_WithPartialFailure_WhenOneItemFails() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 27, 10, 0);

        BatchTransactionItemResult successItem = new BatchTransactionItemResult(
                "TXN-OK", TransactionType.DEPOSIT, new BigDecimal("1000.00"),
                TransactionStatus.SUCCESS, null);

        BatchTransactionItemResult failedItem = new BatchTransactionItemResult(
                "TXN-FAIL", TransactionType.WITHDRAWAL, new BigDecimal("9999.00"),
                TransactionStatus.FAILED, "Insufficient balance");

        BatchTransactionResult mockResult = BatchTransactionResult.of(
                "BATCH-PARTIAL", now, now.plusSeconds(1), 200L,
                2, 2, 0, 1, 1,
                4, 100,
                List.of(successItem, failedItem)
        );

        when(batchTransactionService.processBatch(any())).thenReturn(mockResult);

        String body = """
                {
                  "transactions": [
                    { "type": "DEPOSIT",    "destinationAccountId": 1, "amount": 1000.00 },
                    { "type": "WITHDRAWAL", "sourceAccountId": 2,      "amount": 9999.00 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successful").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.results[1].status").value("FAILED"))
                .andExpect(jsonPath("$.results[1].failureReason").value("Insufficient balance"));
    }

    // ── Validation failures (400) ─────────────────────────────────────────────

    @Test
    void batch_ShouldReturn400_WhenTransactionsListIsEmpty() throws Exception {
        String body = """
                {
                  "transactions": []
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void batch_ShouldReturn400_WhenTransactionsExceed1000Items() throws Exception {
        // Build 1001 minimal DEPOSIT items
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            if (i > 0) items.append(",");
            items.append("""
                    {"type":"DEPOSIT","destinationAccountId":1,"amount":1.00}
                    """);
        }

        String body = "{\"transactions\":[" + items + "]}";

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void batch_ShouldReturn400_WhenItemIsMissingType() throws Exception {
        String body = """
                {
                  "transactions": [
                    { "destinationAccountId": 1, "amount": 500.00 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void batch_ShouldReturn400_WhenItemIsMissingAmount() throws Exception {
        String body = """
                {
                  "transactions": [
                    { "type": "DEPOSIT", "destinationAccountId": 1 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void batch_ShouldReturn400_WhenItemAmountIsZero() throws Exception {
        String body = """
                {
                  "transactions": [
                    { "type": "DEPOSIT", "destinationAccountId": 1, "amount": 0.00 }
                  ]
                }
                """;

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void batch_ShouldReturn400_WhenBatchIdExceeds64Chars() throws Exception {
        String longBatchId = "B".repeat(65);
        String body = """
                {
                  "batchId": "%s",
                  "transactions": [
                    { "type": "DEPOSIT", "destinationAccountId": 1, "amount": 100.00 }
                  ]
                }
                """.formatted(longBatchId);

        mockMvc.perform(post("/api/transactions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
