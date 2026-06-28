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
}
