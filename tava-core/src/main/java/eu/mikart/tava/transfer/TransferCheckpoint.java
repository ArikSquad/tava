package eu.mikart.tava.transfer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Durable position at a completed source-page boundary.
 *
 * @param transferId stable identity of the transfer configuration
 * @param sourceAdapter source adapter name
 * @param targetAdapter target adapter name
 * @param schemaFingerprint digest of the transfer schema
 * @param batchSize page size used by the transfer
 * @param entityIndex index of the next entity to read
 * @param cursor continuation token for the next source page, or {@code null} when starting an entity
 * @param counts records transferred per entity
 * @param issues retained diagnostics from completed pages
 * @param complete whether all entities have been transferred
 * @since 1.3.0
 */
public record TransferCheckpoint(
        String transferId,
        String sourceAdapter,
        String targetAdapter,
        String schemaFingerprint,
        int batchSize,
        int entityIndex,
        @Nullable String cursor,
        Map<String, Long> counts,
        boolean complete,
        List<TransferIssue> issues) {
    /**
     * Creates a checkpoint without retained diagnostics.
     *
     * @param transferId transfer identifier
     * @param sourceAdapter source adapter
     * @param targetAdapter target adapter
     * @param schemaFingerprint schema digest
     * @param batchSize page size
     * @param entityIndex next entity index
     * @param cursor next page cursor
     * @param counts completed record counts
     * @param complete completion state
     */
    public TransferCheckpoint(
            String transferId,
            String sourceAdapter,
            String targetAdapter,
            String schemaFingerprint,
            int batchSize,
            int entityIndex,
            String cursor,
            Map<String, Long> counts,
            boolean complete) {
        this(
                transferId,
                sourceAdapter,
                targetAdapter,
                schemaFingerprint,
                batchSize,
                entityIndex,
                cursor,
                counts,
                complete,
                List.of());
    }

    /**
     * Creates a validated durable transfer position.
     *
     * @param transferId stable identity of the transfer configuration
     * @param sourceAdapter source adapter name
     * @param targetAdapter target adapter name
     * @param schemaFingerprint digest of the transfer schema
     * @param batchSize page size used by the transfer
     * @param entityIndex index of the next entity to read
     * @param cursor continuation token for the next source page, or {@code null} when starting an entity
     * @param counts records transferred per entity
     * @param complete whether all entities have been transferred
     * @param issues retained diagnostics from completed pages
     * @throws IllegalArgumentException if the transfer ID is blank, the position is invalid, or a count is negative
     * @throws NullPointerException if another required field or an entry in {@code counts} is {@code null}
     * @since 1.3.0
     */
    public TransferCheckpoint {
        if (transferId == null || transferId.isBlank()) throw new IllegalArgumentException("transferId is required");
        Objects.requireNonNull(sourceAdapter, "sourceAdapter");
        Objects.requireNonNull(targetAdapter, "targetAdapter");
        Objects.requireNonNull(schemaFingerprint, "schemaFingerprint");
        if (batchSize < 1 || entityIndex < 0) throw new IllegalArgumentException("Invalid checkpoint position");
        counts = Map.copyOf(counts);
        issues = List.copyOf(issues);
        if (counts.values().stream().anyMatch(count -> count == null || count < 0))
            throw new IllegalArgumentException("Invalid checkpoint count");
    }
}
