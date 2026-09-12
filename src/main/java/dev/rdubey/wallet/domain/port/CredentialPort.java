package dev.rdubey.wallet.domain.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage for issued credentials, keyed by the hash of the token rather than
 * the token itself.
 */
public interface CredentialPort
{
    void store(String tokenHash, UUID userId);

    /**
     * Resolves a presented token's hash to its owner, or empty if the token
     * was never issued or has been revoked.
     */
    Optional<UUID> resolveUserId(String tokenHash);
}
