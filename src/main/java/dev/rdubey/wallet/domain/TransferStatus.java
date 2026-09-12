package dev.rdubey.wallet.domain;

/**
 * Terminal outcome of a transfer attempt. Both outcomes are persisted so that
 * an idempotent retry replays the original result rather than re-attempting.
 */
public enum TransferStatus
{
    /** Money moved: source debited and destination credited in one transaction. */
    COMPLETED,

    /** Source had insufficient funds. Nothing was debited or credited. */
    DECLINED_INSUFFICIENT_FUNDS
}
