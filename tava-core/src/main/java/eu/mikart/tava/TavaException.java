package eu.mikart.tava;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TavaException extends RuntimeException {
    public TavaException(final @NotNull String message) {
        super(message);
    }

    public TavaException(final @NotNull String message, final @Nullable Throwable cause) {
        super(message, cause);
    }

    public static final class Capability extends TavaException {
        public Capability(final @NotNull String message) {
            super(message);
        }
    }

    public static final class Schema extends TavaException {
        public Schema(final @NotNull String message) {
            super(message);
        }

        public Schema(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Data extends TavaException {
        public Data(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** A constraint or optimistic/conditional write conflict. */
    public static final class Conflict extends TavaException {
        public Conflict(final @NotNull String message, final @Nullable Throwable cause) { super(message, cause); }
    }

    /** Connection acquisition, timeout, or transport failure. */
    public static final class Connection extends TavaException {
        public Connection(final @NotNull String message, final @Nullable Throwable cause) { super(message, cause); }
    }

    /** Transaction callback, commit, or rollback failure. */
    public static final class Transaction extends TavaException {
        public Transaction(final @NotNull String message, final @Nullable Throwable cause) { super(message, cause); }
    }

    /** Invalid user input or an unmappable value. */
    public static final class Mapping extends TavaException {
        public Mapping(final @NotNull String message, final @Nullable Throwable cause) { super(message, cause); }
    }
}
