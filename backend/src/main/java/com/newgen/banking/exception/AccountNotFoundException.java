package com.newgen.banking.exception;

/**
 * Exception thrown when a requested bank account cannot be found.
 */
public class AccountNotFoundException extends ResourceNotFoundException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
