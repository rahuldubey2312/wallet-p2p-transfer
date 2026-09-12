package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.Money;
import dev.rdubey.wallet.domain.Transfer;
import dev.rdubey.wallet.domain.TransferStatus;
import dev.rdubey.wallet.domain.port.TransferPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransferRepository implements TransferPort
{
    private static final String COLUMNS = "id, from_wallet_id, to_wallet_id, amount_paise, status, created_at";

    private static final RowMapper<Transfer> TRANSFER_MAPPER = (rs, rowNum) -> new Transfer(
            rs.getObject("id", UUID.class),
            rs.getObject("from_wallet_id", UUID.class),
            rs.getObject("to_wallet_id", UUID.class),
            Money.ofPaise(rs.getLong("amount_paise")),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcTransferRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @Override
    public Transfer insert(UUID transferId,
                           UUID fromWalletId,
                           UUID toWalletId,
                           long amountPaise,
                           TransferStatus status)
    {
        return jdbc.queryForObject("""
                                   INSERT INTO transfers (id, from_wallet_id, to_wallet_id, amount_paise, status)
                                   VALUES (?, ?, ?, ?, ?)
                                   RETURNING """ + " " + COLUMNS,
                                   TRANSFER_MAPPER,
                                   transferId, fromWalletId, toWalletId, amountPaise, status.name());
    }

    @Override
    public Optional<Transfer> findById(UUID transferId)
    {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfers WHERE id = ?", TRANSFER_MAPPER, transferId)
                   .stream().findFirst();
    }
}
