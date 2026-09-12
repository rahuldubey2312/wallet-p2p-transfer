package dev.rdubey.wallet.domain;

import java.util.UUID;

/**
 * A claimed idempotency key and the transfer it produced.
 * <p>
 * The original response is not stored: it is fully derived from the transfer
 * row, so replaying by re-rendering that row is identical by construction and
 * leaves no second source of truth to drift.
 *
 * @param requestHash fingerprint of the request body the key was first used
 *                    with; a later request reusing the key with a different
 *                    fingerprint is a conflict, not a second transfer
 * @param transferId  the transfer this key produced
 */
public record IdempotencyRecord(String userId,
                                String idempotencyKey,
                                String requestHash,
                                UUID transferId)
{
    public boolean matches(String candidateRequestHash)
    {
        return requestHash.equals(candidateRequestHash);
    }

    public boolean isComplete()
    {
        return transferId != null;
    }
}
