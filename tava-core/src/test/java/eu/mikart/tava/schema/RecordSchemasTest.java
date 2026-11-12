package eu.mikart.tava.schema;

import static org.junit.jupiter.api.Assertions.*;

import eu.mikart.tava.processor.EntityFieldsProcessor;
import eu.mikart.tava.query.FieldRef;
import eu.mikart.tava.schema.annotation.*;
import eu.mikart.tava.schema.plan.ApplyOptions;
import eu.mikart.tava.schema.plan.ChangeRisk;
import eu.mikart.tava.schema.plan.SchemaChange;
import eu.mikart.tava.schema.plan.SchemaPlan;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordSchemasTest {
    @Test
    void generatesMappedFieldsWithPrimitiveAndCollectionTypes(@TempDir Path directory) throws Exception {
        var source = directory.resolve("Sample.java");
        Files.writeString(source, """
                package sample;
                @eu.mikart.tava.schema.annotation.Entity
                public record Sample(
                    @eu.mikart.tava.schema.annotation.Field("email_address") String email,
                    int score, java.util.List<java.util.UUID> ids) {}
                """);
        var usage = directory.resolve("Usage.java");
        Files.writeString(usage, """
                package sample;
                class Usage {
                    eu.mikart.tava.query.Predicate query = SampleFields.score.gte(2);
                    eu.mikart.tava.query.Mutation mutation = eu.mikart.tava.query.Mutation.builder()
                        .appendIfAbsent(SampleFields.ids, java.util.UUID.randomUUID()).build();
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            String classes = Path.of(FieldRef.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
            var task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of("-classpath", classes, "-d", directory.toString(), "-s", directory.toString()),
                    null,
                    files.getJavaFileObjects(source, usage));
            task.setProcessors(List.of(new EntityFieldsProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }
        try (var loader = new URLClassLoader(
                new URL[] {directory.toUri().toURL()}, getClass().getClassLoader())) {
            var generated = loader.loadClass("sample.SampleFields");
            var email = (FieldRef<?>) generated.getField("email").get(null);
            assertEquals("email_address", email.name());
            var score = (FieldRef<?>) generated.getField("score").get(null);
            assertEquals(Integer.class, score.type());
        }
    }

    @Test
    void createsSchemaFromRecordTypesAndRejectsDuplicateEntities() {
        assertEquals(
                RecordSchemas.describe(Account.class), Schema.of(Account.class).entity("accounts"));
        assertThrows(IllegalArgumentException.class, () -> Schema.of(Account.class, Account.class));
    }

    @Test
    void explicitRiskAllowancesKeepUnsupportedChangesBlocked() {
        var options = ApplyOptions.allowing(ChangeRisk.LOSSY);
        assertTrue(options.allowLossy());
        assertFalse(options.allowDestructive());
        assertThrows(IllegalArgumentException.class, () -> ApplyOptions.allowing(ChangeRisk.UNSUPPORTED));
        var applied = new AtomicBoolean();
        var plan = new SchemaPlan(
                List.of(new SchemaChange("Drop field", ChangeRisk.DESTRUCTIVE, null)), () -> applied.set(true));
        assertThrows(IllegalStateException.class, () -> plan.apply(options));
        assertFalse(applied.get());
        assertThrows(IllegalStateException.class, () -> plan.application().run());
        assertFalse(applied.get());
        plan.apply(ApplyOptions.allowing(ChangeRisk.DESTRUCTIVE));
        assertTrue(applied.get());
    }

    @Test
    void createsCanonicalEntityFromRecord() {
        EntityDefinition entity = RecordSchemas.describe(Account.class);
        assertEquals("accounts", entity.name());
        assertTrue(entity.field("id").identity());
        assertEquals(FieldType.string(200), entity.field("email").type());
        assertEquals(FieldType.decimal(12, 2), entity.field("balance").type());
        assertEquals("JSONB", entity.field("metadata").settings().get("postgres.type"));
    }

    @Test
    void createsSchemaFromConsumerDsl() {
        Schema schema = Schema.builder()
                .entity("accounts", entity -> {
                    entity.uuid("id").identity().notNull();
                    entity.string("email").required().unique().length(255);
                    entity.integer("score");
                })
                .build();

        EntityDefinition entity = schema.entity("accounts");
        assertTrue(entity.field("id").identity());
        assertFalse(entity.field("id").nullable());
        assertEquals(FieldType.string(255), entity.field("email").type());
        assertTrue(entity.field("email").unique());
        assertEquals(FieldType.of(LogicalType.INT32), entity.field("score").type());
    }

    @Entity("accounts")
    record Account(
            @Identity UUID id,
            @Required @Unique @Field(length = 200) String email,
            @Field(precision = 12, scale = 2) BigDecimal balance,
            @Generated(GeneratedValue.NOW) Instant createdAt,

            @AdapterSetting(adapter = "postgres", key = "type", value = "JSONB")
            String metadata) {}
}
