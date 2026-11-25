package com.newgen.banking.service;

import com.newgen.banking.dto.CustomerRequest;
import com.newgen.banking.dto.CustomerResponse;
import com.newgen.banking.dto.PagedResponse;
import com.newgen.banking.entity.Customer;
import com.newgen.banking.exception.DuplicateResourceException;
import com.newgen.banking.exception.ResourceNotFoundException;
import com.newgen.banking.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Service layer for customer registration and lookup business rules.
 */
@Service
public class CustomerService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "fullName", "email", "createdAt");

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public CustomerResponse createCustomer(CustomerRequest request) {
        String normalizedName = request.fullName().trim();
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        String normalizedPhone = request.phone() != null ? request.phone().trim() : null;

        if (customerRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new DuplicateResourceException(
                    "Customer with email '" + normalizedEmail + "' already exists"
            );
        }

        Customer customer = new Customer();
        customer.setFullName(normalizedName);
        customer.setEmail(normalizedEmail);
        customer.setPhone(normalizedPhone);

        Customer savedCustomer = customerRepository.save(customer);
        return CustomerResponse.from(savedCustomer);
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> getAllCustomers() {
        return customerRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream()
                .map(CustomerResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PagedResponse<CustomerResponse> getCustomers(int page, int size, String sortBy, String sortDir) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must be 0 or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (!ALLOWED_SORT_FIELDS.contains(sortBy)) {
            throw new IllegalArgumentException("Invalid sort field: " + sortBy);
        }

        Sort.Direction direction = "desc".equalsIgnoreCase(sortDir)
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));
        Page<CustomerResponse> responsePage = customerRepository.findAll(pageable)
                .map(CustomerResponse::from);
        return PagedResponse.from(responsePage);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getCustomerById(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Customer ID must be a positive number");
        }
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer not found with ID: " + id
                ));
        return CustomerResponse.from(customer);
    }
}
