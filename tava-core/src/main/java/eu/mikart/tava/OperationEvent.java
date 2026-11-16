package eu.mikart.tava;

import java.time.Duration;
import java.util.Map;

/**
 * Completed operation metadata, excluding predicates and record values.
 *
 * @param adapter adapter name
 * @param entity entity name
 * @param operation operation name
 * @param duration elapsed time
 * @param batchSize input batch size, or one
 * @param successful whether the operation completed normally
 * @param metrics adapter metrics such as evaluated records and consumed capacity
 * @since 1.3.0
 */
public record OperationEvent(
        String adapter,
        String entity,
        String operation,
        Duration duration,
        int batchSize,
        boolean successful,
        Map<String, Double> metrics) {
    /**
     * Creates immutable event metadata.
     *
     * @param adapter adapter name
     * @param entity entity name
     * @param operation operation name
     * @param duration elapsed time
     * @param batchSize input batch size
     * @param successful successful completion
     * @param metrics adapter metrics
     * @since 1.3.0
     */
    public OperationEvent {
        metrics = Map.copyOf(metrics);
    }
}
