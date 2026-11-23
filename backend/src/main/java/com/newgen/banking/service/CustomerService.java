package com.newgen.banking.service;

import com.newgen.banking.entity.Customer;
import com.newgen.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Transactional
    public Customer createCustomer(String fullName, String email, String phone) {

        if (customerRepository.existsByEmail(email)) {
            throw new IllegalArgumentException(
                    "Customer with this email already exists"
            );
        }

        Customer customer = new Customer();
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone(phone);

        return customerRepository.save(customer);
    }

    @Transactional(readOnly = true)
    public List<Customer> getAllCustomers() {
        return customerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Customer getCustomerById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Customer not found with ID: " + id
                ));
    }
}