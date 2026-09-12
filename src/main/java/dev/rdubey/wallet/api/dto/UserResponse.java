package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.domain.User;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(@JsonProperty("id") UUID id,
                           @JsonProperty("display_name") String displayName,
                           @JsonProperty("email") String email,
                           @JsonProperty("phone") String phone,
                           @JsonProperty("created_at") Instant createdAt)
{
    public static UserResponse from(User user)
    {
        return new UserResponse(user.id(), user.displayName(), user.email(), user.phone(), user.createdAt());
    }
}
