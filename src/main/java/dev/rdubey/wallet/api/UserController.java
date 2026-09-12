package dev.rdubey.wallet.api;

import dev.rdubey.wallet.api.dto.CreateUserRequest;
import dev.rdubey.wallet.api.dto.CreateUserResponse;
import dev.rdubey.wallet.api.dto.TransactionHistoryResponse;
import dev.rdubey.wallet.api.dto.UserProfileResponse;
import dev.rdubey.wallet.api.filter.BearerAuthFilter;
import dev.rdubey.wallet.application.UserService;
import dev.rdubey.wallet.infrastructure.observability.DomainEvents;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class UserController
{
    private final UserService userService;
    private final DomainEvents events;

    public UserController(UserService userService, DomainEvents events)
    {
        this.userService = userService;
        this.events = events;
    }

    /**
     * Registers a user and returns the token that authenticates it. This is
     * the only unauthenticated write endpoint, because it is how a caller
     * obtains a credential in the first place.
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUserResponse create(@Valid @RequestBody CreateUserRequest request)
    {
        var credential = userService.register(blankToNull(request.displayName()),
                                              blankToNull(request.email()),
                                              blankToNull(request.phone()));
        events.userRegistered(credential.user().id());
        return CreateUserResponse.from(credential);
    }

    /** The caller's own profile, their wallet, and their latest activity. */
    @GetMapping("/users/me")
    public UserProfileResponse me(@RequestAttribute(BearerAuthFilter.USER_ID_ATTRIBUTE) UUID userId)
    {
        return UserProfileResponse.from(userService.profileOf(userId));
    }

    /** The caller's transaction history, newest first. */
    @GetMapping("/users/me/transactions")
    public TransactionHistoryResponse transactions(
            @RequestAttribute(BearerAuthFilter.USER_ID_ATTRIBUTE) UUID userId,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset)
    {
        return TransactionHistoryResponse.from(userService.historyOf(userId, limit, offset));
    }

    /**
     * An omitted optional field and one sent as "" mean the same thing to a
     * caller, so both are stored as absent rather than as an empty string that
     * would occupy the unique email index.
     */
    private static String blankToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
