package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.port.CredentialPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCredentialRepository implements CredentialPort
{
    private final JdbcTemplate jdbc;

    public JdbcCredentialRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @Override
    public void store(String tokenHash, UUID userId)
    {
        jdbc.update("INSERT INTO user_tokens (token_hash, user_id) VALUES (?, ?)", tokenHash, userId);
    }

    @Override
    public Optional<UUID> resolveUserId(String tokenHash)
    {
        return jdbc.queryForList("SELECT user_id FROM user_tokens WHERE token_hash = ?", UUID.class, tokenHash)
                   .stream().findFirst();
    }
}
