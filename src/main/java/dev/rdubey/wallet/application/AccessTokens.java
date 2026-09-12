package dev.rdubey.wallet.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Mints and fingerprints access tokens.
 * <p>
 * Tokens are generated from a cryptographically secure source, and only their
 * SHA-256 is ever stored, so a leaked database cannot be replayed against the
 * API. A plain hash is appropriate here rather than a password KDF: these are
 * 256 bits of machine-generated randomness, so there is no low-entropy secret
 * for an attacker to grind through.
 */
@Component
public class AccessTokens
{
    private static final String PREFIX = "wlt_";
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate()
    {
        byte[] material = new byte[TOKEN_BYTES];
        random.nextBytes(material);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    public String hash(String token)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
