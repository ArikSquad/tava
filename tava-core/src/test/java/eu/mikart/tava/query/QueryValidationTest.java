package eu.mikart.tava.query;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QueryValidationTest {
    private static final Predicate.Operator IS_NULL = Predicate.Operator.IS_NULL;
    private static final Predicate.Operator IS_NOT_NULL = Predicate.Operator.IS_NOT_NULL;
    private static final Predicate.Operator IN = Predicate.Operator.IN;
    private static final Predicate.Operator CONTAINS = Predicate.Operator.CONTAINS;
    private static final Predicate.Operator STARTS_WITH = Predicate.Operator.STARTS_WITH;
    private static final Predicate.Operator EQ = Predicate.Operator.EQ;
    private static final Predicate.Operator NE = Predicate.Operator.NE;
    private static final Predicate.Operator LT = Predicate.Operator.LT;
    private static final Predicate.Operator GTE = Predicate.Operator.GTE;
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

    @Test
    void predicateEdgeCases() {
        Predicate isNull = Predicate.isNull("field");
        assertEquals(IS_NULL, ((Predicate.Comparison) isNull).operator());
        assertNull(((Predicate.Comparison) isNull).value());

        Predicate isNotNull = Predicate.isNotNull("field");
        assertEquals(IS_NOT_NULL, ((Predicate.Comparison) isNotNull).operator());
        assertNull(((Predicate.Comparison) isNotNull).value());

        Predicate inSingle = Predicate.in("id", List.of("single"));
        assertEquals(IN, ((Predicate.Comparison) inSingle).operator());
        assertEquals(List.of("single"), ((Predicate.Comparison) inSingle).value());

        Predicate containsEmpty = Predicate.contains("field", "");
        assertEquals(CONTAINS, ((Predicate.Comparison) containsEmpty).operator());
        assertEquals("", ((Predicate.Comparison) containsEmpty).value());

        Predicate startsWithEmpty = Predicate.startsWith("field", "");
        assertEquals(STARTS_WITH, ((Predicate.Comparison) startsWithEmpty).operator());
        assertEquals("", ((Predicate.Comparison) startsWithEmpty).value());

        Predicate isNullWithInt = Predicate.isNull("intField");
        Predicate isNullWithString = Predicate.isNull("stringField");
        Predicate isNullWithBool = Predicate.isNull("boolField");
        assertNotNull(isNullWithInt);
        assertNotNull(isNullWithString);
        assertNotNull(isNullWithBool);

        Predicate inWithSet = Predicate.in("id", Set.of("a", "b"));
        assertEquals(2, ((List<?>) ((Predicate.Comparison) inWithSet).value()).size());
    }

    @Test
    void sortEdgeCases() {
        Sort sort1 = Sort.asc("field1");
        Sort sort2 = Sort.desc("field2");
        Sort sort3 = Sort.asc("field3");

        Query query = Query.builder()
                .sort(sort1, sort2, sort3)
                .build();
        assertEquals(3, query.sorting().size());
        assertEquals(Sort.Direction.ASC, query.sorting().get(0).direction());
        assertEquals(Sort.Direction.DESC, query.sorting().get(1).direction());
        assertEquals(Sort.Direction.ASC, query.sorting().get(2).direction());

        assertThrows(IllegalArgumentException.class, () -> new Sort(null, Sort.Direction.ASC));
        assertThrows(IllegalArgumentException.class, () -> new Sort("", Sort.Direction.ASC));
        assertThrows(IllegalArgumentException.class, () -> new Sort("  ", Sort.Direction.ASC));
    }

    @Test
    void queryBuilderEdgeCases() {
        Query queryLimitZero = Query.builder().limit(0).build();
        assertEquals(0, queryLimitZero.limit());

        Query queryWithCursorAndLimit = Query.builder().limit(10).cursor("cursor123").build();
        assertEquals(10, queryWithCursorAndLimit.limit());
        assertEquals("cursor123", queryWithCursorAndLimit.cursor());

        Query queryEmptyProjection = Query.builder().project().build();
        assertTrue(queryEmptyProjection.projection().isEmpty());

        Query queryNoProjection = Query.builder().build();
        assertTrue(queryNoProjection.projection().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> Query.builder().limit(-1).build());

        Query allQuery = Query.all();
        assertEquals(Predicate.all(), allQuery.predicate());
        assertEquals(0, allQuery.limit());
        assertNull(allQuery.cursor());
    }

    @Test
    void mutationEdgeCases() {
        Mutation mutation = Mutation.builder()
                .set("field", null)
                .build();
        assertNull(mutation.values().get("field"));

        Mutation increment = Mutation.builder()
                .increment(new FieldRef<>("count", Integer.class), 5)
                .build();
        assertEquals(1, increment.operations().size());
        assertEquals(Mutation.Kind.INCREMENT, increment.operations().get("count").kind());
        assertEquals(5, increment.operations().get("count").value());

        assertThrows(NullPointerException.class, () -> Mutation.builder()
                .increment(new FieldRef<>("count", Integer.class), null));

        FieldRef<Collection<String>> tags = stringCollectionField("tags");
        Mutation appendIfAbsent = Mutation.builder()
                .appendIfAbsent(tags, "newTag")
                .build();
        assertEquals(Mutation.Kind.APPEND_IF_ABSENT, appendIfAbsent.operations().get("tags").kind());
        assertEquals("newTag", appendIfAbsent.operations().get("tags").value());

        Mutation remove = Mutation.builder()
                .remove(tags, "oldTag")
                .build();
        assertEquals(Mutation.Kind.REMOVE, remove.operations().get("tags").kind());
        assertEquals("oldTag", remove.operations().get("tags").value());

        Mutation clear = Mutation.builder()
                .set("tags", List.of())
                .build();
        assertEquals(List.of(), clear.values().get("tags"));
    }

    @Test
    void junctionPredicatesEdgeCases() {
        Predicate singleAnd = Predicate.and(Predicate.eq("a", 1));
        assertTrue(singleAnd instanceof Predicate.Junction);
        assertTrue(((Predicate.Junction) singleAnd).and());
        assertEquals(1, ((Predicate.Junction) singleAnd).predicates().size());

        Predicate singleOr = Predicate.or(Predicate.eq("b", 2));
        assertTrue(singleOr instanceof Predicate.Junction);
        assertFalse(((Predicate.Junction) singleOr).and());
        assertEquals(1, ((Predicate.Junction) singleOr).predicates().size());

        Predicate nestedAnd = Predicate.and(
                Predicate.and(Predicate.eq("x", 1), Predicate.eq("y", 2)),
                Predicate.or(Predicate.eq("z", 3), Predicate.eq("w", 4))
        );
        assertTrue(nestedAnd instanceof Predicate.Junction);
        List<Predicate> topPredicates = ((Predicate.Junction) nestedAnd).predicates();
        assertEquals(2, topPredicates.size());
        assertTrue(topPredicates.get(0) instanceof Predicate.Junction);
        assertTrue(((Predicate.Junction) topPredicates.get(0)).and());
        assertTrue(topPredicates.get(1) instanceof Predicate.Junction);
        assertFalse(((Predicate.Junction) topPredicates.get(1)).and());

        Predicate deepNesting = Predicate.and(
                Predicate.or(
                        Predicate.and(Predicate.eq("a", 1)),
                        Predicate.or(Predicate.eq("b", 2))
                ),
                Predicate.and(
                        Predicate.or(Predicate.eq("c", 3)),
                        Predicate.and(Predicate.eq("d", 4))
                )
        );
        assertTrue(deepNesting instanceof Predicate.Junction);
    }

    @Test
    void comparisonOperatorsWithNullValues() {
        Predicate eqNull = Predicate.eq("field", null);
        assertEquals(EQ, ((Predicate.Comparison) eqNull).operator());
        assertNull(((Predicate.Comparison) eqNull).value());

        Predicate neNull = Predicate.ne("field", null);
        assertEquals(NE, ((Predicate.Comparison) neNull).operator());
        assertNull(((Predicate.Comparison) neNull).value());

        Predicate ltNull = Predicate.lt("field", null);
        assertEquals(LT, ((Predicate.Comparison) ltNull).operator());
        assertNull(((Predicate.Comparison) ltNull).value());

        Predicate gteNull = Predicate.gte("field", null);
        assertEquals(GTE, ((Predicate.Comparison) gteNull).operator());
        assertNull(((Predicate.Comparison) gteNull).value());
    }

    @Test
    void fieldRefInPredicates() {
        FieldRef<String> stringRef = new FieldRef<>("name", String.class);
        FieldRef<Integer> intRef = new FieldRef<>("age", Integer.class);

        Predicate eqString = Predicate.eq(stringRef, "test");
        assertEquals("name", ((Predicate.Comparison) eqString).field());
        assertEquals("test", ((Predicate.Comparison) eqString).value());

        Predicate eqInt = Predicate.eq(intRef, 42);
        assertEquals("age", ((Predicate.Comparison) eqInt).field());
        assertEquals(42, ((Predicate.Comparison) eqInt).value());

        Predicate inWithFieldRef = Predicate.in("ids", List.of(1, 2, 3));
        assertEquals("ids", ((Predicate.Comparison) inWithFieldRef).field());
    }

    @SuppressWarnings("unchecked")
    private static FieldRef<Collection<String>> stringCollectionField(String name) {
        return (FieldRef<Collection<String>>) (FieldRef<?>) new FieldRef<>(name, Collection.class);
    }
}
