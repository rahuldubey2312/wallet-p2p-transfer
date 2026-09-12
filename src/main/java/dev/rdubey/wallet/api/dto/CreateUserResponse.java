package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.domain.IssuedCredential;

/**
 * The only response that ever carries the token. It is not retrievable later,
 * because only its hash is stored.
 */
public record CreateUserResponse(@JsonProperty("user") UserResponse user,
                                 @JsonProperty("token") String token,
                                 @JsonProperty("token_note") String tokenNote)
{
    private static final String NOTE =
            "Send this as 'Authorization: Bearer <token>'. It is shown once and cannot be retrieved again.";

    public static CreateUserResponse from(IssuedCredential credential)
    {
        return new CreateUserResponse(UserResponse.from(credential.user()), credential.token(), NOTE);
    }
}
