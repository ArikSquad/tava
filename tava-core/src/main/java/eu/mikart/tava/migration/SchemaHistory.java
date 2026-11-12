package eu.mikart.tava.migration;

import eu.mikart.tava.Tava;
import eu.mikart.tava.schema.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/**
 * An ordered history of immutable schemas and developer-defined transitions in both directions. Rerunning an operation
 * resumes its interrupted step; actions must tolerate retries.
 */
public final class SchemaHistory {
    private final List<Version> versions;
    private final String journal;

    private SchemaHistory(List<Version> versions, String journal) {
        if (versions.isEmpty()) throw new IllegalArgumentException("schema history is empty");
        this.versions = List.copyOf(versions);
        this.journal = journal;
        for (Version version : versions) {
            if (version.next.entities().stream()
                    .anyMatch(entity -> entity.name().equals(journal)))
                throw new IllegalArgumentException("History cannot manage its own journal: " + journal);
        }
    }

    /**
     * Returns an empty history builder.
     *
     * @return an empty history builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the frozen versions in declaration order.
     *
     * @return the frozen versions in declaration order
     */
    public List<Version> versions() {
        return versions;
    }

    /**
     * Applies pending versions in order, resuming interrupted forward work automatically.
     *
     * @param database database to migrate
     */
    public void up(Tava database) {
        new SchemaJournal(database, this).move(versions.size());
    }

    /**
     * Rolls back the latest version, or resumes its interrupted rollback. An empty history is a no-op.
     *
     * @param database database to migrate
     */
    public void down(Tava database) {
        new SchemaJournal(database, this).move(-1);
    }

    /**
     * Moves forward or backward to a named version. All required down paths are checked before execution.
     *
     * @param database database to migrate
     * @param version target version identifier
     */
    public void to(Tava database, String version) {
        for (int index = 0; index < versions.size(); index++) {
            if (versions.get(index).id.equals(version)) {
                new SchemaJournal(database, this).move(index + 1);
                return;
            }
        }
        throw new IllegalArgumentException("Unknown schema version: " + version);
    }

    /**
     * Reads the current version without creating storage. Interrupted transitions must be finished first.
     *
     * @param database database to inspect
     * @return current version identifier, or empty before the first version
     */
    public Optional<String> current(Tava database) {
        return new SchemaJournal(database, this).current();
    }

    String journal() {
        return journal;
    }

    /** A historical schema and its immutable forward and backward actions. */
    public static final class Version {
        private final String id;
        private final Schema previous;
        private final Schema next;
        private final List<Step> up;
        private final List<Step> down;
        private final String checksum;

        private Version(String id, Schema previous, Schema next, Transition transition, String checksum) {
            this.id = id;
            this.previous = previous;
            this.next = next;
            this.up = List.copyOf(transition.up);
            this.down = List.copyOf(transition.down);
            this.checksum = checksum;
        }

        /**
         * Returns stable version identifier.
         *
         * @return stable version identifier
         */
        public String id() {
            return id;
        }
        /**
         * Returns the preceding schema, empty for the first version.
         *
         * @return the preceding schema, empty for the first version
         */
        public Schema previous() {
            return previous;
        }
        /**
         * Returns this version's schema.
         *
         * @return this version's schema
         */
        public Schema next() {
            return next;
        }
        /**
         * Returns whether explicit rollback actions were declared.
         *
         * @return whether explicit rollback actions were declared
         */
        public boolean reversible() {
            return !down.isEmpty();
        }

        List<Step> steps(boolean backwards) {
            return backwards ? down : up;
        }

        String checksum() {
            return checksum;
        }
    }

    /** Declares versions in application order; identifiers are never sorted. */
    public static final class Builder {
        private final List<Version> versions = new ArrayList<>();
        private String journal = "_tava_migrations";

        private Builder() {}

        /**
         * Chooses a separate journal for an independently managed history.
         *
         * @param entity journal storage name
         * @return this builder
         */
        public Builder journal(String entity) {
            if (entity == null || entity.isBlank()) throw new IllegalArgumentException("journal name is required");
            journal = entity;
            return this;
        }

        /**
         * Captures a schema and declares its forward and backward actions. The definition runs now; its actions run
         * only during migration. At least one forward action is required.
         *
         * @param id stable version identifier
         * @param schema immutable target schema
         * @param definition transition definition
         * @return this builder
         */
        public Builder version(String id, Schema schema, Consumer<Transition> definition) {
            if (id == null || id.isBlank() || id.length() > 255 || id.equals(SchemaJournal.LOCK_ID))
                throw new IllegalArgumentException("valid non-reserved version identifier is required");
            if (versions.stream().anyMatch(version -> version.id.equals(id)))
                throw new IllegalArgumentException("Duplicate schema version: " + id);
            Schema previous = versions.isEmpty() ? new Schema(List.of()) : versions.getLast().next;
            Schema next = freeze(Objects.requireNonNull(schema, "schema"));
            Transition transition = new Transition(previous, next);
            Objects.requireNonNull(definition, "definition").accept(transition);
            if (transition.up.isEmpty()) throw new IllegalArgumentException("Version requires an up action: " + id);
            StringBuilder content = new StringBuilder();
            append(
                    content,
                    List.of(
                            "schema-history-v2",
                            id,
                            versions.isEmpty() ? "" : versions.getLast().checksum,
                            canonical(previous),
                            canonical(next),
                            transition.checksum,
                            transition.up.stream().map(Step::description).toList(),
                            transition.down.stream().map(Step::description).toList()));
            versions.add(new Version(id, previous, next, transition, digest(content.toString())));
            return this;
        }

        /**
         * Returns the immutable declared history.
         *
         * @return the immutable declared history
         */
        public SchemaHistory build() {
            return new SchemaHistory(versions, journal);
        }
    }

    /** Defines the ordered actions between the two supplied schemas. */
    public static final class Transition {
        private final Schema previous;
        private final Schema next;
        private final List<Step> up = new ArrayList<>();
        private final List<Step> down = new ArrayList<>();
        private String checksum = "";

        private Transition(Schema previous, Schema next) {
            this.previous = previous;
            this.next = next;
        }

        /**
         * Returns the previous schema.
         *
         * @return the previous schema
         */
        public Schema previous() {
            return previous;
        }
        /**
         * Returns the next schema.
         *
         * @return the next schema
         */
        public Schema next() {
            return next;
        }

        /**
         * Optionally binds action code to an immutable source digest in addition to the automatic schema checksum.
         *
         * @param digest digest of all source and dependencies affecting the actions
         * @return this transition
         */
        public Transition checksum(String digest) {
            if (digest == null || digest.isBlank()) throw new IllegalArgumentException("content digest is required");
            checksum = digest;
            return this;
        }

        /**
         * Adds a forward action; completed actions are checkpointed individually.
         *
         * @param action action receiving both historical schemas and database access
         * @return this transition
         */
        public Transition up(Consumer<MigrationContext> action) {
            return up("Up step " + (up.size() + 1), action);
        }

        /**
         * Adds a named forward action.
         *
         * @param description action label used in failures
         * @param action action that may be retried after interruption
         * @return this transition
         */
        public Transition up(String description, Consumer<MigrationContext> action) {
            up.add(new Step(description, action));
            return this;
        }

        /**
         * Adds a rollback action. Down actions run in the order declared, with versions undone in reverse order.
         *
         * @param action action receiving both historical schemas and database access
         * @return this transition
         */
        public Transition down(Consumer<MigrationContext> action) {
            return down("Down step " + (down.size() + 1), action);
        }

        /**
         * Adds a named rollback action.
         *
         * @param description action label used in failures
         * @param action action that may be retried after interruption
         * @return this transition
         */
        public Transition down(String description, Consumer<MigrationContext> action) {
            down.add(new Step(description, action));
            return this;
        }
    }

    record Step(String description, Consumer<MigrationContext> action) {
        Step {
            if (description == null || description.isBlank())
                throw new IllegalArgumentException("step label is required");
            Objects.requireNonNull(action, "action");
        }
    }

    private static Schema freeze(Schema schema) {
        return new Schema(schema.entities().stream()
                .map(entity -> new EntityDefinition(
                        entity.name(),
                        entity.fields().stream()
                                .map(field -> new FieldDefinition(
                                        field.name(),
                                        field.type(),
                                        field.nullable(),
                                        field.identity(),
                                        field.unique(),
                                        field.generated(),
                                        settings(field.settings())))
                                .toList(),
                        entity.indexes().stream()
                                .map(index -> new IndexDefinition(
                                        index.name(), index.fields(), index.unique(), settings(index.settings())))
                                .toList(),
                        settings(entity.settings())))
                .toList());
    }

    private static Map<String, Object> settings(Map<String, Object> values) {
        Map<String, Object> frozen = new TreeMap<>();
        values.forEach((key, value) -> frozen.put(key, freezeValue(value)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal) return value;
        if (value instanceof List<?> list)
            return list.stream().map(SchemaHistory::freezeValue).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text))
                    throw new IllegalArgumentException("schema setting keys must be strings");
                frozen.put(text, freezeValue(item));
            });
            return Collections.unmodifiableMap(frozen);
        }
        throw new IllegalArgumentException("unsupported schema setting value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static String canonical(Schema schema) {
        StringBuilder text = new StringBuilder();
        append(text, schema.entities().size());
        schema.entities().forEach(entity -> {
            append(text, entity.name());
            append(text, entity.settings());
            append(text, entity.fields().size());
            entity.fields().forEach(field -> {
                append(text, field.name());
                append(text, field.type());
                append(text, field.nullable());
                append(text, field.identity());
                append(text, field.unique());
                append(text, field.generated());
                append(text, field.settings());
            });
            append(text, entity.indexes().size());
            entity.indexes().forEach(index -> {
                append(text, index.name());
                append(text, index.fields());
                append(text, index.unique());
                append(text, index.settings());
            });
        });
        return text.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value instanceof Map<?, ?> map) {
            out.append('M').append(map.size()).append(':');
            map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> {
                        append(out, entry.getKey());
                        append(out, entry.getValue());
                    });
        } else if (value instanceof List<?> list) {
            out.append('L').append(list.size()).append(':');
            list.forEach(item -> append(out, item));
        } else {
            String text = value.getClass().getName() + ":" + value;
            out.append(text.length()).append(':').append(text);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
