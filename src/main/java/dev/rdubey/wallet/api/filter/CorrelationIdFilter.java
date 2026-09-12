package dev.rdubey.wallet.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts a correlation id on every request and every log line it produces.
 * <p>
 * An inbound id is honoured so a caller can stitch its own trace to ours, but
 * it is validated against a strict allow-list first: an unchecked header would
 * otherwise let a caller forge newlines into the log stream.
 */
@Component
@Order(CorrelationIdFilter.ORDER)
public class CorrelationIdFilter extends OncePerRequestFilter
{
    public static final int ORDER = 1;
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException
    {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try
        {
            chain.doFilter(request, response);
        }
        finally
        {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(String inbound)
    {
        if (inbound != null && SAFE_ID.matcher(inbound).matches())
        {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
