package eu.mikart.tava;

import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class TavaLifecycleTest {
    @Test
    void rejectsFacadeUseAfterCloseEvenWhenAdapterCloseIsNoOp() {
        Tava tava = Tava.open(new NoCloseAdapter());
        Records records = tava.records("items");
        var schema = tava.schema();
        SchemaPlan plan = schema.plan(Schema.builder().build());
        tava.close();

        assertThrows(
                IllegalStateException.class,
                () -> records.insert(EntityRecord.builder().set("id", 1).build()));
        assertThrows(IllegalStateException.class, () -> tava.records("items").find(Query.all()));
        assertThrows(IllegalStateException.class, schema::inspect);
        assertThrows(IllegalStateException.class, plan::apply);
    }

    private static final class NoCloseAdapter implements Adapter {
        private final EntityStore store = new EntityStore() {
            @Override
            public EntityRecord insert(String entity, EntityRecord record) {
                return record;
            }

            @Override
            public Page<EntityRecord> find(String entity, Query query) {
                return new Page<>(List.of(), null);
            }

            @Override
            public long update(String entity, Predicate predicate, Mutation mutation) {
                return 0;
            }

            @Override
            public long delete(String entity, Predicate predicate) {
                return 0;
            }
        };
        private final SchemaManager schemas = new SchemaManager() {
            @Override
            public Schema inspect() {
                return Schema.builder().build();
            }

            @Override
            public SchemaPlan plan(Schema desired) {
                return new SchemaPlan(List.of(), () -> {});
            }
        };

        @Override
        public String name() {
            return "no-close";
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.builder(name()).build();
        }

        @Override
        public SchemaManager schemas() {
            return schemas;
        }

        @Override
        public EntityStore entities() {
            return store;
        }

        @Override
        public NativeAccess nativeAccess() {
            return new NativeAccess() {
                @Override
                @SuppressWarnings("deprecation")
                public <T> T nativeHandle(Class<T> type) {
                    throw new IllegalArgumentException(type.getName());
                }
            };
        }

        @Override
        public void close() {}
    }
}
