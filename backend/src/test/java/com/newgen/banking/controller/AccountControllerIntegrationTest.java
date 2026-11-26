package com.newgen.banking.controller;

import com.newgen.banking.entity.Account;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.repository.AccountRepository;
import com.newgen.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests for Account APIs against PostgreSQL (banking_test_db).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void createAndRetrieveAccount_ShouldPersistInDatabaseAndLinkToCustomer() throws Exception {
        Customer customer = saveCustomer("Siddharth Iyer", "siddharth.iyer@example.com");

        String body = """
                {
                  "customerId": %d,
                  "accountType": "SAVINGS",
                  "initialBalance": 2500.75
                }
                """.formatted(customer.getId());

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.accountNumber").isString())
                .andExpect(jsonPath("$.customerId").value(customer.getId()))
                .andExpect(jsonPath("$.customerName").value("Siddharth Iyer"))
                .andExpect(jsonPath("$.accountType").value("SAVINGS"))
                .andExpect(jsonPath("$.balance").value(2500.75))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        List<Account> savedAccounts = accountRepository.findByCustomerIdOrderByIdAsc(customer.getId());
        assertEquals(1, savedAccounts.size());
        Account persisted = savedAccounts.get(0);
        assertTrue(persisted.getAccountNumber().startsWith("NG"));
        assertEquals(new BigDecimal("2500.75"), persisted.getBalance());

        mockMvc.perform(get("/api/accounts/" + persisted.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.accountNumber").value(persisted.getAccountNumber()))
                .andExpect(jsonPath("$.customerId").value(customer.getId()));

        mockMvc.perform(get("/api/accounts/number/" + persisted.getAccountNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.balance").value(2500.75));
    }

    @Test
    void createMultipleAccounts_ShouldGenerateUniqueNumbersAndSupportListAndCustomerQueries() throws Exception {
        Customer customer = saveCustomer("Kavya Menon", "kavya.menon@example.com");

        String savingsPayload = """
                {
                  "customerId": %d,
                  "accountType": "SAVINGS",
                  "initialBalance": 1000.00
                }
                """.formatted(customer.getId());

        String currentPayload = """
                {
                  "customerId": %d,
                  "accountType": "CURRENT"
                }
                """.formatted(customer.getId());

        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(savingsPayload))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content(currentPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0.0));

        List<Account> customerAccounts = accountRepository.findByCustomerIdOrderByIdAsc(customer.getId());
        assertEquals(2, customerAccounts.size());
        assertNotEquals(customerAccounts.get(0).getAccountNumber(), customerAccounts.get(1).getAccountNumber());

        mockMvc.perform(get("/api/accounts/customer/" + customer.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountType").value("SAVINGS"))
                .andExpect(jsonPath("$[1].accountType").value("CURRENT"));

        mockMvc.perform(get("/api/accounts").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void createAccount_ShouldReturn404AndNotPersist_WhenCustomerDoesNotExist() throws Exception {
        long countBefore = accountRepository.count();

        String body = """
                {
                  "customerId": 999999,
                  "accountType": "SAVINGS",
                  "initialBalance": 500.00
                }
                """;

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Customer not found with ID: 999999"));

        assertEquals(countBefore, accountRepository.count());
    }

    @Test
    void createAccount_ShouldReturn400AndNotPersist_WhenInputIsInvalid() throws Exception {
        Customer customer = saveCustomer("Dev Patel", "dev.patel@example.com");
        long countBefore = accountRepository.count();

        String invalidBody = """
                {
                  "customerId": %d,
                  "accountType": "SAVINGS",
                  "initialBalance": -25.00
                }
                """.formatted(customer.getId());

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.initialBalance").exists());

        assertEquals(countBefore, accountRepository.count());
    }

    @Test
    void getEndpoints_ShouldReturn404_WhenAccountOrCustomerMissing() throws Exception {
        mockMvc.perform(get("/api/accounts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/accounts/number/NG9999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/accounts/customer/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private Customer saveCustomer(String fullName, String email) {
        Customer customer = new Customer();
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone("9876543210");
        return customerRepository.saveAndFlush(customer);
    }
}
