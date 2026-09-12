package dev.rdubey.wallet.domain.exception;

public class InvalidTransferException extends RuntimeException
{
    public InvalidTransferException(String message)
    {
        super(message);
    }
}
