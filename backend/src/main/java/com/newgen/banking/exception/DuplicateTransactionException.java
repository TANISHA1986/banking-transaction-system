package com.newgen.banking.exception;

/**
 * Exception thrown when a transaction with a duplicate transaction ID is submitted.
 */
public class DuplicateTransactionException extends DuplicateResourceException {

    public DuplicateTransactionException(String message) {
        super(message);
    }

    public DuplicateTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
