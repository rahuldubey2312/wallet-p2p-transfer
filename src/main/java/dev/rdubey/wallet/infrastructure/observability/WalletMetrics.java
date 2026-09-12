package dev.rdubey.wallet.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters exposed alongside the standard HTTP metrics.
 * <p>
 * Micrometer renders these at {@code /metrics} as, for example,
 * {@code wallet_transfers_created_total}.
 */
@Component
public class WalletMetrics
{
    private final Counter walletsCreated;
    private final Counter transfersCreated;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;

    public WalletMetrics(MeterRegistry registry)
    {
        // Deliberately not named ".created": OpenMetrics reserves a _created
        // suffix for series timestamps, and the exporter silently strips it,
        // which would publish this as the ambiguous wallet_transfers_total.
        this.walletsCreated = Counter.builder("wallet.wallets.opened")
                                     .description("Wallets actually created, excluding get-or-create hits")
                                     .register(registry);
        this.transfersCreated = Counter.builder("wallet.transfers.completed")
                                       .description("Transfers that debited and credited successfully")
                                       .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("wallet.transfers.declined")
                                                         .tag("reason", "insufficient_funds")
                                                         .description("Transfers declined because the source lacked funds")
                                                         .register(registry);
        this.idempotentReplays = Counter.builder("wallet.idempotent.replays")
                                        .description("Retries of a known idempotency key served from the original result")
                                        .register(registry);
        this.idempotencyConflicts = Counter.builder("wallet.idempotency.conflicts")
                                           .description("Idempotency keys reused with a different request body")
                                           .register(registry);
    }

    public void walletCreated()
    {
        walletsCreated.increment();
    }

    public void transferCreated()
    {
        transfersCreated.increment();
    }

    public void transferDeclinedInsufficientFunds()
    {
        transfersDeclinedInsufficientFunds.increment();
    }

    public void idempotentReplay()
    {
        idempotentReplays.increment();
    }

    public void idempotencyConflict()
    {
        idempotencyConflicts.increment();
    }
}
