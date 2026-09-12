package dev.rdubey.wallet.application;

import dev.rdubey.wallet.domain.LedgerEntry;
import dev.rdubey.wallet.domain.User;
import dev.rdubey.wallet.domain.Wallet;

import java.util.List;

/**
 * A user together with the wallet they own and their latest activity.
 *
 * @param wallet null until the user creates one via POST /wallets
 */
public record UserProfile(User user, Wallet wallet, List<LedgerEntry> recentTransactions)
{
}
