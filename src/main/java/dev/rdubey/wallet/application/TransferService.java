package dev.rdubey.wallet.application;

import dev.rdubey.wallet.domain.IdempotencyRecord;
import dev.rdubey.wallet.domain.Transfer;
import dev.rdubey.wallet.domain.TransferStatus;
import dev.rdubey.wallet.domain.Wallet;
import dev.rdubey.wallet.domain.exception.IdempotencyConflictException;
import dev.rdubey.wallet.domain.exception.InvalidTransferException;
import dev.rdubey.wallet.domain.exception.TransferNotFoundException;
import dev.rdubey.wallet.domain.exception.WalletAccessDeniedException;
import dev.rdubey.wallet.domain.exception.WalletNotFoundException;
import dev.rdubey.wallet.domain.port.IdempotencyPort;
import dev.rdubey.wallet.domain.port.TransferPort;
import dev.rdubey.wallet.domain.port.WalletLedgerPort;
import dev.rdubey.wallet.domain.port.WalletQueryPort;
import dev.rdubey.wallet.infrastructure.observability.DomainEvents;
import dev.rdubey.wallet.infrastructure.observability.WalletMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Peer-to-peer transfers.
 * <p>
 * The correctness story is one database transaction containing, in order:
 * <ol>
 *   <li>the idempotency claim, so a duplicate key can never produce a second
 *       debit and the claim commits with the money or not at all;</li>
 *   <li>row locks on both wallets taken in ascending id order, so an A-to-B
 *       and a B-to-A transfer running at the same instant cannot deadlock;</li>
 *   <li>a conditional debit that only applies when the balance covers the
 *       amount, so overdraft is impossible without reading then writing in
 *       application code;</li>
 *   <li>the matching credit, so the sum of balances is unchanged.</li>
 * </ol>
 * The retry loop exists because a losing duplicate learns it lost only when
 * its INSERT conflicts, which aborts its transaction; the replay must
 * therefore be read afterwards in a fresh one.
 */
@Service
public class TransferService
{
    private static final int MAX_ATTEMPTS = 3;

    private final WalletQueryPort wallets;
    private final WalletLedgerPort ledger;
    private final TransferPort transfers;
    private final IdempotencyPort idempotency;
    private final TransactionTemplate transactionTemplate;
    private final WalletMetrics metrics;
    private final DomainEvents events;

    public TransferService(WalletQueryPort wallets,
                           WalletLedgerPort ledger,
                           TransferPort transfers,
                           IdempotencyPort idempotency,
                           TransactionTemplate transactionTemplate,
                           WalletMetrics metrics,
                           DomainEvents events)
    {
        this.wallets = wallets;
        this.ledger = ledger;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.events = events;
    }

    public TransferOutcome transfer(TransferCommand command)
    {
        validate(command);
        String fingerprint = RequestFingerprint.of(command);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            try
            {
                TransferOutcome outcome = transactionTemplate.execute(status -> apply(command, fingerprint));
                if (outcome == null)
                {
                    throw new IllegalStateException("transfer produced no outcome");
                }
                publish(outcome);
                return outcome;
            }
            catch (DuplicateKeyException conflict)
            {
                Optional<TransferOutcome> replayed = replay(command, fingerprint);
                if (replayed.isPresent())
                {
                    return replayed.get();
                }
                // The winner rolled back after we saw the conflict, so the key
                // is free again. Fall through and retry the transfer itself.
            }
        }
        throw new IllegalStateException("transfer did not settle after " + MAX_ATTEMPTS + " attempts");
    }

    public Transfer getById(UUID transferId)
    {
        return transfers.findById(transferId).orElseThrow(() -> new TransferNotFoundException(transferId));
    }

    /**
     * Runs inside the caller's transaction. Any exception thrown here rolls
     * back the idempotency claim too, which is deliberate: a request rejected
     * for a bad wallet or a forbidden debit had no effect, so its key must be
     * reusable. An insufficient-funds decline is not an exception; it is a
     * real business outcome and is committed so a retry replays it.
     */
    private TransferOutcome apply(TransferCommand command, String fingerprint)
    {
        idempotency.claim(command.userId(), command.idempotencyKey(), fingerprint);

        Wallet source = wallets.findById(command.fromWalletId())
                               .orElseThrow(() -> new WalletNotFoundException(command.fromWalletId()));
        wallets.findById(command.toWalletId())
               .orElseThrow(() -> new WalletNotFoundException(command.toWalletId()));

        if (!source.isOwnedBy(command.userId()))
        {
            throw new WalletAccessDeniedException(command.fromWalletId());
        }

        lockInAscendingIdOrder(command.fromWalletId(), command.toWalletId());

        UUID transferId = UUID.randomUUID();
        if (!ledger.debitIfSufficient(command.fromWalletId(), command.amountPaise()))
        {
            Transfer declined = transfers.insert(transferId,
                                                 command.fromWalletId(),
                                                 command.toWalletId(),
                                                 command.amountPaise(),
                                                 TransferStatus.DECLINED_INSUFFICIENT_FUNDS);
            idempotency.complete(command.userId(), command.idempotencyKey(), transferId);
            return new TransferOutcome(declined, false);
        }

        ledger.credit(command.toWalletId(), command.amountPaise());
        Transfer completed = transfers.insert(transferId,
                                              command.fromWalletId(),
                                              command.toWalletId(),
                                              command.amountPaise(),
                                              TransferStatus.COMPLETED);
        idempotency.complete(command.userId(), command.idempotencyKey(), transferId);
        return new TransferOutcome(completed, false);
    }

    /**
     * Locks both wallets in ascending id order. Every transfer agrees on this
     * order regardless of direction, which is what removes the deadlock
     * between simultaneous A-to-B and B-to-A transfers.
     */
    private void lockInAscendingIdOrder(UUID first, UUID second)
    {
        List<UUID> ordered = List.of(first, second).stream().sorted(Comparator.naturalOrder()).toList();
        ordered.forEach(ledger::lockForUpdate);
    }

    private Optional<TransferOutcome> replay(TransferCommand command, String fingerprint)
    {
        IdempotencyRecord record = transactionTemplate.execute(
                status -> idempotency.find(command.userId(), command.idempotencyKey()).orElse(null));

        if (record == null || !record.isComplete())
        {
            return Optional.empty();
        }

        if (!record.matches(fingerprint))
        {
            metrics.idempotencyConflict();
            events.idempotencyConflict(command.idempotencyKey());
            throw new IdempotencyConflictException(command.idempotencyKey());
        }

        Transfer original = transfers.findById(record.transferId())
                                     .orElseThrow(() -> new TransferNotFoundException(record.transferId()));

        metrics.idempotentReplay();
        events.idempotentReplay(original.id(), command.idempotencyKey());
        return Optional.of(new TransferOutcome(original, true));
    }

    /**
     * Events and counters are emitted only after the transaction commits, so a
     * rolled-back attempt never inflates the numbers or claims money moved.
     */
    private void publish(TransferOutcome outcome)
    {
        Transfer transfer = outcome.transfer();
        if (transfer.isCompleted())
        {
            metrics.transferCreated();
            events.transferCreated(transfer.id(), transfer.fromWalletId(), transfer.toWalletId(),
                                   transfer.amount().paise());
            events.debited(transfer.id(), transfer.fromWalletId(), transfer.amount().paise());
            events.credited(transfer.id(), transfer.toWalletId(), transfer.amount().paise());
        }
        else
        {
            metrics.transferDeclinedInsufficientFunds();
            events.declined(transfer.id(), transfer.fromWalletId(), transfer.toWalletId(),
                            transfer.amount().paise(), "insufficient_funds");
        }
    }

    private static void validate(TransferCommand command)
    {
        if (command.fromWalletId().equals(command.toWalletId()))
        {
            throw new InvalidTransferException("from and to must be different wallets");
        }
        if (command.amountPaise() <= 0)
        {
            throw new InvalidTransferException("amount_paise must be greater than zero");
        }
    }
}
