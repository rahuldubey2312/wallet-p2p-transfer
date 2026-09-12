package dev.rdubey.wallet.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately thin HTTP client for the tests.
 * <p>
 * The invariants are about what happens when real requests arrive at the same
 * instant, so the tests drive the service over its actual socket rather than
 * calling beans directly.
 */
public final class HttpProbe
{
    private final HttpClient client = HttpClient.newBuilder()
                                                .connectTimeout(Duration.ofSeconds(10))
                                                .build();
    private final String baseUrl;

    public HttpProbe(int port)
    {
        this.baseUrl = "http://localhost:" + port;
    }

    public HttpResponse<String> post(String path, String token, String body) throws IOException, InterruptedException
    {
        return client.send(request(path, token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                           HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postWithoutBody(String path, String token) throws IOException, InterruptedException
    {
        return client.send(request(path, token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                           HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String path, String token) throws IOException, InterruptedException
    {
        return client.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Releases every call at the same instant so the service really is hit
     * concurrently, instead of quickly one after another.
     */
    public List<HttpResponse<String>> fireTogether(int count, Callable<HttpResponse<String>> call)
            throws InterruptedException
    {
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>(count);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor())
        {
            for (int i = 0; i < count; i++)
            {
                futures.add(pool.submit(() ->
                                        {
                                            startLine.await();
                                            return call.call();
                                        }));
            }
            startLine.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(2, TimeUnit.MINUTES))
            {
                throw new IllegalStateException("concurrent burst did not finish within two minutes");
            }
        }

        List<HttpResponse<String>> responses = new ArrayList<>(count);
        for (Future<HttpResponse<String>> future : futures)
        {
            try
            {
                responses.add(future.get());
            }
            catch (Exception e)
            {
                throw new IllegalStateException("a concurrent request failed outright", e);
            }
        }
        return responses;
    }

    public static String stringField(String json, String field)
    {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find())
        {
            throw new IllegalArgumentException("no string field '" + field + "' in: " + json);
        }
        return matcher.group(1);
    }

    public static long longField(String json, String field)
    {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find())
        {
            throw new IllegalArgumentException("no numeric field '" + field + "' in: " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private HttpRequest.Builder request(String path, String token)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                 .uri(URI.create(baseUrl + path))
                                                 .timeout(Duration.ofSeconds(60))
                                                 .header("Content-Type", "application/json");
        if (token != null)
        {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }
}
