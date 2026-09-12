package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.rdubey.wallet.application.UserProfile;

import java.util.List;

/**
 * @param wallet null until the user has created one with POST /wallets
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserProfileResponse(@JsonProperty("user") UserResponse user,
                                  @JsonProperty("wallet") WalletResponse wallet,
                                  @JsonProperty("recent_transactions") List<TransactionResponse> recentTransactions)
{
    public static UserProfileResponse from(UserProfile profile)
    {
        return new UserProfileResponse(
                UserResponse.from(profile.user()),
                profile.wallet() == null ? null : WalletResponse.from(profile.wallet()),
                profile.recentTransactions().stream().map(TransactionResponse::from).toList());
    }
}
