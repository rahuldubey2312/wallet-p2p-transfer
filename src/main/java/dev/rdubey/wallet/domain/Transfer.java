package dev.rdubey.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A single transfer attempt between two wallets, successful or declined.
 */
public record Transfer(UUID id,
                       UUID fromWalletId,
                       UUID toWalletId,
                       Money amount,
                       TransferStatus status,
                       Instant createdAt)
{
    public boolean isCompleted()
    {
        return status == TransferStatus.COMPLETED;
    }
}
