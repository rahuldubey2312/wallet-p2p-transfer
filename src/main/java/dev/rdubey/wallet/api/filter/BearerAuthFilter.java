package dev.rdubey.wallet.api.filter;

import dev.rdubey.wallet.application.AccessTokens;
import dev.rdubey.wallet.domain.port.CredentialPort;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticates a caller from a token the service itself issued.
 * <p>
 * The presented token is hashed and looked up in {@code user_tokens}; an
 * unknown token is rejected. Comparing hashes means the credential is never
 * stored in a replayable form, and the resolved identity is the user's id, so
 * the token never reaches the logs, the database rows, or an error response.
 */
@Component
@Order(BearerAuthFilter.ORDER)
public class BearerAuthFilter extends OncePerRequestFilter
{
    public static final int ORDER = CorrelationIdFilter.ORDER + 1;
    public static final String USER_ID_ATTRIBUTE = "wallet.userId";
    public static final String MDC_KEY = "user_id";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Set<String> PUBLIC_PATHS = Set.of("/", "/health", "/metrics", "/info");

    private final CredentialPort credentials;
    private final AccessTokens accessTokens;

    public BearerAuthFilter(CredentialPort credentials, AccessTokens accessTokens)
    {
        this.credentials = credentials;
        this.accessTokens = accessTokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path) || path.startsWith("/health/"))
        {
            return true;
        }
        // Registration is how a caller obtains a token, so it cannot require one.
        return "POST".equals(request.getMethod()) && "/users".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException
    {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX))
        {
            reject(response, "missing bearer token; create a user with POST /users to obtain one");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH)
        {
            reject(response, "malformed bearer token");
            return;
        }

        Optional<UUID> userId = credentials.resolveUserId(accessTokens.hash(token));
        if (userId.isEmpty())
        {
            reject(response, "unrecognised token; create a user with POST /users to obtain one");
            return;
        }

        request.setAttribute(USER_ID_ATTRIBUTE, userId.get());
        MDC.put(MDC_KEY, userId.get().toString());
        try
        {
            chain.doFilter(request, response);
        }
        finally
        {
            MDC.remove(MDC_KEY);
        }
    }

    private static void reject(HttpServletResponse response, String detail) throws IOException
    {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                                   {"type":"about:blank","title":"Unauthorized","status":401,\
                                   "code":"unauthorized","detail":"%s"}""".formatted(detail));
    }
}
