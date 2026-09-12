package dev.rdubey.wallet.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical fingerprint of the meaningful parts of a transfer request.
 * <p>
 * Replaying a key with a matching fingerprint returns the original result;
 * replaying it with a different one is a conflict. Hashing rather than storing
 * the body keeps the stored claim small and avoids retaining request payloads.
 */
final class RequestFingerprint
{
    private RequestFingerprint()
    {
    }

    static String of(TransferCommand command)
    {
        String canonical = String.join("|",
                                       command.fromWalletId().toString(),
                                       command.toWalletId().toString(),
                                       Long.toString(command.amountPaise()));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
