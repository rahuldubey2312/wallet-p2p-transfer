package dev.rdubey.wallet.domain;

/**
 * Money as integer paise. There is deliberately no floating point and no
 * rupee-decimal representation anywhere in the money path.
 *
 * @param paise the amount in paise; never negative
 */
public record Money(long paise)
{
    public Money
    {
        if (paise < 0)
        {
            throw new IllegalArgumentException("money cannot be negative: " + paise);
        }
    }

    public static Money ofPaise(long paise)
    {
        return new Money(paise);
    }

    public boolean isPositive()
    {
        return paise > 0;
    }
}
