package dev.rdubey.wallet.domain.exception;

import java.util.UUID;

public class WalletNotFoundException extends RuntimeException
{
    public WalletNotFoundException(UUID walletId)
    {
        super("wallet not found: " + walletId);
    }
}
