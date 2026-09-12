package dev.rdubey.wallet.api;

import dev.rdubey.wallet.api.dto.WalletResponse;
import dev.rdubey.wallet.api.filter.BearerAuthFilter;
import dev.rdubey.wallet.application.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class WalletController
{
    private final WalletService walletService;

    public WalletController(WalletService walletService)
    {
        this.walletService = walletService;
    }

    /**
     * Get-or-create for the calling user. Safe to call repeatedly and safe to
     * call concurrently: every caller for the same user receives the same
     * wallet id.
     */
    @PostMapping("/wallets")
    public WalletResponse createOrGet(@RequestAttribute(BearerAuthFilter.USER_ID_ATTRIBUTE) UUID userId)
    {
        return WalletResponse.from(walletService.getOrCreate(userId));
    }

    /**
     * Any authenticated caller may read any wallet by id. This is intentional
     * so that a reviewer can sum balances across wallets to check
     * conservation; only debits are restricted to the owner.
     */
    @GetMapping("/wallets/{id}")
    public WalletResponse get(@PathVariable("id") UUID id)
    {
        return WalletResponse.from(walletService.getById(id));
    }
}
