package eu.mikart.tava.transfer;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DataTransferTest {
    @Test
    void stopsWhenSourceRepeatsCursor() {
        EntityRecord record = EntityRecord.builder().set("id", 1).build();
        EntityStore sourceStore = new StubStore() {
            @Override public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(record), "same-cursor");
            }
        };
        StubStore targetStore = new StubStore();
        Schema schema = Schema.builder().entity("things", entity -> entity.integer("id")).build();
        TransferOptions options = new TransferOptions(10, false, false, null, null);

        try (Tava source = Tava.open(adapter(sourceStore)); Tava target = Tava.open(adapter(targetStore))) {
            TransferReport report = DataTransfer.copy(source, target, schema, options);

            assertFalse(report.complete());
            assertEquals(1, report.transferred().get("things"));
            assertEquals(TransferIssue.Severity.ERROR, report.issues().getFirst().severity());
        }
    }

    private static Adapter adapter(EntityStore store) {
        return new Adapter() {
            public String name() { return "stub"; }
            public eu.mikart.tava.capability.Capabilities capabilities() { throw new UnsupportedOperationException(); }
            public eu.mikart.tava.spi.SchemaManager schemas() { throw new UnsupportedOperationException(); }
            public EntityStore entities() { return store; }
            public eu.mikart.tava.spi.NativeAccess nativeAccess() { throw new UnsupportedOperationException(); }
            public void close() { }
        };
    }

    private static class StubStore implements EntityStore {
        private int inserts;
        public EntityRecord insert(String entity, EntityRecord record) { inserts++; return record; }
        public Page<EntityRecord> find(String entity, Query query) { return new Page<>(List.of(), null); }
        public long update(String entity, Predicate predicate, Mutation mutation) { return 0; }
        public long delete(String entity, Predicate predicate) { return 0; }
    }
}
