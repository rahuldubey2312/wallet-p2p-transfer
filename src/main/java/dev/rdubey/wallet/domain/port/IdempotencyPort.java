package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.IdempotencyRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * Storage for idempotency claims. Implementations must enforce uniqueness in
 * the database, not in application code, so that concurrent duplicates cannot
 * both pass a check before either writes.
 */
public interface IdempotencyPort
{
    /**
     * Claims the key for this caller. Must be called inside the same
     * transaction as the debit and credit.
     *
     * @throws org.springframework.dao.DuplicateKeyException if the key is
     *                                                       already claimed
     */
    void claim(String userId, String idempotencyKey, String requestHash);

    /**
     * Links the claimed key to the transfer it produced, in the same
     * transaction as the claim.
     */
    void complete(String userId, String idempotencyKey, UUID transferId);

    Optional<IdempotencyRecord> find(String userId, String idempotencyKey);
}
