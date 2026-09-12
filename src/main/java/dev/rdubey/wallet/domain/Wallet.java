package dev.rdubey.wallet.domain;

import java.util.UUID;

/**
 * A user's wallet. Exactly one wallet exists per user, enforced by a UNIQUE
 * constraint on the user id rather than by application-side checking.
 */
public record Wallet(UUID id, String userId, Money balance)
{
    public boolean isOwnedBy(String candidateUserId)
    {
        return userId.equals(candidateUserId);
    }
}
