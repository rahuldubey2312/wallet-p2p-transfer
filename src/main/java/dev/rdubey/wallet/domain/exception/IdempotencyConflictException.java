package dev.rdubey.wallet.domain.exception;

/**
 * Raised when an idempotency key is reused with a different request body.
 * This is a conflict rather than a second transfer.
 */
public class IdempotencyConflictException extends RuntimeException
{
    public IdempotencyConflictException(String idempotencyKey)
    {
        super("idempotency key reused with a different request body: " + idempotencyKey);
    }
}
