package dev.rdubey.wallet.infrastructure.persistence;

import dev.rdubey.wallet.domain.GetOrCreateResult;
import dev.rdubey.wallet.domain.Money;
import dev.rdubey.wallet.domain.Wallet;
import dev.rdubey.wallet.domain.exception.WalletNotFoundException;
import dev.rdubey.wallet.domain.port.WalletLedgerPort;
import dev.rdubey.wallet.domain.port.WalletQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWalletRepository implements WalletQueryPort, WalletLedgerPort
{
    private static final RowMapper<Wallet> WALLET_MAPPER = (rs, rowNum) -> new Wallet(
            rs.getObject("id", UUID.class),
            rs.getString("user_id"),
            Money.ofPaise(rs.getLong("balance_paise")));

    private final JdbcTemplate jdbc;

    public JdbcWalletRepository(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Wallet> findById(UUID walletId)
    {
        return jdbc.query("SELECT id, user_id, balance_paise FROM wallets WHERE id = ?",
                          WALLET_MAPPER, walletId).stream().findFirst();
    }

    @Override
    public Optional<Wallet> findByUserId(String userId)
    {
        return jdbc.query("SELECT id, user_id, balance_paise FROM wallets WHERE user_id = ?",
                          WALLET_MAPPER, userId).stream().findFirst();
    }

    @Override
    public long totalBalancePaise()
    {
        Long total = jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise), 0) FROM wallets", Long.class);
        return total == null ? 0L : total;
    }

    /**
     * The UNIQUE(user_id) constraint decides the winner when several requests
     * for a brand-new user arrive at once: one INSERT lands, the rest are
     * absorbed by ON CONFLICT DO NOTHING and then read the winner's row.
     * <p>
     * The re-read is safe under READ COMMITTED because each statement takes a
     * fresh snapshot, and ON CONFLICT already waited for the racing writer to
     * commit.
     */
    @Override
    public GetOrCreateResult getOrCreate(String userId, long openingBalancePaise)
    {
        Optional<Wallet> existing = findByUserId(userId);
        if (existing.isPresent())
        {
            return new GetOrCreateResult(existing.get(), false);
        }

        int inserted = jdbc.update("""
                                   INSERT INTO wallets (id, user_id, balance_paise)
                                   VALUES (?, ?, ?)
                                   ON CONFLICT (user_id) DO NOTHING
                                   """,
                                   UUID.randomUUID(), userId, openingBalancePaise);

        Wallet wallet = findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("wallet missing after upsert for user " + userId));
        return new GetOrCreateResult(wallet, inserted == 1);
    }

    @Override
    public void lockForUpdate(UUID walletId)
    {
        boolean locked = !jdbc.queryForList("SELECT id FROM wallets WHERE id = ? FOR UPDATE", UUID.class, walletId)
                              .isEmpty();
        if (!locked)
        {
            throw new WalletNotFoundException(walletId);
        }
    }

    @Override
    public boolean debitIfSufficient(UUID walletId, long amountPaise)
    {
        int rows = jdbc.update("""
                               UPDATE wallets
                               SET balance_paise = balance_paise - ?
                               WHERE id = ? AND balance_paise >= ?
                               """,
                               amountPaise, walletId, amountPaise);
        return rows == 1;
    }

    @Override
    public void credit(UUID walletId, long amountPaise)
    {
        int rows = jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?",
                               amountPaise, walletId);
        if (rows != 1)
        {
            throw new WalletNotFoundException(walletId);
        }
    }
}
