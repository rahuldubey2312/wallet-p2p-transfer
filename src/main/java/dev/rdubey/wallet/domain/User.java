package dev.rdubey.wallet.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who owns a wallet.
 *
 * @param email optional, but unique across users when present
 */
public record User(UUID id,
                   String displayName,
                   String email,
                   String phone,
                   Instant createdAt)
{
}
