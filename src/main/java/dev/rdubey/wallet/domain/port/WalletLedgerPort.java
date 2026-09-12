package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.GetOrCreateResult;

import java.util.UUID;

/**
 * Write side of wallet storage: the operations that move money.
 * <p>
 * Every method here is expected to run inside a caller-managed transaction.
 */
public interface WalletLedgerPort
{
    /**
     * Returns the owner's wallet, creating it only if absent. Concurrent calls
     * for the same owner must converge on a single wallet.
     */
    GetOrCreateResult getOrCreate(UUID ownerId, long openingBalancePaise);

    /**
     * Takes a row lock on the wallet. Callers must invoke this for the wallets
     * involved in a transfer in ascending id order, which is what prevents a
     * deadlock between an A-to-B and a B-to-A transfer running concurrently.
     */
    void lockForUpdate(UUID walletId);

    /**
     * Conditional debit. Deducts only if the balance covers the amount, and
     * reports whether it did, so no read-modify-write happens in application
     * code.
     *
     * @return true if the wallet was debited, false if funds were insufficient
     */
    boolean debitIfSufficient(UUID walletId, long amountPaise);

    void credit(UUID walletId, long amountPaise);
}
