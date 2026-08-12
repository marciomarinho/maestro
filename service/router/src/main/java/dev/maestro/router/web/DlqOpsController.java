package dev.maestro.router.web;

import dev.maestro.router.dlq.DlqRedriveService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's lever for dead letters, guarded by the same token as the rest of
 * {@code /ops} ({@link OpsTokenFilter} applies to every path).
 *
 * <p>Redrive is a POST with no parameters on purpose: the operation is "return
 * everything that has died to its topic", and a filtered redrive is an invitation to
 * leave the awkward half of an incident behind. Inspection — what is on the queue and
 * why — belongs to Kafka tooling and the {@code maestro_dlq_depth} gauge, not to a
 * bespoke browser here.
 */
@RestController
@RequestMapping("/ops/dlq")
public class DlqOpsController {

    private final DlqRedriveService redrive;

    public DlqOpsController(DlqRedriveService redrive) {
        this.redrive = redrive;
    }

    @PostMapping("/redrive")
    public RedriveResult redrive() {
        Map<String, Integer> redriven = redrive.redrive();
        return new RedriveResult(
                redriven.values().stream().mapToInt(Integer::intValue).sum(), redriven);
    }

    @ExceptionHandler(DlqRedriveService.RedriveInProgressException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> redriveInProgress(DlqRedriveService.RedriveInProgressException e) {
        return Map.of("code", "redrive_in_progress", "detail", e.getMessage());
    }

    public record RedriveResult(int total, Map<String, Integer> byTopic) {
    }
}
