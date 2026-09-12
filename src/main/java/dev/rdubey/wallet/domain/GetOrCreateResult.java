package dev.rdubey.wallet.domain;

/**
 * Outcome of a get-or-create call, distinguishing the caller that actually
 * created the wallet from the callers that raced and found it already there.
 */
public record GetOrCreateResult(Wallet wallet, boolean created)
{
}
