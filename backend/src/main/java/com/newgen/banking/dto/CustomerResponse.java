package com.newgen.banking.dto;

import com.newgen.banking.entity.Customer;

import java.time.LocalDateTime;

/**
 * API response representation of a customer record.
 */
public record CustomerResponse(
        Long id,
        String fullName,
        String email,
        String phone,
        LocalDateTime createdAt
) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail(),
                customer.getPhone(),
                customer.getCreatedAt()
        );
    }
}
