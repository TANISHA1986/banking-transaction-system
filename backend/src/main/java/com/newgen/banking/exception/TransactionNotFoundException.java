package com.newgen.banking.exception;

/**
 * Exception thrown when a requested transaction cannot be found.
 */
public class TransactionNotFoundException extends ResourceNotFoundException {

    public TransactionNotFoundException(String message) {
        super(message);
    }
}
