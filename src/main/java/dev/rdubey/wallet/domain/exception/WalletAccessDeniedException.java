package dev.rdubey.wallet.domain.exception;

import java.util.UUID;

/**
 * Raised when a caller tries to debit a wallet that is not theirs. Without
 * this check any valid token could drain any wallet.
 */
public class WalletAccessDeniedException extends RuntimeException
{
    public WalletAccessDeniedException(UUID walletId)
    {
        super("caller does not own wallet: " + walletId);
    }
}
