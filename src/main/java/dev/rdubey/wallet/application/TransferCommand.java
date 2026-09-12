package dev.rdubey.wallet.application;

import java.util.UUID;

public record TransferCommand(String userId,
                              UUID fromWalletId,
                              UUID toWalletId,
                              long amountPaise,
                              String idempotencyKey)
{
}
