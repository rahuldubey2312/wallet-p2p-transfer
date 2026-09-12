package dev.rdubey.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a wallet's transaction history: a transfer seen from the point
 * of view of a single wallet, which is what makes the direction meaningful.
 */
public record LedgerEntry(UUID transferId,
                          Direction direction,
                          Money amount,
                          UUID counterpartyWalletId,
                          TransferStatus status,
                          Instant createdAt)
{
    public enum Direction
    {
        /** Money left this wallet. */
        DEBIT,

        /** Money arrived in this wallet. */
        CREDIT
    }
}
