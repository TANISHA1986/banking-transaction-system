package com.newgen.banking.controller;

import com.newgen.banking.dto.AccountRequest;
import com.newgen.banking.dto.AccountResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.entity.AccountStatus;
import com.newgen.banking.entity.AccountType;
import com.newgen.banking.exception.AccountNotFoundException;
import com.newgen.banking.exception.CustomerNotFoundException;
import com.newgen.banking.exception.GlobalExceptionHandler;
import com.newgen.banking.service.AccountService;
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

@WebMvcTest(AccountController.class)
@Import(GlobalExceptionHandler.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void createAccount_ShouldReturn201_WhenRequestIsValid() throws Exception {
        AccountResponse response = buildResponse(1L, "NG1000000001", 10L, "Aarav Sharma",
                AccountType.SAVINGS, new BigDecimal("1500.00"));
        when(accountService.createAccount(any(AccountRequest.class))).thenReturn(response);

        String body = """
                {
                  "customerId": 10,
                  "accountType": "SAVINGS",
                  "initialBalance": 1500.00
                }
                """;

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountNumber").value("NG1000000001"))
                .andExpect(jsonPath("$.customerId").value(10))
                .andExpect(jsonPath("$.customerName").value("Aarav Sharma"))
                .andExpect(jsonPath("$.accountType").value("SAVINGS"))
                .andExpect(jsonPath("$.balance").value(1500.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createAccount_ShouldReturn400WithValidationErrors_WhenInputIsInvalid() throws Exception {
        String invalidBody = """
                {
                  "customerId": -5,
                  "accountType": null,
                  "initialBalance": -100.00
                }
                """;

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.validationErrors.customerId").exists())
                .andExpect(jsonPath("$.validationErrors.accountType").exists())
                .andExpect(jsonPath("$.validationErrors.initialBalance").exists());

        verify(accountService, never()).createAccount(any());
    }

    @Test
    void createAccount_ShouldReturn400_WhenAccountTypeIsUnknown() throws Exception {
        String invalidEnumBody = """
                {
                  "customerId": 10,
                  "accountType": "CRYPTO",
                  "initialBalance": 100.00
                }
                """;

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidEnumBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(accountService, never()).createAccount(any());
    }

    @Test
    void createAccount_ShouldReturn404_WhenCustomerDoesNotExist() throws Exception {
        when(accountService.createAccount(any(AccountRequest.class)))
                .thenThrow(new CustomerNotFoundException("Customer not found with ID: 999"));

        String body = """
                {
                  "customerId": 999,
                  "accountType": "CURRENT",
                  "initialBalance": 500.00
                }
                """;

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Customer not found with ID: 999"));
    }

    @Test
    void getAllAccounts_ShouldReturn200WithPagedResponse() throws Exception {
        AccountResponse account = buildResponse(1L, "NG1000000001", 10L, "Aarav Sharma",
                AccountType.SAVINGS, new BigDecimal("500.00"));
        PagedResponse<AccountResponse> paged = new PagedResponse<>(List.of(account), 0, 20, 1, 1, true, true);
        when(accountService.getAllAccounts(0, 20, "id", "asc")).thenReturn(paged);

        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value("NG1000000001"));
    }

    @Test
    void getAccountById_ShouldReturn200_WhenFound() throws Exception {
        AccountResponse account = buildResponse(7L, "NG1000000007", 10L, "Aarav Sharma",
                AccountType.SAVINGS, new BigDecimal("900.00"));
        when(accountService.getAccountById(7L)).thenReturn(account);

        mockMvc.perform(get("/api/accounts/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.accountNumber").value("NG1000000007"));
    }

    @Test
    void getAccountById_ShouldReturn404_WhenNotFound() throws Exception {
        when(accountService.getAccountById(404L))
                .thenThrow(new AccountNotFoundException("Account not found with ID: 404"));

        mockMvc.perform(get("/api/accounts/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Account not found with ID: 404"));
    }

    @Test
    void getAccountById_ShouldReturn400_WhenIdIsNotNumeric() throws Exception {
        mockMvc.perform(get("/api/accounts/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getAccountByNumber_ShouldReturn200_WhenFound() throws Exception {
        AccountResponse account = buildResponse(7L, "NG1000000007", 10L, "Aarav Sharma",
                AccountType.CURRENT, new BigDecimal("300.00"));
        when(accountService.getAccountByAccountNumber("NG1000000007")).thenReturn(account);

        mockMvc.perform(get("/api/accounts/number/NG1000000007"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("NG1000000007"))
                .andExpect(jsonPath("$.accountType").value("CURRENT"));
    }

    @Test
    void getAccountByNumber_ShouldReturn404_WhenMissing() throws Exception {
        when(accountService.getAccountByAccountNumber("NG9999999999"))
                .thenThrow(new AccountNotFoundException("Account not found with account number: NG9999999999"));

        mockMvc.perform(get("/api/accounts/number/NG9999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getAccountsByCustomerId_ShouldReturn200_And404WhenMissing() throws Exception {
        List<AccountResponse> accounts = List.of(
                buildResponse(1L, "NG1000000001", 10L, "Aarav Sharma", AccountType.SAVINGS, new BigDecimal("100.00"))
        );
        when(accountService.getAccountsByCustomerId(10L)).thenReturn(accounts);
        when(accountService.getAccountsByCustomerId(999L))
                .thenThrow(new CustomerNotFoundException("Customer not found with ID: 999"));

        mockMvc.perform(get("/api/accounts/customer/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountNumber").value("NG1000000001"));

        mockMvc.perform(get("/api/accounts/customer/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private static AccountResponse buildResponse(
            Long id,
            String accountNumber,
            Long customerId,
            String customerName,
            AccountType type,
            BigDecimal balance) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0);
        return new AccountResponse(id, accountNumber, customerId, customerName, type, balance, AccountStatus.ACTIVE, now, now);
    }
}
