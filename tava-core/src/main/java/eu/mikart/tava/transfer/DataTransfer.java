package eu.mikart.tava.transfer;

import eu.mikart.tava.Tava;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/** Copies records from one adapter to another in pages. */
public final class DataTransfer {
    private DataTransfer() {}

    /**
     * Copies all requested entities and reports the first transfer error, if one occurs.
     *
     * @param source adapter to read from
     * @param target adapter to write to
     * @param schema entities and fields to copy
     * @param options schema and record transformation behavior
     * @return transfer counts, issues, and completion state
     */
    public static @NotNull TransferReport copy(
            @NotNull final Tava source,
            @NotNull final Tava target,
            @NotNull final Schema schema,
            @NotNull final TransferOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        if (options.applySchema()) {
            target.plan(schema).apply(new ApplyOptions(options.allowLossy(), false));
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        List<TransferIssue> issues = new ArrayList<>();
        for (var entity : schema.entities()) {
            long count = 0;
            String cursor = null;
            final var seenCursors = new HashSet<String>();
            do {
                Page<EntityRecord> page;
                try {
                    page = source.records(entity.name())
                            .find(Query.builder()
                                    .limit(options.batchSize())
                                    .cursor(cursor)
                                    .build());
                } catch (RuntimeException failure) {
                    issues.add(
                            new TransferIssue(entity.name(), null, TransferIssue.Severity.ERROR, failure.getMessage()));
                    counts.put(entity.name(), count);
                    return new TransferReport(counts, issues, false);
                }
                final String nextCursor = page.nextCursor();
                if (nextCursor != null && !seenCursors.add(nextCursor)) {
                    issues.add(new TransferIssue(
                            entity.name(),
                            null,
                            TransferIssue.Severity.ERROR,
                            "Source adapter repeated a cursor; transfer stopped to prevent an infinite loop"));
                    counts.put(entity.name(), count);
                    return new TransferReport(counts, issues, false);
                }
                for (EntityRecord record : page.items()) {
                    try {
                        EntityRecord transformed = options.transform().apply(record);
                        target.records(entity.name())
                                .insert(TransferRecords.conform(
                                        source.adapterName(), entity, transformed, options.allowLossy(), issues));
                        count++;
                        options.progress().transferred(entity.name(), count);
                    } catch (RuntimeException failure) {
                        issues.add(new TransferIssue(
                                entity.name(), null, TransferIssue.Severity.ERROR, failure.getMessage()));
                        counts.put(entity.name(), count);
                        return new TransferReport(counts, issues, false);
                    }
                }
                cursor = nextCursor;
            } while (cursor != null);
            counts.put(entity.name(), count);
        }
        return new TransferReport(counts, issues, true);
    }
}
