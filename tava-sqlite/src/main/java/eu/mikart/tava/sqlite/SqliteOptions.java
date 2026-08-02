package eu.mikart.tava.sqlite;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Objects;

/** Connection pragmas applied to every SQLite connection opened by an adapter. */
public record SqliteOptions(boolean foreignKeys, @NotNull Synchronous synchronous,
                            @NotNull JournalMode journalMode, @NotNull Duration busyTimeout) {
    public enum Synchronous { OFF, NORMAL, FULL, EXTRA }
    public enum JournalMode { DELETE, TRUNCATE, PERSIST, MEMORY, WAL, OFF }

    public SqliteOptions {
        synchronous = Objects.requireNonNull(synchronous, "synchronous");
        journalMode = Objects.requireNonNull(journalMode, "journalMode");
        busyTimeout = Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isNegative()) throw new IllegalArgumentException("busyTimeout must not be negative");
        if (busyTimeout.toMillis() > Integer.MAX_VALUE) throw new IllegalArgumentException("busyTimeout is too large");
    }

    public static @NotNull SqliteOptions defaults() { return builder().build(); }
    public static @NotNull Builder builder() { return new Builder(); }

    public static final class Builder {
        private boolean foreignKeys = true;
        private Synchronous synchronous = Synchronous.NORMAL;
        private JournalMode journalMode = JournalMode.WAL;
        private Duration busyTimeout = Duration.ofSeconds(5);
        public Builder foreignKeys(boolean value) { foreignKeys = value; return this; }
        public Builder synchronous(@NotNull Synchronous value) { synchronous = Objects.requireNonNull(value); return this; }
        public Builder journalMode(@NotNull JournalMode value) { journalMode = Objects.requireNonNull(value); return this; }
        public Builder busyTimeout(@NotNull Duration value) { busyTimeout = Objects.requireNonNull(value); return this; }
        public SqliteOptions build() { return new SqliteOptions(foreignKeys, synchronous, journalMode, busyTimeout); }
    }
}
