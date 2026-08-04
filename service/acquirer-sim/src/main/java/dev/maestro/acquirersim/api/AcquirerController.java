package dev.maestro.acquirersim.api;

import dev.maestro.acquirersim.AcquirerSimProperties;
import dev.maestro.acquirersim.AcquirerSimulator;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The interface a real acquiring bank would expose, in miniature. */
@RestController
@RequestMapping("/acquirer")
public class AcquirerController {

    private final AcquirerSimulator simulator;

    public AcquirerController(AcquirerSimulator simulator) {
        this.simulator = simulator;
    }

    @GetMapping
    public List<AcquirerSummary> list() {
        return simulator.acquirers().values().stream()
                .map(a -> new AcquirerSummary(a.id(), a.name(), a.latency().toMillis()))
                .toList();
    }

    /**
     * @param idempotencyKey required, exactly as a real acquirer requires it. A repeat
     *                       returns the original answer rather than authorizing again,
     *                       which is what makes the platform's retries safe.
     */
    @PostMapping("/{acquirerId}/authorize")
    public AuthorizeResponse authorize(
            @PathVariable String acquirerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AuthorizeRequest request) {
        return simulator.authorize(acquirerId, idempotencyKey, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String unknownAcquirer(NoSuchElementException e) {
        return e.getMessage();
    }

    public record AcquirerSummary(String id, String name, long latencyMs) {
    }
}
