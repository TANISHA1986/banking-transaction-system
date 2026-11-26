package com.newgen.banking.exception;

/**
 * Exception thrown when a referenced customer cannot be found.
 */
public class CustomerNotFoundException extends ResourceNotFoundException {

    public CustomerNotFoundException(String message) {
        super(message);
    }
}
