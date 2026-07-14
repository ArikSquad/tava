package eu.mikart.tava.spi;

import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.data.MutationResult;
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

    default @NotNull MutationResult insertResult(@NotNull String entity, @NotNull EntityRecord record) {
        insert(entity, record); return MutationResult.changed(1, MutationResult.Outcome.INSERTED);
    }
    default @NotNull MutationResult updateResult(@NotNull String entity, @NotNull Predicate predicate, @NotNull Mutation mutation) {
        return MutationResult.changed(update(entity, predicate, mutation), MutationResult.Outcome.UPDATED);
    }
    default @NotNull MutationResult deleteResult(@NotNull String entity, @NotNull Predicate predicate) {
        return MutationResult.changed(delete(entity, predicate), MutationResult.Outcome.DELETED);
    }
    default @NotNull MutationResult upsert(@NotNull String entity, @NotNull Predicate identity, @NotNull EntityRecord insert, @NotNull Mutation update) {
        throw new eu.mikart.tava.TavaException.Capability("Adapter does not support atomic upsert");
    }
}
