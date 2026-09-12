package dev.rdubey.wallet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param openingBalancePaise balance a wallet is created with. Wallets are
 *                            seeded so that a reviewer can transfer
 *                            immediately without a funding endpoint; creating
 *                            a wallet is not a transfer, so this does not
 *                            affect the conservation invariant, which is
 *                            asserted across transfers.
 */
@ConfigurationProperties(prefix = "wallet")
public record WalletProperties(@DefaultValue("1000000") long openingBalancePaise)
{
}
