package eu.mikart.tava;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ValueCodecsTest {
    enum State { NEW, ACTIVE }

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
        assertEquals(List.of(true, 12L, new java.math.BigDecimal("-3.5"), "x"),
                ((Map<?, ?>) decoded).get("values"));
        assertNull(((Map<?, ?>) decoded).get("nothing"));
    }

    @Test
    void rejectsMalformedJsonAndValuesJsonCannotRepresent() {
        for (String malformed : List.of("", "01", "-01", "1.", "1e", "[1,]", "{\"x\":}",
                "\"unterminated", "\"raw\nline\"", "true false", "\"bad\\x\"")) {
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
}
