package com.newgen.banking.controller;

import com.newgen.banking.entity.Customer;
import com.newgen.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests verifying HTTP -> Controller -> Service -> Repository -> PostgreSQL (banking_test_db).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CustomerControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void createAndRetrieveCustomer_ShouldPersistInDatabase() throws Exception {
        String body = """
                {
                  "fullName": "  Meera Krishnan  ",
                  "email": "Meera.Krishnan@Example.com",
                  "phone": "9876501234"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.fullName").value("Meera Krishnan"))
                .andExpect(jsonPath("$.email").value("meera.krishnan@example.com"))
                .andExpect(jsonPath("$.phone").value("9876501234"));

        Customer persisted = customerRepository.findByEmail("meera.krishnan@example.com")
                .orElseThrow();

        mockMvc.perform(get("/api/customers/" + persisted.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.fullName").value("Meera Krishnan"))
                .andExpect(jsonPath("$.email").value("meera.krishnan@example.com"));
    }

    @Test
    void createCustomer_ShouldReturn409_WhenEmailAlreadyExists() throws Exception {
        Customer existing = new Customer();
        existing.setFullName("Existing User");
        existing.setEmail("duplicate.test@example.com");
        existing.setPhone("9000000001");
        customerRepository.saveAndFlush(existing);

        long countBefore = customerRepository.count();

        String duplicateBody = """
                {
                  "fullName": "Another User",
                  "email": "DUPLICATE.TEST@example.com",
                  "phone": "9000000002"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Customer with email 'duplicate.test@example.com' already exists"));

        assertEquals(countBefore, customerRepository.count());
    }

    @Test
    void repositorySave_ShouldEnforceDatabaseUniqueEmailConstraint() {
        Customer first = new Customer();
        first.setFullName("First User");
        first.setEmail("unique.constraint@example.com");
        customerRepository.saveAndFlush(first);

        Customer second = new Customer();
        second.setFullName("Second User");
        second.setEmail("unique.constraint@example.com");

        assertThrows(DataIntegrityViolationException.class, () -> customerRepository.saveAndFlush(second));
    }

    @Test
    void getCustomersPaged_ShouldReturnPaginatedResultsFromDatabase() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Customer c = new Customer();
            c.setFullName("Customer " + i);
            c.setEmail("paged" + i + "@example.com");
            customerRepository.save(c);
        }
        customerRepository.flush();

        mockMvc.perform(get("/api/customers/paged")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sortBy", "id")
                        .param("sortDir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value(2));

        assertTrue(customerRepository.count() >= 3);
    }

    @Test
    void getCustomerById_ShouldReturn404_WhenMissingInDatabase() throws Exception {
        mockMvc.perform(get("/api/customers/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Customer not found with ID: 999999"));
    }

    @Test
    void createCustomer_ShouldReturn400AndNotPersist_WhenInputIsInvalid() throws Exception {
        long countBefore = customerRepository.count();

        String invalidBody = """
                {
                  "fullName": "",
                  "email": "invalid-email",
                  "phone": "12"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.validationErrors.fullName").exists())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.phone").exists());

        assertEquals(countBefore, customerRepository.count());
    }
}
