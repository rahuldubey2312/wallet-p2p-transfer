package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.LedgerEntry;
import dev.rdubey.wallet.domain.Money;
import dev.rdubey.wallet.domain.TransferStatus;
import dev.rdubey.wallet.domain.port.LedgerQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transaction history derived from the transfers table.
 * <p>
 * History is a projection rather than a second stored copy: the transfer rows
 * are the single record of what happened, so a statement can never disagree
 * with the balance it explains.
 */
@Repository
public class JdbcLedgerRepository implements LedgerQueryPort
{
    private static final RowMapper<LedgerEntry> ENTRY_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getObject("id", UUID.class),
            LedgerEntry.Direction.valueOf(rs.getString("direction")),
            Money.ofPaise(rs.getLong("amount_paise")),
            rs.getObject("counterparty_wallet_id", UUID.class),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcLedgerRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @Override
    public List<LedgerEntry> historyFor(UUID walletId, int limit, int offset)
    {
        return jdbc.query("""
                          SELECT id,
                                 CASE WHEN from_wallet_id = ? THEN 'DEBIT' ELSE 'CREDIT' END AS direction,
                                 amount_paise,
                                 CASE WHEN from_wallet_id = ? THEN to_wallet_id ELSE from_wallet_id END
                                     AS counterparty_wallet_id,
                                 status,
                                 created_at
                          FROM transfers
                          WHERE from_wallet_id = ? OR to_wallet_id = ?
                          ORDER BY created_at DESC, id DESC
                          LIMIT ? OFFSET ?
                          """,
                          ENTRY_MAPPER, walletId, walletId, walletId, walletId, limit, offset);
    }

    @Override
    public int countFor(UUID walletId)
    {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE from_wallet_id = ? OR to_wallet_id = ?",
                Integer.class, walletId, walletId);
        return count == null ? 0 : count;
    }
}
