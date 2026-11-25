package com.newgen.banking.controller;

import com.newgen.banking.dto.CustomerRequest;
import com.newgen.banking.dto.CustomerResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.exception.DuplicateResourceException;
import com.newgen.banking.exception.GlobalExceptionHandler;
import com.newgen.banking.exception.ResourceNotFoundException;
import com.newgen.banking.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

@WebMvcTest(CustomerController.class)
@Import(GlobalExceptionHandler.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void createCustomer_ShouldReturn201_WhenInputIsValid() throws Exception {
        CustomerResponse response = new CustomerResponse(
                1L,
                "Aarav Sharma",
                "aarav@example.com",
                "9876543210",
                LocalDateTime.of(2026, 11, 25, 13, 0)
        );
        when(customerService.createCustomer(any(CustomerRequest.class))).thenReturn(response);

        String body = """
                {
                  "fullName": "Aarav Sharma",
                  "email": "aarav@example.com",
                  "phone": "9876543210"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.fullName").value("Aarav Sharma"))
                .andExpect(jsonPath("$.email").value("aarav@example.com"))
                .andExpect(jsonPath("$.phone").value("9876543210"));
    }

    @Test
    void createCustomer_ShouldReturn400WithFieldErrors_WhenInputIsInvalid() throws Exception {
        String invalidBody = """
                {
                  "fullName": "",
                  "email": "not-an-email",
                  "phone": "123"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.validationErrors.fullName").exists())
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.phone").exists());

        verify(customerService, never()).createCustomer(any());
    }

    @Test
    void createCustomer_ShouldReturn409_WhenEmailAlreadyExists() throws Exception {
        when(customerService.createCustomer(any(CustomerRequest.class)))
                .thenThrow(new DuplicateResourceException("Customer with email 'dup@example.com' already exists"));

        String body = """
                {
                  "fullName": "Duplicate User",
                  "email": "dup@example.com",
                  "phone": "9876543210"
                }
                """;

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Customer with email 'dup@example.com' already exists"));
    }

    @Test
    void getAllCustomers_ShouldReturn200WithCustomerList() throws Exception {
        List<CustomerResponse> responses = List.of(
                new CustomerResponse(1L, "Aarav Sharma", "aarav@example.com", "9876543210", LocalDateTime.now())
        );
        when(customerService.getAllCustomers()).thenReturn(responses);

        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].fullName").value("Aarav Sharma"));
    }

    @Test
    void getCustomersPaged_ShouldReturn200WithPagedResponse() throws Exception {
        PagedResponse<CustomerResponse> paged = new PagedResponse<>(
                List.of(new CustomerResponse(1L, "Aarav Sharma", "aarav@example.com", "9876543210", LocalDateTime.now())),
                0,
                20,
                1,
                1,
                true,
                true
        );
        when(customerService.getCustomers(0, 20, "id", "asc")).thenReturn(paged);

        mockMvc.perform(get("/api/customers/paged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value("aarav@example.com"));
    }

    @Test
    void getCustomerById_ShouldReturn200_WhenFound() throws Exception {
        CustomerResponse response = new CustomerResponse(
                10L,
                "Priya Nair",
                "priya@example.com",
                "9123456789",
                LocalDateTime.now()
        );
        when(customerService.getCustomerById(10L)).thenReturn(response);

        mockMvc.perform(get("/api/customers/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.fullName").value("Priya Nair"));
    }

    @Test
    void getCustomerById_ShouldReturn404_WhenNotFound() throws Exception {
        when(customerService.getCustomerById(999L))
                .thenThrow(new ResourceNotFoundException("Customer not found with ID: 999"));

        mockMvc.perform(get("/api/customers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Customer not found with ID: 999"));
    }

    @Test
    void getCustomerById_ShouldReturn400_WhenIdTypeIsInvalid() throws Exception {
        mockMvc.perform(get("/api/customers/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
