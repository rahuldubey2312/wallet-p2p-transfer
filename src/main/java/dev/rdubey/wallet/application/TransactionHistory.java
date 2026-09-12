package dev.rdubey.wallet.application;

import dev.rdubey.wallet.domain.LedgerEntry;

import java.util.List;
import java.util.UUID;

/**
 * A page of a wallet's transaction history.
 *
 * @param total every entry available, not just this page, so a caller can page
 *              without discovering the end by trial and error
 */
public record TransactionHistory(UUID walletId,
                                 int total,
                                 int limit,
                                 int offset,
                                 List<LedgerEntry> transactions)
{
}
