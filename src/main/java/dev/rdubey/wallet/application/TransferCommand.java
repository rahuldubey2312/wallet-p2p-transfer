package dev.rdubey.wallet.application;

import java.util.UUID;

public record TransferCommand(UUID userId,
                              UUID fromWalletId,
                              UUID toWalletId,
                              long amountPaise,
                              String idempotencyKey)
{
}
