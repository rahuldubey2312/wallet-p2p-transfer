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
 * Bad input must produce a specific 4xx with a usable message, never a 500 or
 * a stack trace, and the operational endpoints must stay open without a token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                // Spring Boot switches metrics exporters off inside tests; the
                // exposition endpoint is part of the deliverable, so it is
                // switched back on and asserted rather than taken on trust.
                properties = "management.prometheus.metrics.export.enabled=true")
@Import(TestcontainersConfiguration.class)
class ApiContractIT
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
    @DisplayName("a request without a bearer token is rejected and says how to get one")
    void missingTokenIsUnauthorized() throws Exception
    {
        HttpResponse<String> response = http.postWithoutBody("/wallets", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("POST /users").doesNotContain("Exception");
    }

    @Test
    @DisplayName("a token the service never issued is rejected")
    void unknownTokenIsUnauthorized() throws Exception
    {
        HttpResponse<String> response = http.postWithoutBody("/wallets", "wlt_not_a_real_token_at_all");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the root path is a public index, not a 401")
    void rootPathIsAPublicIndex() throws Exception
    {
        HttpResponse<String> response = http.get("/", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("wallet-service").contains("POST /transfers");
    }

    @Test
    @DisplayName("health and metrics are reachable without a token")
    void operationalEndpointsArePublic() throws Exception
    {
        assertThat(http.get("/health", null).statusCode()).isEqualTo(200);

        HttpResponse<String> metrics = http.get("/metrics", null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("http_server_requests");
    }

    @Test
    @DisplayName("p99 latency is actually published, not merely configured")
    void latencyPercentilesArePublished() throws Exception
    {
        http.get("/health", null);
        assertThat(http.get("/metrics", null).body()).contains("quantile=\"0.99\"");
    }

    @Test
    @DisplayName("registration requires a display name")
    void registrationRequiresDisplayName() throws Exception
    {
        HttpResponse<String> response = http.post("/users", null, "{\"email\":\"nobody@example.com\"}");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("an invalid email is rejected before a user is created")
    void invalidEmailIsRejected() throws Exception
    {
        HttpResponse<String> response = http.post("/users", null,
                                                  "{\"display_name\":\"Bad Email\",\"email\":\"not-an-email\"}");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a transfer to the same wallet is rejected")
    void selfTransferIsRejected() throws Exception
    {
        String token = newUserToken();
        String wallet = HttpProbe.stringField(http.postWithoutBody("/wallets", token).body(), "id");

        HttpResponse<String> response = http.post("/transfers", token, """
                {"from":"%s","to":"%s","amount_paise":100,"idempotency_key":"%s"}"""
                .formatted(wallet, wallet, UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a non-positive amount is rejected before anything is claimed")
    void nonPositiveAmountIsRejected() throws Exception
    {
        String token = newUserToken();
        String from = HttpProbe.stringField(http.postWithoutBody("/wallets", token).body(), "id");
        String to = HttpProbe.stringField(http.postWithoutBody("/wallets", newUserToken()).body(), "id");

        HttpResponse<String> response = http.post("/transfers", token, """
                {"from":"%s","to":"%s","amount_paise":0,"idempotency_key":"%s"}"""
                .formatted(from, to, UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("malformed JSON is a bad request, not a server error")
    void malformedJsonIsBadRequest() throws Exception
    {
        HttpResponse<String> response = http.post("/transfers", newUserToken(), "{not json");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("Exception");
    }

    @Test
    @DisplayName("an unknown wallet is a 404")
    void unknownWalletIsNotFound() throws Exception
    {
        HttpResponse<String> response = http.get("/wallets/" + UUID.randomUUID(), newUserToken());
        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("every response carries a correlation id")
    void responsesCarryCorrelationId() throws Exception
    {
        HttpResponse<String> response = http.postWithoutBody("/wallets", newUserToken());
        assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    }

    private String newUserToken() throws Exception
    {
        HttpResponse<String> response = http.post("/users", null,
                                                  """
                                                  {"display_name":"Contract User %s"}"""
                                                          .formatted(UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        return HttpProbe.stringField(response.body(), "token");
    }
}
