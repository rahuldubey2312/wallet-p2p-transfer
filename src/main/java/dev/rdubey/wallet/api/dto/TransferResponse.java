package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.domain.Transfer;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(@JsonProperty("id") UUID id,
                               @JsonProperty("from") UUID from,
                               @JsonProperty("to") UUID to,
                               @JsonProperty("amount_paise") long amountPaise,
                               @JsonProperty("status") String status,
                               @JsonProperty("created_at") Instant createdAt)
{
    public static TransferResponse from(Transfer transfer)
    {
        return new TransferResponse(transfer.id(),
                                    transfer.fromWalletId(),
                                    transfer.toWalletId(),
                                    transfer.amount().paise(),
                                    transfer.status().name(),
                                    transfer.createdAt());
    }
}
