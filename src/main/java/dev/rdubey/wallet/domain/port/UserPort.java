package dev.rdubey.wallet.domain.port;

import dev.rdubey.wallet.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserPort
{
    User insert(UUID id, String displayName, String email, String phone);

    Optional<User> findById(UUID userId);
}
