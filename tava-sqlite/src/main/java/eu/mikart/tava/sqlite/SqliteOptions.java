package eu.mikart.tava.sqlite;

import java.time.Duration;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Connection PRAGMA settings applied to every SQLite connection opened by an adapter.
 *
 * @param foreignKeys whether SQLite foreign-key enforcement is enabled
 * @param synchronous SQLite synchronization mode
 * @param journalMode SQLite journal mode
 * @param busyTimeout maximum time to wait for a busy database lock
 */
public record SqliteOptions(
        boolean foreignKeys,
        @NotNull Synchronous synchronous,
        @NotNull JournalMode journalMode,
        @NotNull Duration busyTimeout) {
    /** Values for SQLite's {@code synchronous} PRAGMA. */
    public enum Synchronous {
        /** Disable synchronization of database changes to durable storage. */
        OFF,
        /** Use SQLite's {@code NORMAL} synchronization mode. */
        NORMAL,
        /** Use SQLite's {@code FULL} synchronization mode. */
        FULL,
        /** Use SQLite's {@code EXTRA} synchronization mode. */
        EXTRA
    }

    /** Values for SQLite's {@code journal_mode} PRAGMA. */
    public enum JournalMode {
        /** Use the {@code DELETE} journal mode. */
        DELETE,
        /** Use the {@code TRUNCATE} journal mode. */
        TRUNCATE,
        /** Use the {@code PERSIST} journal mode. */
        PERSIST,
        /** Use the {@code MEMORY} journal mode. */
        MEMORY,
        /** Use the {@code WAL} journal mode. */
        WAL,
        /** Disable the rollback journal. */
        OFF
    }

    /**
     * Creates validated SQLite connection options.
     *
     * @param foreignKeys whether SQLite foreign-key enforcement is enabled
     * @param synchronous SQLite synchronization mode
     * @param journalMode SQLite journal mode
     * @param busyTimeout maximum time to wait for a busy database lock
     * @throws NullPointerException if a required option is {@code null}
     * @throws IllegalArgumentException if {@code busyTimeout} is negative or too large
     * @throws ArithmeticException if {@code busyTimeout} cannot be represented in milliseconds
     */
    public SqliteOptions {
        synchronous = Objects.requireNonNull(synchronous, "synchronous");
        journalMode = Objects.requireNonNull(journalMode, "journalMode");
        busyTimeout = Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative()) throw new IllegalArgumentException("busyTimeout must not be negative");
        if (busyTimeout.toMillis() > Integer.MAX_VALUE) throw new IllegalArgumentException("busyTimeout is too large");
    }

    /**
     * Returns the default connection options: foreign keys enabled, {@code NORMAL} synchronization, {@code WAL}
     * journaling, and a five-second busy timeout.
     *
     * @return default SQLite connection options
     */
    public static @NotNull SqliteOptions defaults() {
        return builder().build();
    }

    /**
     * Creates a builder initialized with the default connection options.
     *
     * @return a new options builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Builds {@link SqliteOptions} values with selected connection PRAGMAs. */
    public static final class Builder {
        private boolean foreignKeys = true;
        private Synchronous synchronous = Synchronous.NORMAL;
        private JournalMode journalMode = JournalMode.WAL;
        private Duration busyTimeout = Duration.ofSeconds(5);

        /** Creates a builder initialized with the default option values. */
        public Builder() {}

        /**
         * Enables or disables SQLite foreign-key enforcement.
         *
         * @param value whether to enable foreign keys
         * @return this builder
         */
        public Builder foreignKeys(boolean value) {
            foreignKeys = value;
            return this;
        }

        /**
         * Sets SQLite's synchronization mode.
         *
         * @param value synchronization mode
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder synchronous(@NotNull Synchronous value) {
            synchronous = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Sets SQLite's journal mode.
         *
         * @param value journal mode
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder journalMode(@NotNull JournalMode value) {
            journalMode = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Sets the maximum time SQLite waits for a busy database lock.
         *
         * @param value timeout duration
         * @return this builder
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public Builder busyTimeout(@NotNull Duration value) {
            busyTimeout = Objects.requireNonNull(value);
            return this;
        }

        /**
         * Creates options using the values set on this builder.
         *
         * @return validated SQLite connection options
         * @throws IllegalArgumentException if the timeout is negative or too large
         */
        public SqliteOptions build() {
            return new SqliteOptions(foreignKeys, synchronous, journalMode, busyTimeout);
        }
    }
}
