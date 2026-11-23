package com.newgen.banking.controller;

import com.newgen.banking.entity.Customer;
import com.newgen.banking.service.CustomerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    public record CustomerRequest(

            @NotBlank(message = "Name is required")
            @Size(max = 100, message = "Name cannot exceed 100 characters")
            String fullName,

            @NotBlank(message = "Email is required")
            @Email(message = "Enter a valid email")
            @Size(max = 150)
            String email,

            @Pattern(regexp = "^[0-9]{10,15}$",
                    message = "Phone must contain 10 to 15 digits")
            String phone
    ) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Customer createCustomer(@Valid @RequestBody CustomerRequest request) {
        return customerService.createCustomer(
                request.fullName(),
                request.email(),
                request.phone()
        );
    }

    @GetMapping
    public List<Customer> getAllCustomers() {
        return customerService.getAllCustomers();
    }

    @GetMapping("/{id}")
    public Customer getCustomerById(@PathVariable Long id) {
        return customerService.getCustomerById(id);
    }
}