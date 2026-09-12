package dev.rdubey.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(@JsonProperty("display_name")
                                @NotBlank(message = "display_name is required")
                                @Size(max = 120, message = "display_name must be at most 120 characters")
                                String displayName,

                                @JsonProperty("email")
                                @Email(message = "email must be a valid address")
                                @Size(max = 254, message = "email must be at most 254 characters")
                                String email,

                                @JsonProperty("phone")
                                @Pattern(regexp = "^$|^\\+?[0-9 ()-]{6,20}$",
                                         message = "phone must be 6 to 20 digits, optionally prefixed with +")
                                String phone)
{
}
