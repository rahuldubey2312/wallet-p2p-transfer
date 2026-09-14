package dev.rdubey.wallet;

import dev.rdubey.wallet.support.HttpProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user entity: registration issues a usable credential, the wallet is
 * attributable to its owner, and history reads back from the transfers that
 * actually happened.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "management.prometheus.metrics.export.enabled=true")
@Import(TestcontainersConfiguration.class)
class UserAndHistoryIT
{
    @Value("${local.server.port}")
    private int port;

    private HttpProbe http;

    @BeforeEach
    void setUp()
    {
        http = new HttpProbe(port);
    }

    @Test
    @DisplayName("registration returns the user's details and a working token")
    void registrationIssuesAUsableToken() throws Exception
    {
        String email = "rahul+" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> created = http.post("/users", null, """
                {"display_name":"Rahul Dubey","email":"%s","phone":"+91 9876543210"}"""
                .formatted(email));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("Rahul Dubey").contains(email).contains("wlt_");

        String token = HttpProbe.stringField(created.body(), "token");
        HttpResponse<String> profile = http.get("/users/me", token);

        assertThat(profile.statusCode()).isEqualTo(200);
        assertThat(profile.body()).contains("Rahul Dubey").contains(email);
    }

    @Test
    @DisplayName("the issued token is never echoed back by any later read")
    void tokenIsNotRetrievable() throws Exception
    {
        HttpResponse<String> created = http.post("/users", null,
                                                 "{\"display_name\":\"Secret Holder\"}");
        String token = HttpProbe.stringField(created.body(), "token");

        assertThat(http.get("/users/me", token).body())
                .as("a profile read must not leak the credential")
                .doesNotContain(token);
    }

    @Test
    @DisplayName("reusing an email is a conflict, not a second user")
    void duplicateEmailConflicts() throws Exception
    {
        String email = "dupe+" + UUID.randomUUID() + "@example.com";
        String body = "{\"display_name\":\"First\",\"email\":\"%s\"}".formatted(email);

        assertThat(http.post("/users", null, body).statusCode()).isEqualTo(201);
        assertThat(http.post("/users", null, body).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("a user's wallet is attributed to them")
    void walletIsMappedToItsOwner() throws Exception
    {
        HttpResponse<String> created = http.post("/users", null,
                                                 "{\"display_name\":\"Wallet Owner\"}");
        String token = HttpProbe.stringField(created.body(), "token");
        String userId = HttpProbe.stringField(created.body(), "id");

        String walletBody = http.postWithoutBody("/wallets", token).body();
        assertThat(HttpProbe.stringField(walletBody, "user_id")).isEqualTo(userId);

        assertThat(http.get("/users/me", token).body())
                .contains(HttpProbe.stringField(walletBody, "id"));
    }

    @Test
    @DisplayName("a user with no wallet has an empty history rather than an error")
    void historyIsEmptyBeforeAnyWalletExists() throws Exception
    {
        String token = HttpProbe.stringField(
                http.post("/users", null, "{\"display_name\":\"No Wallet\"}").body(), "token");

        HttpResponse<String> history = http.get("/users/me/transactions", token);

        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(HttpProbe.longField(history.body(), "total")).isZero();
    }

    @Test
    @DisplayName("history shows a debit for the sender and a credit for the recipient")
    void historyRecordsBothSidesOfATransfer() throws Exception
    {
        String senderToken = newUserToken("Sender");
        String recipientToken = newUserToken("Recipient");
        String sender = HttpProbe.stringField(http.postWithoutBody("/wallets", senderToken).body(), "id");
        String recipient = HttpProbe.stringField(http.postWithoutBody("/wallets", recipientToken).body(), "id");

        HttpResponse<String> transfer = http.post("/transfers", senderToken, """
                {"from":"%s","to":"%s","amount_paise":7500,"idempotency_key":"%s"}"""
                .formatted(sender, recipient, UUID.randomUUID()));
        assertThat(transfer.statusCode()).isEqualTo(201);
        String transferId = HttpProbe.stringField(transfer.body(), "id");

        HttpResponse<String> senderHistory = http.get("/users/me/transactions", senderToken);
        assertThat(senderHistory.statusCode()).isEqualTo(200);
        assertThat(senderHistory.body()).contains(transferId).contains("DEBIT").contains(recipient);

        HttpResponse<String> recipientHistory = http.get("/users/me/transactions", recipientToken);
        assertThat(recipientHistory.body()).contains(transferId).contains("CREDIT").contains(sender);
    }

    @Test
    @DisplayName("history paging reports the full total, not just the page size")
    void historyPagingReportsTheTotal() throws Exception
    {
        String senderToken = newUserToken("Pager");
        String sender = HttpProbe.stringField(http.postWithoutBody("/wallets", senderToken).body(), "id");
        String recipient = HttpProbe.stringField(
                http.postWithoutBody("/wallets", newUserToken("Pagee")).body(), "id");

        for (int i = 0; i < 3; i++)
        {
            http.post("/transfers", senderToken, """
                    {"from":"%s","to":"%s","amount_paise":100,"idempotency_key":"%s"}"""
                    .formatted(sender, recipient, UUID.randomUUID()));
        }

        HttpResponse<String> firstPage = http.get("/users/me/transactions?limit=2&offset=0", senderToken);
        assertThat(HttpProbe.longField(firstPage.body(), "total")).isEqualTo(3);
        assertThat(HttpProbe.longField(firstPage.body(), "limit")).isEqualTo(2);
    }

    @Test
    @DisplayName("recent domain events are readable over HTTP without a token")
    void recentLogsArePubliclyReadable() throws Exception
    {
        String senderToken = newUserToken("Logged Sender");
        String sender = HttpProbe.stringField(http.postWithoutBody("/wallets", senderToken).body(), "id");
        String recipient = HttpProbe.stringField(
                http.postWithoutBody("/wallets", newUserToken("Logged Recipient")).body(), "id");

        http.post("/transfers", senderToken, """
                {"from":"%s","to":"%s","amount_paise":4200,"idempotency_key":"%s"}"""
                .formatted(sender, recipient, UUID.randomUUID()));

        HttpResponse<String> logs = http.get("/logs/recent", null);

        assertThat(logs.statusCode()).as("no bearer token required").isEqualTo(200);
        assertThat(logs.body())
                .contains("transfer_created")
                .contains("debited")
                .contains("credited")
                .contains("correlation_id");
    }

    private String newUserToken(String name) throws Exception
    {
        HttpResponse<String> response = http.post("/users", null,
                                                  "{\"display_name\":\"%s %s\"}"
                                                          .formatted(name, UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        return HttpProbe.stringField(response.body(), "token");
    }
}
