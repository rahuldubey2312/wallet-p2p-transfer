package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.application.TransactionHistory;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionHistoryResponse(@JsonProperty("wallet_id") UUID walletId,
                                         @JsonProperty("total") int total,
                                         @JsonProperty("limit") int limit,
                                         @JsonProperty("offset") int offset,
                                         @JsonProperty("transactions") List<TransactionResponse> transactions)
{
    public static TransactionHistoryResponse from(TransactionHistory history)
    {
        return new TransactionHistoryResponse(history.walletId(),
                                              history.total(),
                                              history.limit(),
                                              history.offset(),
                                              history.transactions().stream()
                                                     .map(TransactionResponse::from).toList());
    }
}
