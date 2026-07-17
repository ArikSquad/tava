package eu.mikart.tava.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryValidationTest {
    @Test
    void rejectsInvalidSorts() {
        assertThrows(IllegalArgumentException.class, () -> Sort.asc(" "));
        assertThrows(IllegalArgumentException.class, () -> new Sort("name", null));
    }

    @Test
    void rejectsEmptyPredicatesWithAmbiguousNativeSemantics() {
        assertThrows(IllegalArgumentException.class, () -> Predicate.in("id", List.of()));
        assertThrows(IllegalArgumentException.class, Predicate::and);
        assertThrows(IllegalArgumentException.class, Predicate::or);
    }
}
