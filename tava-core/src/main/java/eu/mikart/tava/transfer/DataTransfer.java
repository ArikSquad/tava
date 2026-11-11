package eu.mikart.tava.transfer;

import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.spi.OperationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/** Copies records from one adapter to another in pages. */
public final class DataTransfer {
    /**
     * Creates the legacy utility instance.
     *
     * @deprecated Use the static copy methods directly.
     */
    @Deprecated(since = "1.3.0", forRemoval = false)
    public DataTransfer() {}

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
        return copy(source, target, schema, options, TransferControl.defaults());
    }

    /**
     * Copies records with cooperative cancellation, a deadline, and bounded diagnostics and cursor tracking.
     *
     * @param source source adapter
     * @param target target adapter
     * @param schema schema to copy
     * @param options transfer options
     * @param control cancellation, timeout, and memory limits
     * @return transfer progress and retained diagnostics
     * @since 1.3.0
     */
    public static TransferReport copy(
            Tava source, Tava target, Schema schema, TransferOptions options, TransferControl control) {
        Objects.requireNonNull(control, "control");
        try (OperationContext context = OperationContext.open(control.timeout(), control.cancelled())) {
            return copyControlled(source, target, schema, options, control);
        }
    }

    private static TransferReport copyControlled(
            Tava source, Tava target, Schema schema, TransferOptions options, TransferControl control) {
        long started = System.nanoTime();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        Map<String, Long> counts = new LinkedHashMap<>();
        List<TransferIssue> issues = new TransferIssues(control.maxIssues());
        try {
            control.check(started);
        } catch (RuntimeException failure) {
            issues.add(new TransferIssue("*", null, TransferIssue.Severity.ERROR, failure.getMessage()));
            return new TransferReport(counts, issues, false);
        }
        if (options.applySchema()) {
            target.plan(schema).apply(new ApplyOptions(options.allowLossy(), false));
        }
        for (var entity : schema.entities()) {
            long count = 0;
            String cursor = null;
            final var seenCursors = new TransferCursors(control.maxTrackedCursors(), null);
            do {
                Page<EntityRecord> page;
                try {
                    control.check(started);
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
                try {
                    seenCursors.add(nextCursor);
                    control.check(started);
                } catch (RuntimeException failure) {
                    issues.add(
                            new TransferIssue(entity.name(), null, TransferIssue.Severity.ERROR, failure.getMessage()));
                    counts.put(entity.name(), count);
                    return new TransferReport(counts, issues, false);
                }
                boolean bulk = target.capabilities().supports(Feature.BULK_WRITE)
                        && target.capabilities().supports(Feature.TRANSACTIONS);
                int batchSize = (int) Math.min(
                        Integer.MAX_VALUE,
                        target.capabilities()
                                .feature(Feature.BULK_WRITE)
                                .limits()
                                .getOrDefault("batchSize", (long) options.batchSize()));
                if (bulk && batchSize < 1) throw new IllegalArgumentException("Invalid target batch size capability");
                int position = 0;
                while (position < page.items().size()) {
                    int end = position
                            + Math.min(bulk ? batchSize : 1, page.items().size() - position);
                    List<EntityRecord> batch = new ArrayList<>();
                    try {
                        control.check(started);
                        for (EntityRecord record : page.items().subList(position, end)) {
                            batch.add(TransferRecords.conform(
                                    source.adapterName(),
                                    entity,
                                    options.transform().apply(record),
                                    options.allowLossy(),
                                    issues));
                        }
                        boolean inserted = false;
                        if (bulk && batch.size() > 1) {
                            try {
                                target.transaction(tx -> {
                                    List<EntityRecord> result =
                                            tx.records(entity.name()).insert(batch);
                                    if (result.size() != batch.size())
                                        throw new IllegalStateException("Incomplete bulk insert result");
                                    return null;
                                });
                                inserted = true;
                            } catch (TavaException.Conflict | TavaException.Capability failure) {
                                // A rejected transaction rolls back the batch; retry without rerunning transforms.
                            }
                        }
                        if (inserted) {
                            long previous = count;
                            count += batch.size();
                            for (long current = previous + 1; current <= count; current++)
                                options.progress().transferred(entity.name(), current);
                        } else {
                            for (EntityRecord record : batch) {
                                control.check(started);
                                target.records(entity.name()).insert(record);
                                count++;
                                options.progress().transferred(entity.name(), count);
                            }
                        }
                    } catch (RuntimeException failure) {
                        issues.add(new TransferIssue(
                                entity.name(), null, TransferIssue.Severity.ERROR, failure.getMessage()));
                        counts.put(entity.name(), count);
                        return new TransferReport(counts, issues, false);
                    }
                    position = end;
                }
                cursor = nextCursor;
            } while (cursor != null);
            counts.put(entity.name(), count);
        }
        return new TransferReport(counts, issues, true);
    }
}
