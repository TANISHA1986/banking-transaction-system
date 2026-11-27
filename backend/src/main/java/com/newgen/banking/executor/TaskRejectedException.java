package com.newgen.banking.executor;

/**
 * Thrown when a transaction task cannot be accepted because both the
 * thread pool and its bounded queue are full (backpressure signal).
 *
 * <p>Callers should catch this exception and record the submission as
 * REJECTED in the batch result rather than propagating it as a 500 error.</p>
 */
public class TaskRejectedException extends RuntimeException {

    public TaskRejectedException(String message) {
        super(message);
    }

    public TaskRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
