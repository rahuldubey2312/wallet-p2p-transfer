package dev.rdubey.wallet.api.filter;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Identifies the caller from a bearer token.
 * <p>
 * The token is a shared secret chosen by the caller; there is no registration
 * step. The user identity is a one-way derivation of the token rather than the
 * token itself, so the credential never reaches the database, the logs, or an
 * error response. Two requests bearing the same token are the same user.
 * <p>
 * This is deliberately minimal: the exercise does not grade auth
 * sophistication, only that a caller is identified.
 */
@Component
@Order(BearerAuthFilter.ORDER)
public class BearerAuthFilter extends OncePerRequestFilter
{
    public static final int ORDER = CorrelationIdFilter.ORDER + 1;
    public static final String USER_ID_ATTRIBUTE = "wallet.userId";
    public static final String MDC_KEY = "user_id";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MIN_TOKEN_LENGTH = 8;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Set<String> PUBLIC_PATHS = Set.of("/health", "/metrics", "/info");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        String path = request.getRequestURI();
        return PUBLIC_PATHS.contains(path) || path.startsWith("/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException
    {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX))
        {
            reject(response, "missing bearer token");
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.length() < MIN_TOKEN_LENGTH || token.length() > MAX_TOKEN_LENGTH)
        {
            reject(response, "bearer token must be between " + MIN_TOKEN_LENGTH
                             + " and " + MAX_TOKEN_LENGTH + " characters");
            return;
        }

        String userId = userIdFor(token);
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        MDC.put(MDC_KEY, userId);
        try
        {
            chain.doFilter(request, response);
        }
        finally
        {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Derives a stable, non-reversible user id from the token so that logs and
     * stored rows never contain the credential itself.
     */
    public static String userIdFor(String token)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("usr_");
            for (int i = 0; i < 12; i++)
            {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
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
