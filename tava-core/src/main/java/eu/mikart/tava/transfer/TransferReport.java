package eu.mikart.tava.transfer;

import java.util.List;
import java.util.Map;

public record TransferReport(Map<String, Long> transferred, List<TransferIssue> issues, boolean complete) {
    public TransferReport {
        transferred = Map.copyOf(transferred);
        issues = List.copyOf(issues);
    }
}
