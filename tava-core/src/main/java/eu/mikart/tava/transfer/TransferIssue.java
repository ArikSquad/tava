package eu.mikart.tava.transfer;

/**
 * Describes an issue found while transferring an entity's data.
 *
 * @param entity affected entity name
 * @param field affected field name, or {@code null} when the issue applies to the whole entity
 * @param severity issue severity
 * @param detail explanatory message
 */
public record TransferIssue(String entity, String field, Severity severity, String detail) {
    /** Severity assigned to a transfer issue. */
    public enum Severity {
        /** A non-fatal issue that does not necessarily lose data. */
        WARNING,
        /** Some source data may be omitted from the target record. */
        LOSSY,
        /** The transfer could not continue past this issue. */
        ERROR
    }
}
