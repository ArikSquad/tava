package eu.mikart.tava.query;

import java.util.List;

/**
 * Portable access-path description; this is not a database optimizer execution plan.
 *
 * @param accessPath access method chosen by the adapter
 * @param ordering effective ordering, including appended identity fields
 * @param scan whether the adapter explicitly chooses a table scan; optimizer choices require native explain
 * @param detail limitations or backend planning information
 * @since 1.3.0
 */
public record QueryDiagnostics(String accessPath, List<Sort> ordering, boolean scan, String detail) {
    /**
     * Copies ordering metadata.
     *
     * @param accessPath selected access method
     * @param ordering effective order
     * @param scan table scan indicator
     * @param detail planning detail
     * @since 1.3.0
     */
    public QueryDiagnostics {
        ordering = List.copyOf(ordering);
    }
}
