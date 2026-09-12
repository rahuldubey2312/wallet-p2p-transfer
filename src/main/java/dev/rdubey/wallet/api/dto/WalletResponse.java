package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.domain.Wallet;

import java.util.UUID;

/**
 * Balance is a JSON integer of paise. There is no rupee field and no decimal
 * anywhere on the wire.
 */
public record WalletResponse(@JsonProperty("id") UUID id,
                             @JsonProperty("user_id") String userId,
                             @JsonProperty("balance_paise") long balancePaise)
{
    public static WalletResponse from(Wallet wallet)
    {
        return new WalletResponse(wallet.id(), wallet.userId(), wallet.balance().paise());
    }
}
