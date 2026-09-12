package dev.rdubey.wallet.domain;

import java.util.UUID;

/**
 * A user's wallet. Exactly one wallet exists per user, enforced by a UNIQUE
 * constraint on the owner rather than by application-side checking.
 */
public record Wallet(UUID id, UUID ownerId, Money balance)
{
    public boolean isOwnedBy(UUID candidateOwnerId)
    {
        return ownerId.equals(candidateOwnerId);
    }
}
