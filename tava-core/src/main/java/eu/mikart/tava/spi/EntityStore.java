package eu.mikart.tava.spi;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Mutation;
import eu.mikart.tava.query.Predicate;
import eu.mikart.tava.query.Query;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface EntityStore {
    @NotNull EntityRecord insert(@NotNull String entity, @NotNull EntityRecord record);

    default @NotNull List<EntityRecord> insert(final @NotNull String entity, final @NotNull List<EntityRecord> records) {
        return records.stream().map(record -> insert(entity, record)).toList();
    }

    @NotNull Page<EntityRecord> find(@NotNull String entity, @NotNull Query query);

    long update(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation);

    long delete(@NotNull String entity, @NotNull Predicate predicate);
}
