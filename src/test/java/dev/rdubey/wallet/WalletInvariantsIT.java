package dev.rdubey.wallet;

import dev.rdubey.wallet.domain.port.WalletQueryPort;
import dev.rdubey.wallet.support.HttpProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four graded invariants, exercised over real HTTP against a real
 * Postgres, with genuinely simultaneous requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "management.prometheus.metrics.export.enabled=true")
@Import(TestcontainersConfiguration.class)
class WalletInvariantsIT
{
    private static final long OPENING_BALANCE = 1_000_000L;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WalletQueryPort wallets;

    private HttpProbe http;
    private String readerToken;

    @BeforeEach
    void setUp() throws Exception
    {
        http = new HttpProbe(port);
        readerToken = newUserToken();
    }

    @Test
    @DisplayName("Invariant 4: concurrent get-or-create for one new user yields exactly one wallet")
    void concurrentGetOrCreateYieldsExactlyOneWallet() throws Exception
    {
        String token = newUserToken();

        List<HttpResponse<String>> responses = http.fireTogether(25, () -> http.postWithoutBody("/wallets", token));

        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));

        Set<String> walletIds = responses.stream()
                                         .map(response -> HttpProbe.stringField(response.body(), "id"))
                                         .collect(Collectors.toSet());
        assertThat(walletIds).as("25 concurrent creates must converge on one wallet").hasSize(1);
    }

    @Test
    @DisplayName("Invariant 3: a retry storm on one idempotency key debits exactly once")
    void idempotentRetryStormAppliesExactlyOneDebit() throws Exception
    {
        String senderToken = newUserToken();
        String sender = createWallet(senderToken);
        String recipient = createWallet(newUserToken());
        long amount = 25_000L;

        String body = transferBody(sender, recipient, amount, "storm-" + UUID.randomUUID());
        List<HttpResponse<String>> responses =
                http.fireTogether(30, () -> http.post("/transfers", senderToken, body));

        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));

        Set<String> transferIds = responses.stream()
                                           .map(response -> HttpProbe.stringField(response.body(), "id"))
                                           .collect(Collectors.toSet());
        assertThat(transferIds).as("every retry must replay the one original transfer").hasSize(1);

        Set<String> bodies = responses.stream().map(HttpResponse::body).collect(Collectors.toSet());
        assertThat(bodies).as("replayed responses must be identical to the original").hasSize(1);

        assertThat(balanceOf(sender)).isEqualTo(OPENING_BALANCE - amount);
        assertThat(balanceOf(recipient)).isEqualTo(OPENING_BALANCE + amount);
    }

    @Test
    @DisplayName("Invariants 1 and 2: bidirectional contention conserves the total and never goes negative")
    void bidirectionalContentionConservesTotal() throws Exception
    {
        String tokenA = newUserToken();
        String tokenB = newUserToken();
        String walletA = createWallet(tokenA);
        String walletB = createWallet(tokenB);

        long totalBefore = wallets.totalBalancePaise();

        // A to B and B to A at the same instant: the case that deadlocks if
        // the two wallets are locked in request order rather than a fixed one.
        List<HttpResponse<String>> responses = http.fireTogether(120, () ->
        {
            boolean forward = ThreadLocalRandom.current().nextBoolean();
            String from = forward ? walletA : walletB;
            String to = forward ? walletB : walletA;
            String token = forward ? tokenA : tokenB;
            long amount = ThreadLocalRandom.current().nextLong(1, 5_000);
            return http.post("/transfers", token,
                             transferBody(from, to, amount, UUID.randomUUID().toString()));
        });

        assertThat(responses)
                .as("contention must never produce a server error")
                .allSatisfy(response -> assertThat(response.statusCode()).isLessThan(500));

        assertThat(wallets.totalBalancePaise())
                .as("no money may be created or destroyed by transfers")
                .isEqualTo(totalBefore);
        assertThat(balanceOf(walletA)).isNotNegative();
        assertThat(balanceOf(walletB)).isNotNegative();
        assertThat(balanceOf(walletA) + balanceOf(walletB)).isEqualTo(2 * OPENING_BALANCE);
    }

    @Test
    @DisplayName("Invariant 2: an overdraft is declined cleanly and moves no money")
    void overdraftIsDeclinedAndChangesNothing() throws Exception
    {
        String senderToken = newUserToken();
        String sender = createWallet(senderToken);
        String recipient = createWallet(newUserToken());

        HttpResponse<String> response = http.post("/transfers", senderToken,
                                                  transferBody(sender, recipient, OPENING_BALANCE + 1,
                                                               UUID.randomUUID().toString()));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("DECLINED_INSUFFICIENT_FUNDS");
        assertThat(balanceOf(sender)).isEqualTo(OPENING_BALANCE);
        assertThat(balanceOf(recipient)).isEqualTo(OPENING_BALANCE);
    }

    @Test
    @DisplayName("Invariant 3: a declined result is itself replayed, not retried")
    void declinedTransferIsReplayedOnRetry() throws Exception
    {
        String senderToken = newUserToken();
        String sender = createWallet(senderToken);
        String recipient = createWallet(newUserToken());
        String key = UUID.randomUUID().toString();
        String body = transferBody(sender, recipient, OPENING_BALANCE + 1, key);

        HttpResponse<String> first = http.post("/transfers", senderToken, body);
        HttpResponse<String> second = http.post("/transfers", senderToken, body);

        assertThat(first.statusCode()).isEqualTo(422);
        assertThat(second.statusCode()).isEqualTo(422);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("Idempotent-Replay")).contains("true");
    }

    @Test
    @DisplayName("Invariant 3: the same key with a different body is a conflict, not a second debit")
    void sameKeyDifferentBodyConflicts() throws Exception
    {
        String senderToken = newUserToken();
        String sender = createWallet(senderToken);
        String recipient = createWallet(newUserToken());
        String key = UUID.randomUUID().toString();

        HttpResponse<String> first = http.post("/transfers", senderToken,
                                               transferBody(sender, recipient, 1_000L, key));
        HttpResponse<String> second = http.post("/transfers", senderToken,
                                                transferBody(sender, recipient, 2_000L, key));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(balanceOf(sender)).as("the conflicting retry must not debit again")
                                     .isEqualTo(OPENING_BALANCE - 1_000L);
    }

    @Test
    @DisplayName("A caller may not debit a wallet it does not own")
    void debitingSomeoneElsesWalletIsForbidden() throws Exception
    {
        String victim = createWallet(newUserToken());
        String attackerToken = newUserToken();
        String attacker = createWallet(attackerToken);

        HttpResponse<String> response = http.post("/transfers", attackerToken,
                                                  transferBody(victim, attacker, 1_000L,
                                                               UUID.randomUUID().toString()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(balanceOf(victim)).isEqualTo(OPENING_BALANCE);
    }

    private String createWallet(String token) throws Exception
    {
        HttpResponse<String> response = http.postWithoutBody("/wallets", token);
        assertThat(response.statusCode()).isEqualTo(200);
        return HttpProbe.stringField(response.body(), "id");
    }

    private long balanceOf(String walletId) throws Exception
    {
        HttpResponse<String> response = http.get("/wallets/" + walletId, readerToken);
        assertThat(response.statusCode()).isEqualTo(200);
        return HttpProbe.longField(response.body(), "balance_paise");
    }

    /** Registers a user and returns the token the service issued for it. */
    private String newUserToken() throws Exception
    {
        HttpResponse<String> response = http.post("/users", null,
                                                  """
                                                  {"display_name":"Test User %s"}"""
                                                          .formatted(UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        return HttpProbe.stringField(response.body(), "token");
    }

    private static String transferBody(String from, String to, long amountPaise, String idempotencyKey)
    {
        return """
               {"from":"%s","to":"%s","amount_paise":%d,"idempotency_key":"%s"}"""
                .formatted(from, to, amountPaise, idempotencyKey);
    }
}
