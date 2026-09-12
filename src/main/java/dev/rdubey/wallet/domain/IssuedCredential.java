package dev.rdubey.wallet.domain;

/**
 * A newly created user together with the one and only time its token is
 * visible. The token is returned to the caller and then forgotten: storage
 * keeps nothing but its hash.
 */
public record IssuedCredential(User user, String token)
{
}
