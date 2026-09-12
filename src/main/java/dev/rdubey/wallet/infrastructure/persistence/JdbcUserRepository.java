package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.User;
import dev.rdubey.wallet.domain.port.UserPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository implements UserPort
{
    private static final String COLUMNS = "id, display_name, email, phone, created_at";

    private static final RowMapper<User> USER_MAPPER = (rs, rowNum) -> new User(
            rs.getObject("id", UUID.class),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    /**
     * A duplicate email surfaces as a unique violation from the database
     * rather than a prior existence check, so two concurrent registrations of
     * the same address cannot both succeed.
     */
    @Override
    public User insert(UUID id, String displayName, String email, String phone)
    {
        return jdbc.queryForObject("""
                                   INSERT INTO users (id, display_name, email, phone)
                                   VALUES (?, ?, ?, ?)
                                   RETURNING """ + " " + COLUMNS,
                                   USER_MAPPER, id, displayName, email, phone);
    }

    @Override
    public Optional<User> findById(UUID userId)
    {
        return jdbc.query("SELECT " + COLUMNS + " FROM users WHERE id = ?", USER_MAPPER, userId)
                   .stream().findFirst();
    }
}
