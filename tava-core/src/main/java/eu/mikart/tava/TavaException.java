package eu.mikart.tava;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base exception for failures reported by the portable Tava API. */
public class TavaException extends RuntimeException {
    /**
     * Creates a portable API failure.
     *
     * @param message explanation of the failure
     */
    public TavaException(final @NotNull String message) {
        super(message);
    }

    /**
     * Creates a portable API failure with its underlying cause.
     *
     * @param message explanation of the failure
     * @param cause underlying failure, if any
     */
    public TavaException(final @NotNull String message, final @Nullable Throwable cause) {
        super(message, cause);
    }

    /** The adapter cannot provide the requested feature or operation. */
    public static final class Capability extends TavaException {
        /**
         * Creates a capability failure.
         *
         * @param message explanation of the unsupported operation
         */
        public Capability(final @NotNull String message) {
            super(message);
        }

        /**
         * Creates a capability failure with its driver or adapter cause.
         *
         * @param message explanation of the unsupported operation
         * @param cause underlying failure, if any
         * @since 1.3.0
         */
        public Capability(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** A schema definition or schema operation is invalid or unsupported. */
    public static final class Schema extends TavaException {
        /**
         * Creates a schema failure.
         *
         * @param message explanation of the failure
         */
        public Schema(final @NotNull String message) {
            super(message);
        }

        /**
         * Creates a schema failure with its underlying cause.
         *
         * @param message explanation of the failure
         * @param cause underlying failure, if any
         */
        public Schema(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** A storage operation failed while reading or writing data. */
    public static final class Data extends TavaException {
        /**
         * Creates a data failure with its underlying cause.
         *
         * @param message explanation of the failure
         * @param cause underlying failure, if any
         */
        public Data(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** A unique-key, optimistic, or conditional write conflict. */
    public static final class Conflict extends TavaException {
        /**
         * Creates a write conflict.
         *
         * @param message explanation of the conflict
         * @param cause underlying failure, if any
         */
        public Conflict(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** Connection acquisition, timeout, or transport failure. */
    public static final class Connection extends TavaException {
        /**
         * Creates a connection failure.
         *
         * @param message explanation of the failure
         * @param cause underlying failure, if any
         */
        public Connection(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** Transaction callback, commit, or rollback failure. */
    public static final class Transaction extends TavaException {
        /**
         * Creates a transaction failure.
         *
         * @param message explanation of the failure
         * @param cause underlying failure, if any
         */
        public Transaction(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    /** Invalid user input or an unmappable value. */
    public static final class Mapping extends TavaException {
        /**
         * Creates a mapping failure.
         *
         * @param message explanation of the failure
         * @param cause underlying failure, if any
         */
        public Mapping(final @NotNull String message, final @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
