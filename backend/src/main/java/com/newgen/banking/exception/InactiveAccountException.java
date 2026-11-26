package com.newgen.banking.exception;

/**
 * Exception thrown when an operation is attempted on a non-ACTIVE account.
 */
public class InactiveAccountException extends RuntimeException {

    public InactiveAccountException(String message) {
        super(message);
    }
}
