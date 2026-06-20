package eu.mikart.tava;

public class TavaException extends RuntimeException {
    public TavaException(String message) {
        super(message);
    }

    public TavaException(String message, Throwable cause) {
        super(message, cause);
    }

    public static final class Capability extends TavaException {
        public Capability(String message) {
            super(message);
        }
    }

    public static final class Schema extends TavaException {
        public Schema(String message) {
            super(message);
        }

        public Schema(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class Data extends TavaException {
        public Data(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
