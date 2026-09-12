package dev.rdubey.wallet.api;

import dev.rdubey.wallet.api.filter.CorrelationIdFilter;
import dev.rdubey.wallet.domain.exception.IdempotencyConflictException;
import dev.rdubey.wallet.domain.exception.InvalidTransferException;
import dev.rdubey.wallet.domain.exception.TransferNotFoundException;
import dev.rdubey.wallet.domain.exception.WalletAccessDeniedException;
import dev.rdubey.wallet.domain.exception.WalletNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Turns every failure into RFC 9457 problem detail JSON. Client mistakes get a
 * specific 4xx with a usable message; only genuinely unexpected faults become
 * a 500, and those never leak a stack trace to the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail handleWalletNotFound(WalletNotFoundException e)
    {
        return problem(HttpStatus.NOT_FOUND, "wallet_not_found", e.getMessage());
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException e)
    {
        return problem(HttpStatus.NOT_FOUND, "transfer_not_found", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleUnknownRoute(NoResourceFoundException e)
    {
        return problem(HttpStatus.NOT_FOUND, "not_found", "no such endpoint");
    }

    @ExceptionHandler(WalletAccessDeniedException.class)
    public ProblemDetail handleAccessDenied(WalletAccessDeniedException e)
    {
        return problem(HttpStatus.FORBIDDEN, "wallet_not_owned",
                       "the source wallet does not belong to the caller");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException e)
    {
        return problem(HttpStatus.CONFLICT, "idempotency_key_reused",
                       "this idempotency key was already used with a different request body");
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ProblemDetail handleInvalidTransfer(InvalidTransferException e)
    {
        return problem(HttpStatus.BAD_REQUEST, "invalid_transfer", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e)
    {
        String detail = e.getBindingResult().getFieldErrors().stream()
                         .map(error -> error.getField() + ": " + error.getDefaultMessage())
                         .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "validation_failed",
                       detail.isEmpty() ? "request validation failed" : detail);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerValidation(HandlerMethodValidationException e)
    {
        return problem(HttpStatus.BAD_REQUEST, "validation_failed", "request validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e)
    {
        return problem(HttpStatus.BAD_REQUEST, "malformed_request",
                       "request body is not valid JSON or a field has the wrong type");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e)
    {
        LOG.error("unhandled_exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected server error");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail)
    {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null)
        {
            problem.setProperty("correlation_id", correlationId);
        }
        return problem;
    }
}
