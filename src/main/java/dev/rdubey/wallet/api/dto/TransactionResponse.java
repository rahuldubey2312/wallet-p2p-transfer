package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.domain.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * One transaction as seen from the caller's own wallet, which is what makes
 * {@code direction} meaningful.
 */
public record TransactionResponse(@JsonProperty("transfer_id") UUID transferId,
                                  @JsonProperty("direction") String direction,
                                  @JsonProperty("amount_paise") long amountPaise,
                                  @JsonProperty("counterparty_wallet_id") UUID counterpartyWalletId,
                                  @JsonProperty("status") String status,
                                  @JsonProperty("created_at") Instant createdAt)
{
    public static TransactionResponse from(LedgerEntry entry)
    {
        return new TransactionResponse(entry.transferId(),
                                       entry.direction().name(),
                                       entry.amount().paise(),
                                       entry.counterpartyWalletId(),
                                       entry.status().name(),
                                       entry.createdAt());
    }
}
