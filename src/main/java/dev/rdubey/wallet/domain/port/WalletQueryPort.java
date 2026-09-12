package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.Wallet;

import java.util.Optional;
import java.util.UUID;

/**
 * Read side of wallet storage. Kept separate from {@link WalletLedgerPort} so
 * that balance lookups do not drag in the locking and mutation vocabulary of
 * the money path.
 */
public interface WalletQueryPort
{
    Optional<Wallet> findById(UUID walletId);

    Optional<Wallet> findByOwnerId(UUID ownerId);

    /**
     * Sum of every wallet balance. Used only by tests and diagnostics to
     * assert the conservation invariant.
     */
    long totalBalancePaise();
}
