package dev.rdubey.wallet.application;

import dev.rdubey.wallet.domain.IssuedCredential;
import dev.rdubey.wallet.domain.LedgerEntry;
import dev.rdubey.wallet.domain.User;
import dev.rdubey.wallet.domain.Wallet;
import dev.rdubey.wallet.domain.exception.EmailAlreadyRegisteredException;
import dev.rdubey.wallet.domain.exception.UserNotFoundException;
import dev.rdubey.wallet.domain.port.CredentialPort;
import dev.rdubey.wallet.domain.port.LedgerQueryPort;
import dev.rdubey.wallet.domain.port.UserPort;
import dev.rdubey.wallet.domain.port.WalletQueryPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class UserService
{
    private static final int RECENT_TRANSACTION_COUNT = 10;
    private static final int MAX_HISTORY_PAGE = 100;

    private final UserPort users;
    private final CredentialPort credentials;
    private final WalletQueryPort wallets;
    private final LedgerQueryPort ledger;
    private final AccessTokens accessTokens;
    private final TransactionTemplate transactionTemplate;

    public UserService(UserPort users,
                       CredentialPort credentials,
                       WalletQueryPort wallets,
                       LedgerQueryPort ledger,
                       AccessTokens accessTokens,
                       TransactionTemplate transactionTemplate)
    {
        this.users = users;
        this.credentials = credentials;
        this.wallets = wallets;
        this.ledger = ledger;
        this.accessTokens = accessTokens;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Creates a user and issues the token that authenticates it.
     * <p>
     * Both writes share one transaction, so a user can never exist without a
     * way to reach it, nor a credential without an owner. The token itself is
     * returned here and nowhere else; storage keeps only its hash.
     */
    public IssuedCredential register(String displayName, String email, String phone)
    {
        String token = accessTokens.generate();
        String tokenHash = accessTokens.hash(token);

        try
        {
            User created = transactionTemplate.execute(status ->
                                                       {
                                                           User user = users.insert(UUID.randomUUID(),
                                                                                    displayName, email, phone);
                                                           credentials.store(tokenHash, user.id());
                                                           return user;
                                                       });
            if (created == null)
            {
                throw new IllegalStateException("registration produced no user");
            }
            return new IssuedCredential(created, token);
        }
        catch (DuplicateKeyException e)
        {
            // The only unique constraint a caller can collide with is the email.
            throw new EmailAlreadyRegisteredException();
        }
    }

    public UserProfile profileOf(UUID userId)
    {
        User user = users.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        Wallet wallet = wallets.findByOwnerId(userId).orElse(null);

        List<LedgerEntry> recent = wallet == null
                ? List.of()
                : ledger.historyFor(wallet.id(), RECENT_TRANSACTION_COUNT, 0);

        return new UserProfile(user, wallet, recent);
    }

    /**
     * Transaction history for the caller's wallet. A user without a wallet has
     * an empty history rather than an error: nothing has happened yet.
     */
    public TransactionHistory historyOf(UUID userId, int limit, int offset)
    {
        int boundedLimit = Math.clamp(limit, 1, MAX_HISTORY_PAGE);
        int boundedOffset = Math.max(offset, 0);

        Wallet wallet = wallets.findByOwnerId(userId).orElse(null);
        if (wallet == null)
        {
            return new TransactionHistory(null, 0, boundedLimit, boundedOffset, List.of());
        }

        return new TransactionHistory(wallet.id(),
                                      ledger.countFor(wallet.id()),
                                      boundedLimit,
                                      boundedOffset,
                                      ledger.historyFor(wallet.id(), boundedLimit, boundedOffset));
    }
}
