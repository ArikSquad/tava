package eu.mikart.tava.transfer;

import java.util.List;
import java.util.Map;

/**
 * Counts, issues, and completion state from a data transfer.
 *
 * @param transferred records transferred per entity
 * @param issues issues encountered during transfer
 * @param complete whether all requested entities have been transferred
 */
public record TransferReport(Map<String, Long> transferred, List<TransferIssue> issues, boolean complete) {
    /**
     * Creates an immutable transfer report.
     *
     * @param transferred records transferred per entity
     * @param issues issues encountered during transfer
     * @param complete whether all requested entities have been transferred
     * @throws NullPointerException if either collection or any of its entries is {@code null}
     */
    public TransferReport {
        transferred = Map.copyOf(transferred);
        issues = List.copyOf(issues);
    }
}
