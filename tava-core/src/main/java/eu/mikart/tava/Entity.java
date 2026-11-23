package eu.mikart.tava;

import eu.mikart.tava.spi.EntityStore;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.NotNull;

/**
 * Compatibility type for the original typed record facade.
 *
 * @param <T> record type
 * @deprecated Use {@link EntityHandle}.
 */
@Deprecated(forRemoval = true)
public final class Entity<T extends Record> extends EntityHandle<T> {
    Entity(
            final @NotNull String name,
            final @NotNull EntityStore store,
            final @NotNull RecordMapper<T> mapper,
            final @NotNull Executor executor) {
        super(name, store, mapper, executor);
    }
}
