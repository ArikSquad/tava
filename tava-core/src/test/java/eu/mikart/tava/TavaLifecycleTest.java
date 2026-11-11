package eu.mikart.tava;

import static org.junit.jupiter.api.Assertions.*;

import eu.mikart.tava.capability.Capabilities;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.annotation.Identity;
import eu.mikart.tava.schema.plan.SchemaPlan;
import eu.mikart.tava.spi.Adapter;
import eu.mikart.tava.spi.EntityStore;
import eu.mikart.tava.spi.ForwardingEntityStore;
import eu.mikart.tava.spi.NativeAccess;
import eu.mikart.tava.spi.SchemaManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TavaLifecycleTest {
    @Test
    void defaultAsyncCallsRunOnVirtualThreads() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        try (Tava tava = Tava.open(new NoCloseAdapter() {
            @Override
            public EntityStore entities() {
                EntityStore delegate = super.entities();
                return new ForwardingEntityStore() {
                    protected EntityStore delegate() {
                        return delegate;
                    }

                    @Override
                    public Page<EntityRecord> find(String entity, Query query) {
                        virtual.set(Thread.currentThread().isVirtual());
                        return delegate.find(entity, query);
                    }
                };
            }
        })) {
            record Item(@Identity int id) {}
            tava.entity("items", Item.class).findByIdAsync(1).get(5, TimeUnit.SECONDS);
            assertTrue(virtual.get());
        }
    }

    @Test
    void timedCloseLeavesFacadeOpenUntilActiveOperationFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tava tava = Tava.open(new NoCloseAdapter() {
            @Override
            public EntityStore entities() {
                EntityStore delegate = super.entities();
                return new ForwardingEntityStore() {
                    protected EntityStore delegate() {
                        return delegate;
                    }

                    @Override
                    public Page<EntityRecord> find(String entity, Query query) {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new IllegalStateException("Test operation timed out");
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                        return delegate.find(entity, query);
                    }
                };
            }
        });
        CompletableFuture<?> active =
                CompletableFuture.runAsync(() -> tava.records("items").find(Query.all()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(tava.close(Duration.ZERO));
            assertEquals("no-close", tava.adapterName());
        } finally {
            release.countDown();
            active.get(5, TimeUnit.SECONDS);
            assertTrue(tava.close(Duration.ofSeconds(1)));
        }
        assertThrows(IllegalStateException.class, tava::adapterName);
    }

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

    private static class NoCloseAdapter implements Adapter {
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
