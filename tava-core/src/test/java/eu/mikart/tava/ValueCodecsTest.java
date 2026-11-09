package eu.mikart.tava;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ValueCodecsTest {
    enum State {
        NEW,
        ACTIVE
    }

    enum Priority {
        LOW,
        MEDIUM,
        HIGH
    }

    private static final ValueCodec<String> STRING_CODEC = new ValueCodec<>() {
        public Object encode(String value) {
            return value;
        }

        public String decode(Object stored) {
            return stored == null ? null : stored.toString();
        }
    };

    @Test
    void composesEnumAndCollectionCodecsAcrossJsonStorage() {
        ValueCodec<Set<State>> codec = ValueCodecs.set(ValueCodecs.enumName(State.class));
        Object encoded = codec.encode(Set.of(State.NEW, State.ACTIVE));
        String json = ValueCodecs.toJson(encoded);
        assertEquals(Set.of(State.NEW, State.ACTIVE), codec.decode(json));
        assertEquals(List.of("NEW", "ACTIVE"), ValueCodecs.fromJson("[\"NEW\",\"ACTIVE\"]"));
    }

    @Test
    void roundTripsEverySupportedJsonShapeAndEscape() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "quote=\" slash=\\ line=\n tab=\t control=\u0001");
        value.put("values", List.of(true, 12, -3.50, "x"));
        value.put("nothing", null);

        Object decoded = ValueCodecs.fromJson(ValueCodecs.toJson(value));
        assertEquals(value.get("text"), ((Map<?, ?>) decoded).get("text"));
        assertEquals(List.of(true, 12L, new BigDecimal("-3.5"), "x"), ((Map<?, ?>) decoded).get("values"));
        assertNull(((Map<?, ?>) decoded).get("nothing"));
    }

    @Test
    void rejectsMalformedJsonAndValuesJsonCannotRepresent() {
        for (String malformed : List.of(
                "",
                "01",
                "-01",
                "1.",
                "1e",
                "[1,]",
                "{\"x\":}",
                "\"unterminated",
                "\"raw\nline\"",
                "true false",
                "\"bad\\x\"")) {
            assertThrows(TavaException.Mapping.class, () -> ValueCodecs.fromJson(malformed), malformed);
        }
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Double.NaN));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Double.POSITIVE_INFINITY));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(new Object()));
    }

    @Test
    void codecsHandleNullAndRejectNonCollections() {
        var codec = ValueCodecs.list(ValueCodecs.enumName(State.class));
        assertNull(codec.encode(null));
        assertNull(codec.decode(null));
        assertThrows(TavaException.Mapping.class, () -> codec.decode("{}"));
        assertThrows(NullPointerException.class, () -> ValueCodecs.enumName(null));
        assertThrows(NullPointerException.class, () -> ValueCodecs.list(null));
    }

    @Test
    void enumCodecsWithNullValues() {
        ValueCodec<State> enumCodec = ValueCodecs.enumName(State.class);
        assertNull(enumCodec.encode(null));
        assertNull(enumCodec.decode(null));

        Object encoded = enumCodec.encode(State.ACTIVE);
        assertEquals("ACTIVE", encoded);
        assertEquals(State.ACTIVE, enumCodec.decode("ACTIVE"));

        assertThrows(IllegalArgumentException.class, () -> enumCodec.decode("INVALID"));
    }

    @Test
    void collectionCodecsWithNestedCollections() {
        ValueCodec<List<State>> listCodec = ValueCodecs.list(ValueCodecs.enumName(State.class));
        List<State> values = List.of(State.NEW, State.ACTIVE);
        Object encoded = listCodec.encode(values);
        assertEquals(List.of("NEW", "ACTIVE"), encoded);

        List<State> decoded = listCodec.decode(encoded);
        assertEquals(values, decoded);

        ValueCodec<Set<State>> setCodec = ValueCodecs.set(ValueCodecs.enumName(State.class));
        Set<State> setValues = Set.of(State.NEW, State.ACTIVE);
        Object setEncoded = setCodec.encode(setValues);
        @SuppressWarnings("unchecked")
        List<String> encodedList = (List<String>) setEncoded;
        assertEquals(2, encodedList.size());
        assertTrue(encodedList.contains("NEW"));
        assertTrue(encodedList.contains("ACTIVE"));

        Set<State> setDecoded = setCodec.decode(setEncoded);
        assertEquals(setValues, setDecoded);
    }

    @Test
    void nestedCollectionCodecs() {
        ValueCodec<List<List<String>>> nestedListCodec = ValueCodecs.list(ValueCodecs.list(STRING_CODEC));
        List<List<String>> nested = List.of(List.of("a", "b"), List.of("c"));
        Object encoded = nestedListCodec.encode(nested);
        assertEquals(List.of(List.of("a", "b"), List.of("c")), encoded);

        List<List<String>> decoded = nestedListCodec.decode(encoded);
        assertEquals(nested, decoded);

        ValueCodec<Set<Set<String>>> nestedSetCodec = ValueCodecs.set(ValueCodecs.set(STRING_CODEC));
        Set<Set<String>> nestedSet = Set.of(Set.of("x", "y"), Set.of("z"));
        Object setEncoded = nestedSetCodec.encode(nestedSet);
        Set<Set<String>> setDecoded = nestedSetCodec.decode(setEncoded);
        assertEquals(nestedSet, setDecoded);
    }

    @Test
    void jsonEncodingDecodingSpecialCharacters() {
        String specialChars = "\b\f\n\r\t\"\\/\u0000\u001F\u007F";
        String json = ValueCodecs.toJson(specialChars);
        Object decoded = ValueCodecs.fromJson(json);
        assertEquals(specialChars, decoded);

        String emoji = "🎉🚀💻";
        String emojiJson = ValueCodecs.toJson(emoji);
        Object emojiDecoded = ValueCodecs.fromJson(emojiJson);
        assertEquals(emoji, emojiDecoded);

        String unicodeEscapes = "\uD83D\uDE00";
        String unicodeJson = ValueCodecs.toJson(unicodeEscapes);
        Object unicodeDecoded = ValueCodecs.fromJson(unicodeJson);
        assertEquals(unicodeEscapes, unicodeDecoded);

        Map<String, Object> complexMap = new LinkedHashMap<>();
        complexMap.put("newline", "line1\nline2");
        complexMap.put("tab", "col1\tcol2");
        complexMap.put("quote", "He said \"Hello\"");
        complexMap.put("backslash", "C:\\Users\\Path");
        complexMap.put("nullChar", "before\u0000after");
        String complexJson = ValueCodecs.toJson(complexMap);
        Map<?, ?> complexDecoded = (Map<?, ?>) ValueCodecs.fromJson(complexJson);
        assertEquals("line1\nline2", complexDecoded.get("newline"));
        assertEquals("col1\tcol2", complexDecoded.get("tab"));
        assertEquals("He said \"Hello\"", complexDecoded.get("quote"));
        assertEquals("C:\\Users\\Path", complexDecoded.get("backslash"));
        assertEquals("before\u0000after", complexDecoded.get("nullChar"));
    }

    @Test
    void codecCompositionEnumCollectionJson() {
        ValueCodec<List<State>> enumListCodec = ValueCodecs.list(ValueCodecs.enumName(State.class));
        List<State> states = List.of(State.NEW, State.ACTIVE);
        Object encoded = enumListCodec.encode(states);
        String json = ValueCodecs.toJson(encoded);

        List<State> decoded = enumListCodec.decode(ValueCodecs.fromJson(json));
        assertEquals(states, decoded);

        ValueCodec<Set<Priority>> enumSetCodec = ValueCodecs.set(ValueCodecs.enumName(Priority.class));
        Set<Priority> priorities = Set.of(Priority.LOW, Priority.HIGH);
        Object setEncoded = enumSetCodec.encode(priorities);
        String setJson = ValueCodecs.toJson(setEncoded);

        Set<Priority> setDecoded = enumSetCodec.decode(ValueCodecs.fromJson(setJson));
        assertEquals(priorities, setDecoded);

        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("states", states);
        combined.put("priorities", priorities);
        String combinedJson = ValueCodecs.toJson(combined);
        Map<?, ?> combinedDecoded = (Map<?, ?>) ValueCodecs.fromJson(combinedJson);
        assertEquals(List.of("NEW", "ACTIVE"), combinedDecoded.get("states"));
        @SuppressWarnings("unchecked")
        List<String> priorityList = (List<String>) combinedDecoded.get("priorities");
        assertEquals(2, priorityList.size());
        assertTrue(priorityList.contains("LOW"));
        assertTrue(priorityList.contains("HIGH"));
    }

    @Test
    void listCodecWithNullElements() {
        ValueCodec<List<String>> stringListCodec = ValueCodecs.list(STRING_CODEC);
        List<String> withNull = Arrays.asList("a", null, "b");
        Object encoded = stringListCodec.encode(withNull);
        assertEquals(Arrays.asList("a", null, "b"), encoded);

        List<String> decoded = stringListCodec.decode(encoded);
        assertEquals(withNull, decoded);
    }

    @Test
    void setCodecDeduplicatesStoredValues() {
        ValueCodec<Set<String>> stringSetCodec = ValueCodecs.set(STRING_CODEC);
        Set<String> decoded = stringSetCodec.decode(List.of("a", "a", "b"));
        assertEquals(Set.of("a", "b"), decoded);
    }

    @Test
    void emptyCollectionsEncodeDecode() {
        ValueCodec<List<String>> emptyListCodec = ValueCodecs.list(STRING_CODEC);
        Object emptyListEncoded = emptyListCodec.encode(List.of());
        assertEquals(List.of(), emptyListEncoded);
        assertEquals(List.of(), emptyListCodec.decode(emptyListEncoded));

        ValueCodec<Set<String>> emptySetCodec = ValueCodecs.set(STRING_CODEC);
        Object emptySetEncoded = emptySetCodec.encode(Set.of());
        assertEquals(List.of(), emptySetEncoded);
        assertEquals(Set.of(), emptySetCodec.decode(emptySetEncoded));
    }

    @Test
    void jsonNumbersPrecision() {
        Object decoded = ValueCodecs.fromJson("123");
        assertEquals(123L, decoded);

        decoded = ValueCodecs.fromJson("123.456");
        assertEquals(new BigDecimal("123.456"), decoded);

        decoded = ValueCodecs.fromJson("-42");
        assertEquals(-42L, decoded);

        decoded = ValueCodecs.fromJson("0");
        assertEquals(0L, decoded);

        decoded = ValueCodecs.fromJson("1e10");
        assertEquals(10000000000L, decoded);

        decoded = ValueCodecs.fromJson("1.5e-3");
        assertEquals(new BigDecimal("1.5E-3"), decoded);
    }

    @Test
    void jsonBooleanAndNull() {
        assertEquals(true, ValueCodecs.fromJson("true"));
        assertEquals(false, ValueCodecs.fromJson("false"));
        assertNull(ValueCodecs.fromJson("null"));
    }

    @Test
    void jsonArraysAndObjects() {
        List<?> array = (List<?>) ValueCodecs.fromJson("[1, \"two\", true, null, [3, 4]]");
        assertEquals(5, array.size());
        assertEquals(1L, array.get(0));
        assertEquals("two", array.get(1));
        assertEquals(true, array.get(2));
        assertNull(array.get(3));
        assertEquals(List.of(3L, 4L), array.get(4));

        Map<?, ?> object = (Map<?, ?>) ValueCodecs.fromJson("{\"a\": 1, \"b\": {\"c\": 2}}");
        assertEquals(1L, object.get("a"));
        assertEquals(Map.of("c", 2L), object.get("b"));
    }

    @Test
    void rejectsInvalidEnumClass() {
        assertThrows(NullPointerException.class, () -> ValueCodecs.enumName((Class<State>) null));
    }

    @Test
    void rejectsInvalidElementCodec() {
        assertThrows(NullPointerException.class, () -> ValueCodecs.list(null));
        assertThrows(NullPointerException.class, () -> ValueCodecs.set(null));
    }

    @Test
    void toJsonRejectsNonFiniteNumbers() {
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Double.NaN));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Double.POSITIVE_INFINITY));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Double.NEGATIVE_INFINITY));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Float.NaN));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Float.POSITIVE_INFINITY));
    }

    @Test
    void toJsonRejectsUnsupportedTypes() {
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(new Object()));
        assertThrows(TavaException.Mapping.class, () -> ValueCodecs.toJson(Thread.currentThread()));
    }
}
