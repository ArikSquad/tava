package eu.mikart.tava.transfer;

public record TransferIssue(String entity, String field, Severity severity, String detail) {
    public enum Severity {
        WARNING,
        LOSSY,
        ERROR
    }
}
