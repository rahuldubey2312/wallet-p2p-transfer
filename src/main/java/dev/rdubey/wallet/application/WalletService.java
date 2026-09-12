package dev.rdubey.wallet.application;

import dev.rdubey.wallet.config.WalletProperties;
import dev.rdubey.wallet.domain.GetOrCreateResult;
import dev.rdubey.wallet.domain.Wallet;
import dev.rdubey.wallet.domain.exception.WalletNotFoundException;
import dev.rdubey.wallet.domain.port.WalletLedgerPort;
import dev.rdubey.wallet.domain.port.WalletQueryPort;
import dev.rdubey.wallet.infrastructure.observability.DomainEvents;
import dev.rdubey.wallet.infrastructure.observability.WalletMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class WalletService
{
    private final WalletQueryPort wallets;
    private final WalletLedgerPort ledger;
    private final WalletProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final WalletMetrics metrics;
    private final DomainEvents events;

    public WalletService(WalletQueryPort wallets,
                         WalletLedgerPort ledger,
                         WalletProperties properties,
                         TransactionTemplate transactionTemplate,
                         WalletMetrics metrics,
                         DomainEvents events)
    {
        this.wallets = wallets;
        this.ledger = ledger;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.events = events;
    }

    /**
     * Returns the caller's wallet, creating it on first call. Concurrent
     * callers for a brand-new user all receive the same wallet: the database
     * decides the winner, so no double creation is possible.
     */
    public Wallet getOrCreate(String userId)
    {
        GetOrCreateResult result = transactionTemplate.execute(
                status -> ledger.getOrCreate(userId, properties.openingBalancePaise()));

        if (result == null)
        {
            throw new IllegalStateException("get-or-create returned no result for user " + userId);
        }

        if (result.created())
        {
            metrics.walletCreated();
            events.walletCreated(result.wallet().id(), userId, result.wallet().balance().paise());
        }
        return result.wallet();
    }

    public Wallet getById(UUID walletId)
    {
        return wallets.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
