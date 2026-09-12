package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.LedgerEntry;

import java.util.List;
import java.util.UUID;

public interface LedgerQueryPort
{
    /**
     * Transaction history for one wallet, newest first, with each entry's
     * direction expressed relative to that wallet.
     */
    List<LedgerEntry> historyFor(UUID walletId, int limit, int offset);

    int countFor(UUID walletId);
}
