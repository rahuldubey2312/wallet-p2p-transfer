package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TransferRequest(@JsonProperty("from") @NotNull(message = "from is required") UUID from,

                              @JsonProperty("to") @NotNull(message = "to is required") UUID to,

                              @JsonProperty("amount_paise")
                              @NotNull(message = "amount_paise is required")
                              @Positive(message = "amount_paise must be greater than zero")
                              Long amountPaise,

                              @JsonProperty("idempotency_key")
                              @NotBlank(message = "idempotency_key is required")
                              @Size(max = 128, message = "idempotency_key must be at most 128 characters")
                              String idempotencyKey)
{
}
