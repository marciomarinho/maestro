package dev.maestro.payment.web;

import dev.maestro.domain.id.Ids;
import dev.maestro.domain.money.Money;
import java.net.URI;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders every error as RFC 9457 {@code application/problem+json}.
 *
 * <p>{@code code} is stable and machine-readable — merchants branch on it — while
 * {@code title} and {@code detail} are for humans and may be reworded. Every response
 * carries a {@code request_id} that correlates to the trace, so a merchant quoting it
 * to support identifies the exact request.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR_BASE = "https://maestro.dev/errors/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed", detail);
    }

    @ExceptionHandler(Money.CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(Money.CurrencyMismatchException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "currency_mismatch", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        String requestId = Ids.request();
        // The detail is deliberately generic: an internal message could disclose schema
        // or infrastructure. The request id is what connects it to the logged cause, so
        // the same value must appear in both places.
        log.error("Unhandled exception, request_id={}", requestId, e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "The request could not be completed. Quote the request_id when contacting support.",
                requestId);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        return problem(status, code, detail, Ids.request());
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String detail, String requestId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_BASE + code.replace('_', '-')));
        problem.setTitle(titleFor(code));
        problem.setProperty("code", code);
        problem.setProperty("request_id", requestId);
        return problem;
    }

    private static String titleFor(String code) {
        String spaced = code.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
