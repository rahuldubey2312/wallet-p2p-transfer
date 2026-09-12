package dev.rdubey.wallet.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A public index at the root path.
 * <p>
 * Without it the base URL answers 401, because authentication is applied
 * before routing and the root is not a known route. That is defensible but
 * reads as a broken service to anyone who opens the URL in a browser, so the
 * root states what the service is and where to look instead.
 */
@RestController
public class ServiceIndexController
{
    @GetMapping("/")
    public Map<String, Object> index()
    {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /wallets", "get-or-create the caller's wallet; requires a bearer token");
        endpoints.put("GET /wallets/{id}", "current balance in integer paise");
        endpoints.put("POST /transfers", "move money; body carries from, to, amount_paise, idempotency_key");
        endpoints.put("GET /transfers/{id}", "transfer status");
        endpoints.put("GET /health", "liveness and readiness");
        endpoints.put("GET /metrics", "Prometheus exposition, including domain counters and p99 latency");

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("service", "wallet-service");
        index.put("description", "Wallet with peer-to-peer transfers. Money is always integer paise.");
        index.put("auth", "Authorization: Bearer <token>, where the token identifies the caller");
        index.put("repository", "https://github.com/rahuldubey2312/wallet-p2p-transfer");
        index.put("endpoints", endpoints);
        return index;
    }
}
