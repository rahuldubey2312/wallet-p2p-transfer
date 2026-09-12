package dev.rdubey.wallet.domain.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException
{
    public TransferNotFoundException(UUID transferId)
    {
        super("transfer not found: " + transferId);
    }
}
