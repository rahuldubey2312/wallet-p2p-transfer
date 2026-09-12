package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.IdempotencyRecord;
import dev.rdubey.wallet.domain.port.IdempotencyPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyRepository implements IdempotencyPort
{
    private static final RowMapper<IdempotencyRecord> RECORD_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getObject("transfer_id", UUID.class));

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * A plain INSERT, deliberately not an upsert: the primary key violation is
     * the signal that another request already owns this key. Postgres blocks
     * the second inserter until the first transaction resolves, so the loser
     * only sees a conflict once the winner has actually committed.
     */
    @Override
    public void claim(String userId, String idempotencyKey, String requestHash)
    {
        jdbc.update("INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?, ?, ?)",
                    userId, idempotencyKey, requestHash);
    }

    @Override
    public void complete(String userId, String idempotencyKey, UUID transferId)
    {
        jdbc.update("""
                    UPDATE idempotency_keys
                    SET transfer_id = ?
                    WHERE user_id = ? AND idempotency_key = ?
                    """,
                    transferId, userId, idempotencyKey);
    }

    @Override
    public Optional<IdempotencyRecord> find(String userId, String idempotencyKey)
    {
        return jdbc.query("""
                          SELECT user_id, idempotency_key, request_hash, transfer_id
                          FROM idempotency_keys
                          WHERE user_id = ? AND idempotency_key = ?
                          """,
                          RECORD_MAPPER, userId, idempotencyKey).stream().findFirst();
    }
}
