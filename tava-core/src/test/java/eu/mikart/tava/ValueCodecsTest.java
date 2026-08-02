package eu.mikart.tava;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
