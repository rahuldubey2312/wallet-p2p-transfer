package dev.rdubey.wallet.application;

import dev.rdubey.wallet.domain.Transfer;

/**
 * @param idempotentReplay true when this result was served from an earlier
 *                         request that used the same idempotency key
 */
public record TransferOutcome(Transfer transfer, boolean idempotentReplay)
{
}
