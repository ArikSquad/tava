package eu.mikart.tava.transfer;

import eu.mikart.tava.Records;
import eu.mikart.tava.Tava;
import eu.mikart.tava.TavaException;
import eu.mikart.tava.ValueCodecs;
import eu.mikart.tava.capability.Feature;
import eu.mikart.tava.data.EntityRecord;
import eu.mikart.tava.data.Identity;
import eu.mikart.tava.data.Page;
import eu.mikart.tava.query.Query;
import eu.mikart.tava.schema.EntityDefinition;
import eu.mikart.tava.schema.Schema;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.spi.OperationContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Resumable page-at-a-time copy. Transfer IDs must identify the endpoints and deterministic transform version. The
 * source must remain stable or provide its own snapshot while the copy runs.
 *
 * @since 1.3.0
 */
public final class ResumableTransfer {
    private ResumableTransfer() {}

    /**
     * Copies entities from a stable source position by position, saving progress after each page. Reuse the same
     * transfer ID only for the same endpoints, schema, and deterministic transform.
     *
     * @param source source adapter
     * @param target target adapter
     * @param schema entities and fields to copy
     * @param options transfer behavior
     * @param transferId stable identity for this transfer configuration
     * @param checkpoints durable checkpoint store
     * @return counts, issues, and completion state
     * @throws IllegalArgumentException if {@code transferId} is {@code null} or blank, the saved checkpoint does not
     *     match this transfer configuration, or its saved position is invalid
     * @throws NullPointerException if any argument other than {@code transferId} is {@code null}
     * @since 1.3.0
     */
    public static TransferReport copy(
            Tava source,
            Tava target,
            Schema schema,
            TransferOptions options,
            String transferId,
            TransferCheckpointStore checkpoints) {
        return copy(source, target, schema, options, transferId, checkpoints, TransferControl.defaults());
    }

    /**
     * Copies records with cooperative cancellation, a deadline, and bounded diagnostics and cursor tracking.
     *
     * @param source source adapter
     * @param target target adapter
     * @param schema schema to copy
     * @param options transfer options
     * @param transferId stable transfer identifier
     * @param checkpoints checkpoint store
     * @param control cancellation, timeout, and memory limits
     * @return transfer progress and retained diagnostics
     * @since 1.3.0
     */
    public static TransferReport copy(
            Tava source,
            Tava target,
            Schema schema,
            TransferOptions options,
            String transferId,
            TransferCheckpointStore checkpoints,
            TransferControl control) {
        Objects.requireNonNull(control, "control");
        try (OperationContext context = OperationContext.open(control.timeout(), control.cancelled())) {
            return copyControlled(source, target, schema, options, transferId, checkpoints, control);
        }
    }

    private static TransferReport copyControlled(
            Tava source,
            Tava target,
            Schema schema,
            TransferOptions options,
            String transferId,
            TransferCheckpointStore checkpoints,
            TransferControl control) {
        long started = System.nanoTime();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(checkpoints, "checkpoints");
        String fingerprint = fingerprint(schema);
        try (TransferCheckpointStore.Lease lease = checkpoints.acquire(transferId)) {
            TransferCheckpoint state = lease.load().orElse(null);
            if (state == null) {
                state = new TransferCheckpoint(
                        transferId,
                        source.adapterName(),
                        target.adapterName(),
                        fingerprint,
                        options.batchSize(),
                        0,
                        null,
                        Map.of(),
                        false);
                lease.save(state);
            } else if (!state.sourceAdapter().equals(source.adapterName())
                    || !state.targetAdapter().equals(target.adapterName())
                    || !state.schemaFingerprint().equals(fingerprint)
                    || state.batchSize() != options.batchSize()
                    || state.entityIndex() > schema.entities().size()) {
                throw new IllegalArgumentException("Checkpoint does not match transfer configuration");
            }
            boolean invalidCompletePosition = state.complete()
                    && (state.entityIndex() != schema.entities().size() || state.cursor() != null);
            boolean invalidIncompletePosition = !state.complete()
                    && state.entityIndex() == schema.entities().size()
                    && !schema.entities().isEmpty();
            if (invalidCompletePosition || invalidIncompletePosition) {
                throw new IllegalArgumentException("Checkpoint has an invalid completed position");
            }
            Map<String, Long> counts = new LinkedHashMap<>(state.counts());
            List<TransferIssue> issues = new TransferIssues(control.maxIssues());
            issues.addAll(state.issues());
            if (state.complete()) return new TransferReport(counts, issues, true);
            try {
                control.check(started);
            } catch (RuntimeException failure) {
                issues.add(new TransferIssue("*", null, TransferIssue.Severity.ERROR, failure.getMessage()));
                return new TransferReport(counts, issues, false);
            }
            if (options.applySchema()) target.plan(schema).apply(new ApplyOptions(options.allowLossy(), false));
            for (int index = state.entityIndex(); index < schema.entities().size(); index++) {
                EntityDefinition entity = schema.entities().get(index);
                String cursor = index == state.entityIndex() ? state.cursor() : null;
                TransferCursors seen = new TransferCursors(control.maxTrackedCursors(), cursor);
                do {
                    try {
                        control.check(started);
                        Page<EntityRecord> page = source.records(entity.name())
                                .find(Query.builder()
                                        .limit(options.batchSize())
                                        .cursor(cursor)
                                        .build());
                        String next = page.nextCursor();
                        seen.add(next);
                        control.check(started);
                        List<EntityRecord> records = page.items().stream()
                                .map(options.transform())
                                .map(record -> TransferRecords.conform(
                                        source.adapterName(), entity, record, options.allowLossy(), issues))
                                .toList();
                        write(target, entity, records, control, started);
                        long count = counts.getOrDefault(entity.name(), 0L);
                        counts.put(entity.name(), count + records.size());
                        int nextEntity = next == null ? index + 1 : index;
                        boolean complete = nextEntity == schema.entities().size();
                        state = new TransferCheckpoint(
                                transferId,
                                source.adapterName(),
                                target.adapterName(),
                                fingerprint,
                                options.batchSize(),
                                nextEntity,
                                next,
                                counts,
                                complete,
                                issues);
                        lease.save(state);
                        for (int i = 0; i < records.size(); i++)
                            options.progress().transferred(entity.name(), ++count);
                        cursor = next;
                    } catch (RuntimeException failure) {
                        issues.add(new TransferIssue(
                                entity.name(),
                                null,
                                TransferIssue.Severity.ERROR,
                                Objects.toString(
                                        failure.getMessage(), failure.getClass().getName())));
                        return new TransferReport(counts, issues, false);
                    }
                } while (cursor != null);
            }
            if (!state.complete()) {
                state = new TransferCheckpoint(
                        transferId,
                        source.adapterName(),
                        target.adapterName(),
                        fingerprint,
                        options.batchSize(),
                        schema.entities().size(),
                        null,
                        counts,
                        true,
                        issues);
                lease.save(state);
            }
            return new TransferReport(counts, issues, true);
        }
    }

    private static void write(
            Tava target, EntityDefinition entity, List<EntityRecord> values, TransferControl control, long started) {
        if (values.isEmpty()) return;
        values.forEach(value -> identity(entity, value));
        Records records = target.records(entity.name());
        long limit = target.capabilities().feature(Feature.BULK_WRITE).limits().getOrDefault("batchSize", (long)
                Integer.MAX_VALUE);
        if (limit < 1) throw new TavaException.Capability("Invalid target batch size capability");
        int max = (int) Math.min(Integer.MAX_VALUE, limit);
        if (!target.capabilities().supports(Feature.BULK_WRITE)) max = 1;
        for (int start = 0; start < values.size(); ) {
            control.check(started);
            int end = start + Math.min(max, values.size() - start);
            List<EntityRecord> batch = values.subList(start, end);
            if (batch.size() > 1) {
                try {
                    List<EntityRecord> inserted = records.insert(batch);
                    if (inserted != null && inserted.size() == batch.size()) {
                        start = end;
                        continue;
                    }
                    // A short bulk result may follow a partial write. Reconcile each identity before advancing.
                } catch (TavaException.Conflict | TavaException.Capability retryIndividually) {
                    /* Confirm each existing identity below. */
                }
            }
            for (EntityRecord value : batch) {
                control.check(started);
                Identity identity = identity(entity, value);
                var result = records.insertResult(value);
                if (!result.inserted() && !result.conflicted())
                    throw new TavaException.Data("Unexpected insert result during transfer", null);
                if (result.conflicted()) {
                    EntityRecord existing = records.findById(identity)
                            .orElseThrow(() ->
                                    new TavaException.Conflict("Conflict does not match transfer identity", null));
                    if (!matches(entity, value.values(), existing.values()))
                        throw new TavaException.Conflict("Existing record differs from transferred record", null);
                }
            }
            start = end;
        }
    }

    private static Identity identity(EntityDefinition entity, EntityRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object partition = entity.settings().get("dynamodb.partitionKey");
        if (partition != null) values.put(partition.toString(), record.get(partition.toString()));
        entity.fields().stream()
                .filter(field -> field.identity())
                .forEach(field -> values.put(field.name(), record.get(field.name())));
        if (values.isEmpty() || values.values().stream().anyMatch(Objects::isNull))
            throw new TavaException.Schema("Resumable transfer requires non-null identity fields for " + entity.name());
        return Identity.of(values);
    }

    private static boolean matches(EntityDefinition entity, Map<String, Object> expected, Map<String, Object> actual) {
        return expected.entrySet().stream()
                .allMatch(entry -> actual.containsKey(entry.getKey())
                        && equivalent(
                                entry.getValue(),
                                TransferRecords.canonical(entity.field(entry.getKey()), actual.get(entry.getKey()))));
    }

    private static boolean equivalent(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            try {
                return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return a.equals(b);
            }
        }
        if (left instanceof LocalDateTime && right instanceof Timestamp timestamp) right = timestamp.toLocalDateTime();
        if (right instanceof LocalDateTime && left instanceof Timestamp timestamp) left = timestamp.toLocalDateTime();
        if (left instanceof java.sql.Date date) left = date.toLocalDate();
        else if (left instanceof Date date) left = date.toInstant();
        if (right instanceof java.sql.Date date) right = date.toLocalDate();
        else if (right instanceof Date date) right = date.toInstant();
        if (left instanceof OffsetDateTime date) left = date.toInstant();
        if (right instanceof OffsetDateTime date) right = date.toInstant();
        if (left instanceof UUID || right instanceof UUID)
            return Objects.toString(left).equals(Objects.toString(right));
        if (left instanceof byte[] a && right instanceof byte[] b) return Arrays.equals(a, b);
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            return a.entrySet().stream().allMatch(entry -> equivalent(entry.getValue(), b.get(entry.getKey())));
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!equivalent(a.get(i), b.get(i))) return false;
            return true;
        }
        return Objects.equals(left, right);
    }

    private static String fingerprint(Schema schema) {
        List<Object> entities = new ArrayList<>();
        for (EntityDefinition entity : schema.entities()) {
            List<Object> fields = new ArrayList<>();
            entity.fields()
                    .forEach(field -> fields.add(List.of(
                            field.name(),
                            field.type().toString(),
                            field.nullable(),
                            field.identity(),
                            field.unique(),
                            field.generated().name(),
                            canonical(field.settings()))));
            List<Object> indexes = new ArrayList<>();
            entity.indexes()
                    .forEach(index -> indexes.add(
                            List.of(index.name(), index.fields(), index.unique(), canonical(index.settings()))));
            entities.add(List.of(entity.name(), fields, indexes, canonical(entity.settings())));
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(ValueCodecs.toJson(entities).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> list)
            return list.stream().map(ResumableTransfer::canonical).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        throw new IllegalArgumentException("Unsupported schema setting in transfer fingerprint: "
                + value.getClass().getName());
    }
}
