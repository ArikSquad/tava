package eu.mikart.tava;

import java.math.BigDecimal;
import java.util.*;
import org.jetbrains.annotations.NotNull;

/** Built-in codecs and the JSON representation used for JDBC collection/document values. */
public final class ValueCodecs {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ValueCodecs() {}

    /**
     * Creates a codec that stores enum constants by name.
     *
     * @param type enum class to encode and decode
     * @param <E> enum type
     * @return codec that encodes names and decodes them to enum constants
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static <E extends Enum<E>> @NotNull ValueCodec<E> enumName(final @NotNull Class<E> type) {
        Objects.requireNonNull(type, "type");
        return new ValueCodec<>() {
            public Object encode(E value) {
                return value == null ? null : value.name();
            }

            public E decode(Object stored) {
                return stored == null ? null : Enum.valueOf(type, stored.toString());
            }
        };
    }

    /**
     * Creates a codec for lists using the supplied element codec.
     *
     * @param elementCodec codec for list elements
     * @param <T> element type
     * @return codec that encodes and decodes lists of values
     * @throws NullPointerException if {@code elementCodec} is {@code null}
     */
    public static <T> @NotNull ValueCodec<List<T>> list(final @NotNull ValueCodec<T> elementCodec) {
        Objects.requireNonNull(elementCodec, "elementCodec");
        return new ValueCodec<>() {
            public Object encode(List<T> value) {
                return value == null
                        ? null
                        : value.stream().map(elementCodec::encode).toList();
            }

            public List<T> decode(Object stored) {
                if (stored == null) return null;
                Object value = stored instanceof String text ? fromJson(text) : stored;
                if (!(value instanceof Collection<?> collection))
                    throw new TavaException.Mapping("Expected a collection", null);
                return collection.stream().map(elementCodec::decode).toList();
            }
        };
    }

    /**
     * Creates a codec for sets using the supplied element codec.
     *
     * @param elementCodec codec for set elements
     * @param <T> element type
     * @return codec that decodes values into an unmodifiable insertion-ordered set
     * @throws NullPointerException if {@code elementCodec} is {@code null}
     */
    public static <T> @NotNull ValueCodec<Set<T>> set(final @NotNull ValueCodec<T> elementCodec) {
        ValueCodec<List<T>> lists = list(elementCodec);
        return new ValueCodec<>() {
            public Object encode(Set<T> value) {
                return value == null ? null : lists.encode(new ArrayList<>(value));
            }

            public Set<T> decode(Object stored) {
                List<T> values = lists.decode(stored);
                return values == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(values));
            }
        };
    }

    /**
     * Encodes a value as JSON text.
     *
     * @param value value to encode
     * @return JSON representation
     * @throws TavaException.Mapping if the value cannot be represented as JSON
     */
    public static @NotNull String toJson(final Object value) {
        StringBuilder output = new StringBuilder();
        writeJson(output, value);
        return output.toString();
    }

    /**
     * Parses JSON text into Java values.
     *
     * <p>Nesting is limited to 128 values. Duplicate object keys are rejected.
     *
     * @param json JSON text to parse
     * @return parsed value, represented by maps, lists, strings, booleans, numbers, or {@code null}
     * @throws TavaException.Mapping if {@code json} is not valid JSON
     * @throws NullPointerException if {@code json} is {@code null}
     */
    public static Object fromJson(final @NotNull String json) {
        return new JsonParser(json).parse();
    }

    private static void writeJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String
                || value instanceof Character
                || value instanceof UUID
                || value instanceof Enum<?>) {
            quote(out, value instanceof Enum<?> e ? e.name() : value.toString());
            return;
        }
        if (value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Number number) {
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new TavaException.Mapping("JSON does not support non-finite numbers", null);
            }
            out.append(number);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeJson(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof Collection<?> collection) {
            out.append('[');
            boolean first = true;
            for (Object element : collection) {
                if (!first) out.append(',');
                first = false;
                writeJson(out, element);
            }
            out.append(']');
            return;
        }
        throw new TavaException.Mapping(
                "Cannot encode JSON value " + value.getClass().getName(), null);
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        out.append(HEX[(c >>> 12) & 0xF]);
                        out.append(HEX[(c >>> 8) & 0xF]);
                        out.append(HEX[(c >>> 4) & 0xF]);
                        out.append(HEX[c & 0xF]);
                    } else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class JsonParser {
        private final String text;
        private int offset;
        private int depth;

        private JsonParser(String text) {
            this.text = Objects.requireNonNull(text, "json");
        }

        private Object parse() {
            Object value = value();
            whitespace();
            if (offset != text.length()) fail();
            return value;
        }

        private Object value() {
            whitespace();
            if (offset >= text.length()) return fail();
            if (++depth > 128) return fail();
            try {
                return switch (text.charAt(offset)) {
                    case '"' -> string();
                    case '[' -> array();
                    case '{' -> object();
                    case 't' -> literal("true", true);
                    case 'f' -> literal("false", false);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            } finally {
                depth--;
            }
        }

        private List<Object> array() {
            offset++;
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            do {
                result.add(value());
                whitespace();
            } while (take(','));
            if (!take(']')) fail();
            return result;
        }

        private Map<String, Object> object() {
            offset++;
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                if (offset >= text.length() || text.charAt(offset) != '"') fail();
                String key = string();
                whitespace();
                if (!take(':')) fail();
                if (result.containsKey(key)) fail();
                result.put(key, value());
                whitespace();
            } while (take(','));
            if (!take('}')) fail();
            return result;
        }

        private String string() {
            offset++;
            StringBuilder result = new StringBuilder();
            while (offset < text.length()) {
                char c = text.charAt(offset++);
                if (c == '"') return result.toString();
                if (c != '\\') {
                    if (c < 0x20) fail();
                    result.append(c);
                    continue;
                }
                if (offset >= text.length()) fail();
                char escape = text.charAt(offset++);
                result.append(
                        switch (escape) {
                            case '"', '\\', '/' -> escape;
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'u' -> {
                                if (offset + 4 > text.length()) yield (char) fail();
                                try {
                                    char decoded = (char) Integer.parseInt(text.substring(offset, offset + 4), 16);
                                    offset += 4;
                                    yield decoded;
                                } catch (NumberFormatException e) {
                                    yield (char) fail();
                                }
                            }
                            default -> (char) fail();
                        });
            }
            return (String) fail();
        }

        private Object number() {
            int start = offset;
            if (take('-')) {}
            if (take('0')) {
                if (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9') return fail();
            } else {
                int integerStart = offset;
                while (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9') offset++;
                if (integerStart == offset) return fail();
            }
            if (take('.')) {
                int fractionStart = offset;
                while (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9') offset++;
                if (fractionStart == offset) return fail();
            }
            if (offset < text.length() && (text.charAt(offset) == 'e' || text.charAt(offset) == 'E')) {
                offset++;
                if (offset < text.length() && (text.charAt(offset) == '+' || text.charAt(offset) == '-')) offset++;
                int exponentStart = offset;
                while (offset < text.length() && text.charAt(offset) >= '0' && text.charAt(offset) <= '9') offset++;
                if (exponentStart == offset) return fail();
            }
            if (start == offset) return fail();
            try {
                BigDecimal value = new BigDecimal(text.substring(start, offset));
                return value.scale() <= 0 && value.precision() < 19 ? value.longValueExact() : value;
            } catch (ArithmeticException | NumberFormatException e) {
                return fail();
            }
        }

        private Object literal(String token, Object result) {
            if (!text.startsWith(token, offset)) return fail();
            offset += token.length();
            return result;
        }

        private boolean take(char expected) {
            if (offset < text.length() && text.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void whitespace() {
            while (offset < text.length()) {
                char next = text.charAt(offset);
                if (next != ' ' && next != '\t' && next != '\r' && next != '\n') break;
                offset++;
            }
        }

        private <T> T fail() {
            throw new TavaException.Mapping("Invalid JSON at offset " + offset, null);
        }
    }
}
