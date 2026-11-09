package eu.mikart.tava.spi;

import eu.mikart.tava.TavaException;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.query.Sort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Encodes typed continuation values in opaque, query-bound cursors.
 *
 * @since 1.3.0
 */
public final class KeysetCursor {
    private static final int MAX_TOKEN_LENGTH = 65536;

    private KeysetCursor() {}

    /**
     * Encodes a page position for a particular query and adapter context.
     *
     * @param context adapter and entity identity
     * @param query query whose result is being paged
     * @param order complete effective ordering
     * @param values ordered position values
     * @return opaque continuation token
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the number of values does not match the order
     * @throws TavaException.Capability if a value cannot be encoded or the token exceeds the size limit
     * @since 1.3.0
     */
    public static String encode(String context, Query query, List<Sort> order, List<?> values) {
        if (order.size() != values.size()) throw new IllegalArgumentException("Invalid continuation values");
        List<Object> encoded = values.stream().map(KeysetCursor::value).toList();
        String json = ValueCodecs.toJson(List.of("1", signature(context, query, order), encoded));
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        if (token.length() > MAX_TOKEN_LENGTH)
            throw new TavaException.Capability("Continuation values exceed cursor size limit");
        return token;
    }

    /**
     * Decodes a continuation token after verifying its query and adapter context.
     *
     * @param token opaque token from the prior page
     * @param context adapter and entity identity
     * @param query query whose result is being paged
     * @param order complete effective ordering
     * @return ordered position values
     * @throws IllegalArgumentException if the token is {@code null}, invalid, or does not match the query and context
     * @since 1.3.0
     */
    public static List<Object> decode(String token, String context, Query query, List<Sort> order) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH) throw new IllegalArgumentException("Invalid cursor");
        try {
            Object parsed =
                    ValueCodecs.fromJson(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> root)
                    || root.size() != 3
                    || !"1".equals(root.get(0))
                    || !signature(context, query, order).equals(root.get(1))
                    || !(root.get(2) instanceof List<?> entries)
                    || entries.size() != order.size())
                throw new IllegalArgumentException("Cursor does not match query");
            List<Object> values = new ArrayList<>();
            for (Object entry : entries) values.add(readValue(entry));
            return Collections.unmodifiableList(values);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid cursor", failure);
        }
    }

    private static String signature(String context, Query query, List<Sort> order) {
        List<Object> sorting = order.stream()
                .<Object>map(sort -> List.of(sort.field(), sort.direction().name()))
                .toList();
        String input = ValueCodecs.toJson(
                List.of(context, predicate(query.predicate()), query.projection(), query.limit(), sorting));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object predicate(Predicate predicate) {
        if (predicate instanceof Predicate.All) return List.of("all");
        if (predicate instanceof Predicate.Comparison comparison)
            return List.of(
                    "comparison", comparison.field(), comparison.operator().name(), value(comparison.value()));
        Predicate.Junction junction = (Predicate.Junction) predicate;
        return List.of(
                junction.and() ? "and" : "or",
                junction.predicates().stream().map(KeysetCursor::predicate).toList());
    }

    private static Object value(Object input) {
        if (input == null) return List.of("null");
        if (input instanceof String text) return List.of("string", text);
        if (input instanceof Enum<?> item) return List.of("string", item.name());
        if (input instanceof Boolean bool) return List.of("boolean", bool.toString());
        if (input instanceof Byte || input instanceof Short || input instanceof Integer)
            return List.of("int", input.toString());
        if (input instanceof Long) return List.of("long", input.toString());
        if (input instanceof BigDecimal) return List.of("decimal", input.toString());
        if (input instanceof Float || input instanceof Double) return List.of("double", input.toString());
        if (input instanceof UUID) return List.of("uuid", input.toString());
        if (input instanceof byte[] bytes)
            return List.of("binary", Base64.getEncoder().encodeToString(bytes));
        if (input instanceof Timestamp timestamp)
            return List.of("timestamp", timestamp.toInstant().toString());
        if (input instanceof Date date)
            return List.of("date", date.toLocalDate().toString());
        if (input instanceof Instant instant) return List.of("instant", instant.toString());
        if (input instanceof OffsetDateTime date) return List.of("offset", date.toString());
        if (input instanceof LocalDate date) return List.of("date", date.toString());
        if (input instanceof LocalDateTime date) return List.of("datetime", date.toString());
        if (input instanceof Collection<?> values)
            return List.of("list", values.stream().map(KeysetCursor::value).toList());
        if (input instanceof Map<?, ?> map) {
            SortedMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), value(item)));
            return List.of(
                    "map",
                    sorted.entrySet().stream()
                            .map(item -> List.of(item.getKey(), item.getValue()))
                            .toList());
        }
        throw new TavaException.Capability(
                "Unsupported cursor value type: " + input.getClass().getName());
    }

    private static Object readValue(Object input) {
        if (!(input instanceof List<?> pair) || pair.isEmpty() || !(pair.getFirst() instanceof String type))
            throw new IllegalArgumentException("Invalid cursor value");
        if (type.equals("null") && pair.size() == 1) return null;
        if (pair.size() != 2) throw new IllegalArgumentException("Invalid cursor value");
        if (type.equals("list")) {
            if (!(pair.get(1) instanceof List<?> entries)) throw new IllegalArgumentException("Invalid cursor list");
            return entries.stream().map(KeysetCursor::readValue).toList();
        }
        if (type.equals("map")) {
            if (!(pair.get(1) instanceof List<?> entries)) throw new IllegalArgumentException("Invalid cursor map");
            Map<String, Object> values = new LinkedHashMap<>();
            for (Object entry : entries) {
                if (!(entry instanceof List<?> item)
                        || item.size() != 2
                        || !(item.getFirst() instanceof String key)
                        || values.containsKey(key)) throw new IllegalArgumentException("Invalid cursor map entry");
                values.put(key, readValue(item.get(1)));
            }
            return Collections.unmodifiableMap(values);
        }
        if (!(pair.get(1) instanceof String text)) throw new IllegalArgumentException("Invalid cursor value");
        return switch (type) {
            case "string" -> text;
            case "boolean" -> {
                if (!text.equals("true") && !text.equals("false"))
                    throw new IllegalArgumentException("Invalid boolean");
                yield Boolean.valueOf(text);
            }
            case "int" -> Integer.valueOf(text);
            case "long" -> Long.valueOf(text);
            case "decimal" -> new BigDecimal(text);
            case "double" -> Double.valueOf(text);
            case "uuid" -> UUID.fromString(text);
            case "binary" -> Base64.getDecoder().decode(text);
            case "timestamp" -> Timestamp.from(Instant.parse(text));
            case "instant" -> Instant.parse(text);
            case "offset" -> OffsetDateTime.parse(text);
            case "date" -> LocalDate.parse(text);
            case "datetime" -> LocalDateTime.parse(text);
            default -> throw new IllegalArgumentException("Unsupported cursor value");
        };
    }
}
