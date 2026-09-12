package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.Transfer;
import dev.rdubey.wallet.domain.TransferStatus;

import java.util.Optional;
import java.util.UUID;

public interface TransferPort
{
    Transfer insert(UUID transferId,
                    UUID fromWalletId,
                    UUID toWalletId,
                    long amountPaise,
                    TransferStatus status);

    Optional<Transfer> findById(UUID transferId);
}
