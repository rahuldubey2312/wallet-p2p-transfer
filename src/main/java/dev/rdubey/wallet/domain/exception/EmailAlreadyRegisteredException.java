package dev.rdubey.wallet.domain.exception;

/**
 * Raised when a registration reuses an email already on file. Detected by the
 * database's unique constraint rather than a prior lookup, so two concurrent
 * registrations cannot both pass the check.
 */
public class EmailAlreadyRegisteredException extends RuntimeException
{
    public EmailAlreadyRegisteredException()
    {
        super("that email address is already registered");
    }
}
