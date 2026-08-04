package dev.maestro.payment.web;

import org.springframework.http.HttpStatus;

/**
 * An error with a stable, machine-readable {@code code}.
 *
 * <p>Codes are part of the published contract: merchants branch on them, so they are
 * chosen once and never reworded. The human-readable detail may change freely.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /**
     * A resource that does not exist, or belongs to another merchant.
     *
     * <p>Both cases return {@code 404}. Returning {@code 403} for the second would
     * confirm that an identifier exists, which turns the API into an enumeration
     * oracle for another tenant's payment volume.
     */
    public static ApiException notFound(String resource, String id) {
        return new ApiException(
                HttpStatus.NOT_FOUND, "resource_not_found", "No such %s: %s".formatted(resource, id));
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException unprocessable(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
