package com.newgen.banking.exception;

/**
 * Exception thrown when an account has insufficient balance for a debit operation.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
