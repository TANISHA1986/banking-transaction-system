package com.newgen.banking.controller;

import com.newgen.banking.dto.DepositRequest;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.dto.TransactionResponse;
import com.newgen.banking.dto.TransferRequest;
import com.newgen.banking.dto.WithdrawalRequest;
import com.newgen.banking.entity.TransactionStatus;
import com.newgen.banking.entity.TransactionType;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.DuplicateTransactionException;
import com.newgen.banking.exception.GlobalExceptionHandler;
import com.newgen.banking.exception.InactiveAccountException;
import com.newgen.banking.exception.InsufficientBalanceException;
import com.newgen.banking.exception.TransactionNotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import(GlobalExceptionHandler.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService transactionService;

    @Test
    void deposit_ShouldReturn201_WhenValid() throws Exception {
        TransactionResponse response = new TransactionResponse(
                1L, "TXN-DEP-1", TransactionType.DEPOSIT, new BigDecimal("500.00"),
                null, null, 10L, "NG1000000010", TransactionStatus.SUCCESS, "Cash deposit", null,
                LocalDateTime.of(2026, 11, 26, 10, 30)
        );
        when(transactionService.deposit(any(DepositRequest.class))).thenReturn(response);

        String body = """
                {
                  "transactionId": "TXN-DEP-1",
                  "accountId": 10,
                  "amount": 500.00,
                  "description": "Cash deposit"
                }
                """;

        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("TXN-DEP-1"))
                .andExpect(jsonPath("$.transactionType").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(500.00));
    }

    @Test
    void deposit_ShouldReturn400_WhenValidationFails() throws Exception {
        String invalidBody = """
                {
                  "accountId": -1,
                  "amount": 0.00
                }
                """;

        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.accountId").exists())
                .andExpect(jsonPath("$.validationErrors.amount").exists());

        verify(transactionService, never()).deposit(any());
    }

    @Test
    void withdraw_ShouldReturn422_WhenInsufficientBalance() throws Exception {
        when(transactionService.withdraw(any(WithdrawalRequest.class)))
                .thenThrow(new InsufficientBalanceException("Insufficient balance"));

        String body = """
                {
                  "transactionId": "TXN-WDR-LOW",
                  "accountId": 10,
                  "amount": 9000.00
                }
                """;

        mockMvc.perform(post("/api/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("Insufficient balance"));
    }

    @Test
    void withdraw_ShouldReturn409_WhenAccountInactive() throws Exception {
        when(transactionService.withdraw(any(WithdrawalRequest.class)))
                .thenThrow(new InactiveAccountException("Account is FROZEN"));

        String body = """
                {
                  "transactionId": "TXN-FROZEN",
                  "accountId": 10,
                  "amount": 100.00
                }
                """;

        mockMvc.perform(post("/api/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void transfer_ShouldReturn201_AndHandleDuplicateAndNotFound() throws Exception {
        TransactionResponse response = new TransactionResponse(
                2L, "TXN-TRF-1", TransactionType.TRANSFER, new BigDecimal("250.00"),
                10L, "NG1000000010", 11L, "NG1000000011", TransactionStatus.SUCCESS, "Transfer", null,
                LocalDateTime.of(2026, 11, 26, 10, 30)
        );
        when(transactionService.transfer(any(TransferRequest.class)))
                .thenReturn(response)
                .thenThrow(new DuplicateTransactionException("Duplicate transaction"))
                .thenThrow(new AccountNotFoundException("Destination account not found"));

        String body = """
                {
                  "transactionId": "TXN-TRF-1",
                  "sourceAccountId": 10,
                  "destinationAccountId": 11,
                  "amount": 250.00,
                  "description": "Transfer"
                }
                """;

        mockMvc.perform(post("/api/transactions/transfer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("TRANSFER"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(post("/api/transactions/transfer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(post("/api/transactions/transfer").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getTransactionAndHistory_ShouldReturn200_And404WhenMissing() throws Exception {
        TransactionResponse tx = new TransactionResponse(
                1L, "TXN-1", TransactionType.DEPOSIT, new BigDecimal("100.00"),
                null, null, 10L, "NG1000000010", TransactionStatus.SUCCESS, null, null,
                LocalDateTime.of(2026, 11, 26, 10, 30)
        );
        when(transactionService.getTransactionByTransactionId("TXN-1")).thenReturn(tx);
        when(transactionService.getTransactionByTransactionId("TXN-404"))
                .thenThrow(new TransactionNotFoundException("Transaction not found"));
        when(transactionService.getTransactionsByAccountId(10L, 0, 20, "createdAt", "desc"))
                .thenReturn(new PagedResponse<>(List.of(tx), 0, 20, 1, 1, true, true));

        mockMvc.perform(get("/api/transactions/TXN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("TXN-1"));

        mockMvc.perform(get("/api/transactions/TXN-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/transactions/account/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].transactionId").value("TXN-1"));
    }
}
