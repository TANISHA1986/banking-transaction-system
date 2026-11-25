package com.newgen.banking.service;

import com.newgen.banking.dto.CustomerRequest;
import com.newgen.banking.dto.CustomerResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.exception.DuplicateResourceException;
import com.newgen.banking.exception.ResourceNotFoundException;
import com.newgen.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomerService customerService;

    @Test
    void createCustomer_ShouldNormalizeAndSaveCustomer_WhenEmailIsUnique() {
        CustomerRequest request = new CustomerRequest("  Aarav Sharma  ", "  Aarav.Sharma@Example.COM ", "9876543210");
        when(customerRepository.existsByEmailIgnoreCase("aarav.sharma@example.com")).thenReturn(false);

        Customer saved = buildCustomer(1L, "Aarav Sharma", "aarav.sharma@example.com", "9876543210");
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);

        CustomerResponse response = customerService.createCustomer(request);

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        Customer persisted = captor.getValue();

        assertEquals("Aarav Sharma", persisted.getFullName());
        assertEquals("aarav.sharma@example.com", persisted.getEmail());
        assertEquals("9876543210", persisted.getPhone());

        assertEquals(1L, response.id());
        assertEquals("Aarav Sharma", response.fullName());
        assertEquals("aarav.sharma@example.com", response.email());
        assertNotNull(response.createdAt());
    }

    @Test
    void createCustomer_ShouldThrowDuplicateResourceException_WhenEmailAlreadyExists() {
        CustomerRequest request = new CustomerRequest("Priya Nair", "priya@example.com", "9123456789");
        when(customerRepository.existsByEmailIgnoreCase("priya@example.com")).thenReturn(true);

        DuplicateResourceException ex = assertThrows(
                DuplicateResourceException.class,
                () -> customerService.createCustomer(request)
        );

        assertTrue(ex.getMessage().contains("priya@example.com"));
        verify(customerRepository, never()).save(any());
    }

    @Test
    void getCustomerById_ShouldReturnCustomerResponse_WhenCustomerExists() {
        Customer existing = buildCustomer(5L, "Rohan Verma", "rohan@example.com", "9988776655");
        when(customerRepository.findById(5L)).thenReturn(Optional.of(existing));

        CustomerResponse response = customerService.getCustomerById(5L);

        assertEquals(5L, response.id());
        assertEquals("Rohan Verma", response.fullName());
        assertEquals("rohan@example.com", response.email());
        assertEquals("9988776655", response.phone());
    }

    @Test
    void getCustomerById_ShouldThrowResourceNotFoundException_WhenCustomerDoesNotExist() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(
                ResourceNotFoundException.class,
                () -> customerService.getCustomerById(99L)
        );

        assertEquals("Customer not found with ID: 99", ex.getMessage());
    }

    @Test
    void getCustomerById_ShouldThrowIllegalArgumentException_WhenIdIsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> customerService.getCustomerById(0L));
        verify(customerRepository, never()).findById(any());
    }

    @Test
    void getAllCustomers_ShouldReturnMappedResponses() {
        List<Customer> customers = List.of(
                buildCustomer(1L, "Aarav Sharma", "aarav@example.com", "9876543210"),
                buildCustomer(2L, "Priya Nair", "priya@example.com", null)
        );
        when(customerRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))).thenReturn(customers);

        List<CustomerResponse> responses = customerService.getAllCustomers();

        assertEquals(2, responses.size());
        assertEquals("Aarav Sharma", responses.get(0).fullName());
        assertEquals("Priya Nair", responses.get(1).fullName());
    }

    @Test
    void getCustomers_ShouldReturnPagedResponse() {
        Customer customer = buildCustomer(1L, "Aarav Sharma", "aarav@example.com", "9876543210");
        when(customerRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(customer)));

        PagedResponse<CustomerResponse> page = customerService.getCustomers(0, 10, "id", "asc");

        assertEquals(1, page.content().size());
        assertEquals(0, page.page());
        assertEquals(1, page.totalElements());
        assertTrue(page.first());
        assertTrue(page.last());
    }

    @Test
    void getCustomers_ShouldRejectInvalidPaginationOrSortParameters() {
        assertThrows(IllegalArgumentException.class, () -> customerService.getCustomers(-1, 10, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> customerService.getCustomers(0, 0, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> customerService.getCustomers(0, 101, "id", "asc"));
        assertThrows(IllegalArgumentException.class, () -> customerService.getCustomers(0, 10, "password", "asc"));
    }

    private static Customer buildCustomer(Long id, String fullName, String email, String phone) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone(phone);
        customer.setCreatedAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        return customer;
    }
}
